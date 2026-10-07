package com.example.ui.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 漫画图像处理管线（真实像素处理，全部非破坏性：只作用于解码后的位图副本）。
 *
 * 处理顺序：裁边 → 拆片 → 旋转 → 色调(LUT/色矩阵) → 锐化/增强 → 放大。
 * 在 Dispatchers.Default 上执行，结果由 [ComicPageLoader] 缓存。
 *
 * 二次精修（开源算法本地化，详见 docs/WHEEL_EVALUATION.md）：
 * - 自动裁边 v2：分块扫描 + RGB 逐通道容差 + 行/列密度噪声容忍 + 单边 1/3 防御
 *   （结构移植自 Kotatsu EdgeDetector/TrimTransformation，Apache-2.0）
 * - Anime4K 档：FastLineDarken 形态学线条重建（膨胀→腐蚀背景场 + 深度比例加深，
 *   平坦区零扰动；算法参考 AviSynth FastLineDarken 思路重写）
 * - CAS / Unsharp / Lanczos 输出统一 overshoot 限幅
 *   （clamp(out, min3x3−ov, max3x3+ov)，AMD FidelityFX CAS 同款思想，MIT）
 * - 新增中央装订缝（gutter）检测供拆页判定（ScanTailor VertLineFinder 思想重写，仅借鉴）
 */
object ComicImagePipeline {

    /** 处理后位图长边上限（内存与性能护栏） */
    // 新反馈第 5 条：2560→3200——lanczos 2x（1600 级源）与超分回原尺寸不再被逐维钳小（
    // 解码上限 DECODE_MAX_EDGE=2800，3200 容纳其 2x 档与回程放大；内存 ~37MB IntArray 可控）
    const val MAX_EDGE = 3200

    data class Geometry(
        val half: ComicSplitHalf = ComicSplitHalf.FULL,
        val splitPosition: Float = 0.5f,
        val rotationDeg: Int = 0,          // 整本 + 单页合成后的角度
        val cropMode: ComicCropMode = ComicCropMode.OFF,
        val manualCrop: List<Float>? = null, // [l,t,r,b] normalized
    )

    data class Toning(
        val brightness: Int = 0,
        val contrast: Int = 0,
        val saturation: Int = 0,
        val hue: Int = 0,
        val gamma: Float = 1.0f,
        val sharpen: Int = 0,
        val shadow: Int = 0,
        val bw: Boolean = false,
        val enhanceMode: ComicEnhanceMode = ComicEnhanceMode.OFF,
        val enhanceStrength: Int = 60,
    )

    fun hasWork(geo: Geometry, tone: Toning): Boolean =
        geo.half != ComicSplitHalf.FULL || geo.rotationDeg % 360 != 0 ||
            geo.cropMode != ComicCropMode.OFF || geo.manualCrop != null ||
            tone.hasWork()

    fun Toning.hasWork(): Boolean = brightness != 0 || contrast != 0 || saturation != 0 ||
        hue != 0 || gamma != 1.0f || sharpen > 0 || shadow != 0 || bw ||
        (enhanceMode != ComicEnhanceMode.OFF && enhanceStrength > 0)

    /** [Toning.hasWork] 的对外导出（成员扩展无法在 object 外直接调用） */
    fun toningHasWork(tone: Toning): Boolean = tone.hasWork()

    /**
     * 增强档耗时预估（第六轮第 5 条：加载占位提示"处理中，约 X 秒"）。
     * 系数来自 EnhanceBenchmarkTest 实测（JVM）× ~2.2 真机因子，0.5s 步进。
     * 估算而非精确值——目的是让用户知道"在处理而不是卡死"。
     */
    fun enhanceEstimateSec(mode: ComicEnhanceMode, strength: Int, longEdge: Int): Double {
        if (strength <= 0) return 0.0
        val base = when (mode) {
            ComicEnhanceMode.OFF -> return 0.0
            ComicEnhanceMode.CAS -> 0.4
            ComicEnhanceMode.ANIME4K -> 1.9
            ComicEnhanceMode.WAIFU2X -> if (longEdge >= 2400) 1.7 else 2.5
            ComicEnhanceMode.SUPER_RES -> if (longEdge <= 1800) 1.3 else 0.4
        }
        val s = (strength.coerceIn(0, 100) + 25) / 125f
        val areaFactor = (longEdge / 1600.0).let { it * it }.coerceIn(0.45, 1.6)
        val sec = base * s * (0.5 + 0.5 * areaFactor) * 2.2
        return (kotlin.math.ceil(sec * 2) / 2).coerceAtLeast(0.5)
    }

    /**
     * 行条带并行（第六轮第 5 条性能）：重像素核（CAS/Unsharp/形态学/CNN/Lanczos）
     * 按行分片到 Dispatchers.Default 多核执行。调用约束：body 只写 [y0,y1) 行、
     * 只读共享输入（越界±1行读取安全——输入缓冲在处理期间不可变）。
     * 输入/输出为独立缓冲的核天然满足；同缓冲原位核不适用。
     */
    internal fun parallelStripes(rows: Int, body: (y0: Int, y1: Int) -> Unit) {
        val cores = Runtime.getRuntime().availableProcessors()
        val minChunk = 96
        // 小任务串行：协程调度开销在小图上会倒挂（实测 CAS 1000x1400 +135ms）
        if (rows < 768 || cores <= 1) {
            body(0, rows)
            return
        }
        val stripes = minOf(cores, rows / minChunk)
        if (stripes <= 1) {
            body(0, rows)
            return
        }
        runBlocking {
            val per = (rows + stripes - 1) / stripes
            (0 until stripes).map { i ->
                async(Dispatchers.Default) {
                    val y0 = i * per
                    val y1 = minOf(y0 + per, rows)
                    if (y0 < y1) body(y0, y1)
                }
            }.awaitAll()
        }
    }

    /* ─────────────── 主入口 ─────────────── */

    fun process(src: Bitmap, geo: Geometry, tone: Toning): Bitmap {
        var bmp = src
        // 1. 裁边（自动/手动，基于原始方向）
        bmp = applyCrop(bmp, geo)
        // 2. 拆片（跨页扫描左右半）
        bmp = applySplit(bmp, geo)
        // 3. 旋转（整本 + 单页）
        if (geo.rotationDeg % 360 != 0) bmp = rotate(bmp, geo.rotationDeg % 360)
        // 4. 色调 / 滤镜
        if (tone.hasWork()) bmp = applyToning(bmp, tone)
        return bmp
    }

    /* ─────────────── 几何变换 ─────────────── */

    private fun applyCrop(src: Bitmap, geo: Geometry): Bitmap {
        val rect = when {
            geo.manualCrop != null && geo.manualCrop.size == 4 -> {
                val (l, t, r, b) = geo.manualCrop
                intArrayOf(
                    (l.coerceIn(0f, 1f) * src.width).roundToInt(),
                    (t.coerceIn(0f, 1f) * src.height).roundToInt(),
                    (r.coerceIn(0f, 1f) * src.width).roundToInt(),
                    (b.coerceIn(0f, 1f) * src.height).roundToInt()
                )
            }
            geo.cropMode != ComicCropMode.OFF -> detectContentRect(src, geo.cropMode)
            else -> null
        } ?: return src
        if (src.width < 4 || src.height < 4) return src
        val x = rect[0].coerceIn(0, src.width - 2)
        val y = rect[1].coerceIn(0, src.height - 2)
        val w = (rect[2] - rect[0]).coerceIn(2, src.width - x)
        val h = (rect[3] - rect[1]).coerceIn(2, src.height - y)
        if (w >= src.width && h >= src.height) return src
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    private fun applySplit(src: Bitmap, geo: Geometry): Bitmap {
        if (geo.half == ComicSplitHalf.FULL) return src
        val cut = geo.splitPosition.coerceIn(0.3f, 0.7f)
        return when (geo.half) {
            ComicSplitHalf.LEFT ->
                Bitmap.createBitmap(src, 0, 0, (src.width * cut).roundToInt().coerceIn(2, src.width), src.height)
            ComicSplitHalf.RIGHT -> {
                val x = (src.width * cut).roundToInt()
                Bitmap.createBitmap(src, x.coerceIn(0, src.width - 2), 0, src.width - x.coerceIn(0, src.width - 2), src.height)
            }
            else -> src
        }
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /* ─────────────── 自动裁边检测 v2 ─────────────── */

    /** 裁边检测内部参数（Kotatsu EdgeDetector 量级：容差 16、单边 1/3 防御） */
    private const val CROP_TOLERANCE = 16
    // 扫描缩略边长上限：必须让真实漫画格线（2~4px @1400px 级页面）在缩略图上
    // ≥1 整列——512 时 3px 线缩成 0.77px 亚像素，最近邻采样 ~23% 概率整列错过
    //（批量实测 almost-white 1/10 误裁 80px 的根因）；768 时 3px→1.37px 必命中。
    // 扫描像素 2.25× 仍是毫秒级，且内容判定已是 run 语义，噪声容忍不受影响
    private const val CROP_SCAN_MAX_EDGE = 768

    /**
     * 内容区域检测 v2：降采样 → 估计边框基准色（边缘环 RGB 中位数）→
     * 逐边向内分块扫描（行/列密度判定，容忍 JPEG 噪点）→ 单边 1/3 防御 → 全局 30% 保护。
     *
     * 白边/黑边/彩边/米色纸底统一走「与基准色逐通道比较」路线（Kotatsu TrimTransformation 思想）。
     * 返回 [l,t,r,b]（原图坐标）；检测失败返回 null 表示不裁。
     */
    fun detectContentRect(src: Bitmap, mode: ComicCropMode): IntArray? {
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return null

        // 1. 降采样（加速 + 平滑噪声）
        val scale = min(1f, CROP_SCAN_MAX_EDGE.toFloat() / max(w, h))
        val sw = max(8, (w * scale).roundToInt())
        val sh = max(8, (h * scale).roundToInt())
        val small = if (scale < 1f) {
            val m = Matrix().apply { setScale(scale, scale) }
            Bitmap.createBitmap(src, 0, 0, w, h, m, true)
        } else src
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()

        // 2. 边框基准色：边缘环（3px）像素逐通道中位数
        val ringR = ArrayList<Int>(8 * (sw + sh))
        val ringG = ArrayList<Int>(8 * (sw + sh))
        val ringB = ArrayList<Int>(8 * (sw + sh))
        val ring = 3
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                if (x < ring || y < ring || x >= sw - ring || y >= sh - ring) {
                    val p = px[y * sw + x]
                    ringR.add((p shr 16) and 0xFF)
                    ringG.add((p shr 8) and 0xFF)
                    ringB.add(p and 0xFF)
                }
            }
        }
        fun median(list: ArrayList<Int>): Int {
            list.sort()
            return list[list.size / 2]
        }
        val br = median(ringR); val bg = median(ringG); val bb = median(ringB)

        // 模式约束：白边模式要求边框偏亮、黑边模式要求偏暗，不匹配则不裁（防误裁）
        val borderLuma = (br * 299 + bg * 587 + bb * 114) / 1000
        when (mode) {
            ComicCropMode.WHITE -> if (borderLuma < 128) return null
            ComicCropMode.BLACK -> if (borderLuma > 127) return null
            ComicCropMode.AUTO -> Unit
            ComicCropMode.OFF -> return null
        }

        // 3. 逐通道容差的内容判定 + 连续段（run）阈值（容忍边缘噪点/灰尘）
        fun isContent(p: Int): Boolean {
            val dr = abs(((p shr 16) and 0xFF) - br)
            val dg = abs(((p shr 8) and 0xFF) - bg)
            val db = abs((p and 0xFF) - bb)
            return dr > CROP_TOLERANCE || dg > CROP_TOLERANCE || db > CROP_TOLERANCE
        }

        val stepY = max(1, sh / 96)
        val stepX = max(1, sw / 96)
        val samplesPerColumn = (sh + stepY - 1) / stepY
        val samplesPerRow = (sw + stepX - 1) / stepX
        // 连续段（run）判定：内容是连续特征（线/块贯穿采样方向），灰尘噪声是
        // 孤立点（run≈1~2）。旧版总计数密度阈值在缩略图上会被噪声放大击穿——
        // 原图 0.8% 的灰尘经 3.9× 下采样聚成 ~11% 缩略像素密度，散点即可凑够
        // 1/16 计数，把噪声行/列误判成内容行/列（批量实测 white-std 6/10 漏裁）。
        // run 要求 ≥ 该方向采样数的 5%（且 ≥3），对孤立噪声免疫、细线/气泡弧宽容
        val lineRunCol = max(3, samplesPerColumn / 20)
        val lineRunRow = max(3, samplesPerRow / 20)

        fun columnHasContent(x: Int): Boolean {
            var run = 0
            var y = 0
            while (y < sh) {
                if (isContent(px[y * sw + x])) {
                    run++
                    if (run >= lineRunCol) return true
                } else {
                    run = 0
                }
                y += stepY
            }
            return false
        }

        fun rowHasContent(y: Int): Boolean {
            var run = 0
            var x = 0
            while (x < sw) {
                if (isContent(px[y * sw + x])) {
                    run++
                    if (run >= lineRunRow) return true
                } else {
                    run = 0
                }
                x += stepX
            }
            return false
        }

        // 4. 单边 1/3 防御：扫过 1/3 仍无内容 → 该边视为全内容（放弃该边裁剪）
        val limitX = sw / 3
        val limitY = sh / 3
        var left = -1
        var xScan = 0
        while (xScan < limitX) {
            if (columnHasContent(xScan)) { left = xScan; break }
            xScan++
        }

        var right = -1
        var xScanR = sw - 1
        while (xScanR >= sw - limitX) {
            if (columnHasContent(xScanR)) { right = xScanR; break }
            xScanR--
        }

        var top = -1
        var yScan = 0
        while (yScan < limitY) {
            if (rowHasContent(yScan)) { top = yScan; break }
            yScan++
        }

        var bottom = -1
        var yScanB = sh - 1
        while (yScanB >= sh - limitY) {
            if (rowHasContent(yScanB)) { bottom = yScanB; break }
            yScanB--
        }

        // 整页找不到任何内容（全白/全黑/全纯色）→ 不裁
        if (left < 0 && right < 0 && top < 0 && bottom < 0) return null

        if (left < 0) left = 0
        if (right < 0) right = sw - 1
        if (top < 0) top = 0
        if (bottom < 0) bottom = sh - 1

        if (right <= left || bottom <= top) return null

        // 5. 映射回原图坐标 + 1% 安全边距
        val inv = 1f / scale
        val mx = max(1, w / 100)
        val my = max(1, h / 100)
        val L = max(0, (left * inv).roundToInt() - mx)
        val T = max(0, (top * inv).roundToInt() - my)
        val R = min(w - 1, (right * inv).roundToInt() + mx)
        val B = min(h - 1, (bottom * inv).roundToInt() + my)

        // 6. 内容太小视为检测失败（避免把整页误裁成一行字）
        if ((R - L) < w * 0.3f || (B - T) < h * 0.3f) return null
        return intArrayOf(L, T, R, B)
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /* ─────────────── 中央装订缝（gutter）检测 ─────────────── */

    /**
     * 判断一张宽图是否是「跨页扫描」（中央存在装订缝特征）。
     * v3 平台差分法（ScanTailor VertLineFinder / PageLayoutEstimator 思想重写）：
     * 装订缝 = 「窄带 + 两侧最近等值平台」的结构模式——
     * - 带内列亮度（中位数，贯穿全高的缝 vs 部分高度的块）与两侧平台差
     *   >depth；谷（扫描缝阴影）与峰（黑底纸白装订线）对称处理；
     * - 带宽 2 列~6% 页宽（缝物理宽 1.5%~5%；单列内容竖线不成带）；
     * - 两侧平台 = 带边外 14% 页宽内最近的「3 连续等值列」（纸面页边距），
     *   不依赖全局背景基线：画面块占基线窗口近半时中值基线被击穿
     *   （50% 污染点，批量实测块贴缝场景漏拆根因）；
     * - 左右两半内容方差校验（列均值），排除空页。
     */
    /** [detectCenterGutter] 的诊断版：verdict + 各级判定中间量（批量统计/调试用） */
    class GutterDiag(
        val isGutter: Boolean,
        val reason: String,
        val bestScore: Float,
        val bandWidth: Int,
        val hasPlates: Boolean,
        val varL: Float,
        val varR: Float,
        /** 缝中心归一化 x（0..1；第 19 条：拆分位置精确落在装订缝上），非缝为 NaN */
        val position: Float = Float.NaN,
        /** 缝带左缘列（缩略图坐标） */
        val bandStart: Int = -1,
    )

    fun detectCenterGutter(src: Bitmap): Boolean = detectCenterGutterDetail(src).isGutter

    fun detectCenterGutterDetail(src: Bitmap): GutterDiag {
        val w = src.width
        val h = src.height
        if (w < 64 || h < 64) return GutterDiag(false, "size<64", 0f, 0, false, 0f, 0f)
        // 降采样到宽 ~256
        val scale = min(1f, 256f / w)
        val sw = max(64, (w * scale).roundToInt())
        val sh = max(64, (h * scale).roundToInt())
        val small = if (scale < 1f) {
            val m = Matrix().apply { setScale(scale, scale) }
            Bitmap.createBitmap(src, 0, 0, w, h, m, true)
        } else src
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()

        val stepY = max(1, sh / 64)
        val depth = 18f

        // 列亮度中位数：装订缝物理上贯穿整页高（书脊），中位数=缝值；
        // 实心画面块只占部分高度（20%~45%），中位数仍是纸面——
        // 用列均值会把部分高度的块平均成"假缝带"（批量实测 no-gutter 9/10 误拆）
        val colMedianCache = HashMap<Int, Float>()
        fun colMedian(x: Int): Float = colMedianCache.getOrPut(x) {
            val vals = IntArray((sh + stepY - 1) / stepY)
            var n = 0
            var y = 0
            while (y < sh) {
                vals[n++] = luma(px[y * sw + x])
                y += stepY
            }
            vals.sort()
            if (n % 2 == 1) vals[n / 2].toFloat() else (vals[n / 2 - 1] + vals[n / 2]) / 2f
        }

        fun colMean(x: Int): Float {
            var sum = 0L
            var n = 0
            var y = 0
            while (y < sh) {
                sum += luma(px[y * sw + x])
                n++
                y += stepY
            }
            return sum.toFloat() / n
        }

        // 装订缝常偏离几何中心 2~5%：在中央带 [0.42w, 0.58w] 窗口内搜索
        val w0 = (sw * 0.42f).toInt()
        val w1 = (sw * 0.58f).toInt()
        fun cm(x: Int): Float = colMedian(x)

        // ── v3 平台差分法：装订缝 = 「窄带 + 两侧最近的等值平台」结构 ──
        // 不算全局背景基线——画面块占基线窗口近半时，中值基线被击穿
        //（经典 50% 污染点；批测 scan-shadow 块贴缝场景漏拆的根因），
        // 块外纸面成假"亮带"、真缝两侧平台全毁。改为直接搜结构模式：
        // 带内亮度与两侧平台差 >depth，带宽 2 列~6% 页宽（缝物理宽 1.5%~5%）
        val maxBw = (sw * 0.06f).toInt() + 2

        // 等值平台：连续 3 列两两差 <4（列值是中位数，已抗孤立噪点）
        val flatCache = FloatArray(sw) { Float.NaN }
        fun flat(j: Int): Float {
            if (j < 0 || j + 2 >= sw) return Float.NaN
            val v = flatCache[j]
            if (!v.isNaN()) return v
            val a = cm(j); val b = cm(j + 1); val c = cm(j + 2)
            val r = if (abs(b - a) < 4f && abs(c - b) < 4f) {
                (a + b + c) / 3f
            } else Float.NaN
            flatCache[j] = r
            return r
        }

        // 从 from 向 dir 找最近的等值平台（0.14w 内；真实缝外页边距 0.5%~3%
        // 紧贴带边，块/内容区在更远处）
        fun nearestFlat(from: Int, dir: Int): Float {
            var j = from
            val end = from + dir * (sw * 0.14f).toInt()
            while (if (dir > 0) j <= end else j >= end) {
                val v = flat(j)
                if (!v.isNaN()) return v
                j += dir
            }
            return Float.NaN
        }

        var bestDev = 0f
        var bestLo = -1
        var bestBw = 0
        var x = w0
        while (x + 1 <= w1) {
            // 带内极值随 bw 增量维护
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            var bw = 1
            while (bw <= maxBw && x + bw - 1 <= w1) {
                val v = cm(x + bw - 1)
                if (v < mn) mn = v
                if (v > mx) mx = v
                if (bw >= 2) {
                    // 平台起点外移，保证平台段 [j, j+2] 不与带重叠
                    val refL = nearestFlat(x - 3, -1)
                    val refR = nearestFlat(x + bw + 2, +1)
                    if (!refL.isNaN() && !refR.isNaN()) {
                        val ref = (refL + refR) / 2f
                        // 带边界夹逼：带外紧邻列必须已接近平台（|cm−ref|<depth）——
                        // 真缝被页边距夹住；画面块内切出的子窗其窗外仍是块值，
                        // 带内方向一致性拦不住这种"完美谷带"（批测 no-gutter 误拆）
                        val confined = x > 0 && x + bw <= w1 &&
                            abs(cm(x - 1) - ref) < depth && abs(cm(x + bw) - ref) < depth
                        if (confined) {
                            // 带内方向一致性：谷带要求带内最亮列也偏离（mx ≤ ref−0.6d）、
                            // 峰带要求最暗列也偏离——否则窗口里混入平台色列
                            //（画面块边缘+纸面的混合窗口），不是均匀缝带
                            val devValley = ref - mn
                            val devPeak = mx - ref
                            val dev = when {
                                devValley >= devPeak && mx <= ref - depth * 0.6f -> devValley
                                devPeak > devValley && mn >= ref + depth * 0.6f -> devPeak
                                else -> 0f
                            }
                            if (dev > bestDev) { bestDev = dev; bestLo = x; bestBw = bw }
                        }
                    }
                }
                bw++
            }
            x++
        }
        val plates = bestLo >= 0
        if (!plates) return GutterDiag(false, "no-band", bestDev, 0, false, 0f, 0f)

        // 两侧内容方差（有内容才有意义）——用列均值：方差要反映的正是
        // 画面块带来的波动，列中位数会把块抹平（median 只用于缝特征提取）
        fun bandVariance(x0: Int, x1: Int): Float {
            val means = ArrayList<Float>(max(1, x1 - x0))
            for (bx in x0 until x1 step 2) means.add(colMean(bx))
            if (means.isEmpty()) return 0f
            val avg = means.sum() / means.size
            var v = 0f
            means.forEach { v += (it - avg) * (it - avg) }
            return v / means.size
        }
        val varL = bandVariance((sw * 0.05f).toInt(), (sw * 0.40f).toInt())
        val varR = bandVariance((sw * 0.60f).toInt(), (sw * 0.95f).toInt())
        val hasContent = varL > 25f && varR > 25f
        val ok = hasContent && bestDev > depth
        // 缝中心（带中点，缩略图列 → 归一化）：第 19 条拆分位置基准
        val pos = if (ok) (bestLo + bestBw / 2f) / sw else Float.NaN
        val reason = when {
            ok -> "ok"
            !hasContent -> "no-content(varL=${varL.toInt()} varR=${varR.toInt()})"
            else -> "shallow(dev=$bestDev)"
        }

        return GutterDiag(ok, reason, bestDev, bestBw, plates, varL, varR, pos, bestLo)
    }

    /* ─────────────── 色调 / 滤镜 ─────────────── */

    private fun applyToning(src: Bitmap, tone: Toning): Bitmap {
        var bmp = src
        // 4a. 亮度/对比度/Gamma/阴影 → LUT
        if (tone.brightness != 0 || tone.contrast != 0 || tone.gamma != 1.0f || tone.shadow != 0) {
            bmp = applyLut(bmp, buildToneLut(tone))
        }
        // 4b. 饱和度/色调/黑白 → 色矩阵
        if (tone.saturation != 0 || tone.hue != 0 || tone.bw) {
            bmp = applyColorMatrix(bmp, buildFilterMatrix(tone))
        }
        // 4c. 增强（新反馈第 5 条重做：四档输出恒 ≥ 原分辨率。旧版先 capForPixelOp
        // 缩到 2M 像素、CNN 内部再钳 1280/900，处理完显示时反向拉伸——净效果是
        // 变软而非增强，"完全看不出"即此。各档自带预算，见对应函数）
        // 第六轮第 5 条终审调参（视觉代理实测驱动）：
        // - CAS = 边缘掩码锐化 + 轻量线深（纯锐化在线稿上被 overshoot 钳制，
        //   实测 meanAbsDiff 0.95 "一眼看不出"——叠加线深后线条可辨地更实；
        //   与 ANIME4K 档的区隔 = 无 CNN/无预降噪，档间 diff 仍有量化断言）；
        // - SUPER_RES = Lanczos 2x 重建 + 边缘掩码强锐化；
        // - ANIME4K 平坦降噪 0.20→0.12（0.20 在噪声底上产生整体灰移，观感变脏）。
        val strength = tone.enhanceStrength / 100f
        bmp = when (if (tone.enhanceStrength <= 0) ComicEnhanceMode.OFF else tone.enhanceMode) {
            ComicEnhanceMode.CAS -> casSharpenEdges(
                anime4kLines(bmp, strength, kExtra = 0.34f + 0.34f * strength),
                0.45f + 0.4f * strength, edgeRange = 26,
            )
            ComicEnhanceMode.ANIME4K -> anime4kRestore(bmp, strength)
            ComicEnhanceMode.WAIFU2X -> anime4kUpscale(bmp, strength)
            ComicEnhanceMode.SUPER_RES -> superResolution(bmp, strength)
            ComicEnhanceMode.OFF -> bmp
        }
        // 4d. 常规锐化（同样过尺寸护栏：2800px 全尺寸 unsharp 是 ~67MB 瞬时分配）
        if (tone.sharpen > 0) {
            bmp = unsharpMask(capForPixelOp(bmp), tone.sharpen / 100f)
        }
        return bmp
    }

    /** 逐像素增强操作前的尺寸护栏（>2M 像素先等比降到 ~2M，防卡顿/OOM） */
    private fun capForPixelOp(src: Bitmap): Bitmap {
        val px = src.width.toLong() * src.height.toLong()
        if (px <= 2_000_000L) return src
        val scale = kotlin.math.sqrt(2_000_000f / px)
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /** 亮度/对比度/Gamma/阴影像素 LUT */
    fun buildToneLut(tone: Toning): IntArray {
        val bright = tone.brightness / 100f * 48f
        val contrast = 1f + tone.contrast / 100f * 0.6f
        val gamma = tone.gamma.coerceIn(0.5f, 2.2f)
        val shadow = tone.shadow / 100f
        val lut = IntArray(256)
        for (i in 0..255) {
            var v = i / 255f
            v = (v + bright / 255f).coerceIn(0f, 1f)
            v = ((v - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
            v = v.pow(1f / gamma)
            if (shadow > 0) {
                // 提亮暗部：低权重区抬升
                val w = (1f - v).coerceIn(0f, 1f)
                v = (v + shadow * 0.45f * w * w).coerceIn(0f, 1f)
            } else if (shadow < 0) {
                // 压暗暗部：越暗压越多、纯黑不动（防线稿/网点被压死成黑块；
                // 旧版此分支与提亮分支完全相同，"加深阴影"语义未实现）
                val w = (1f - v).coerceIn(0f, 1f)
                v = (v * (1f + shadow * 0.45f * w)).coerceIn(0f, 1f)
            }
            lut[i] = (v * 255f).roundToInt().coerceIn(0, 255)
        }
        return lut
    }

    fun applyLut(src: Bitmap, lut: IntArray): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = lut[(p shr 16) and 0xFF]
            val g = lut[(p shr 8) and 0xFF]
            val b = lut[p and 0xFF]
            pixels[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        return createBitmap(w, h, pixels)
    }

    /** 饱和度/色调/黑白色矩阵 */
    fun buildFilterMatrix(tone: Toning): ColorMatrix {
        val sat = when {
            tone.bw -> 0f
            tone.saturation != 0 -> (1f + tone.saturation / 100f).coerceIn(0f, 3f)
            else -> 1f
        }
        val m = ColorMatrix().apply { setSaturation(sat) }
        if (tone.hue != 0 && !tone.bw) {
            m.postConcat(hueMatrix(tone.hue.toFloat()))
        }
        return m
    }

    private fun hueMatrix(degrees: Float): ColorMatrix {
        // 标准 hue 旋转：SVG/CSS filter feColorMatrix hueRotate 矩阵（绕亮度轴旋转）。
        // 旧实现"红轴+蓝轴双旋转"并非色相旋转，±180° 极值下通道混洗产生非预期色偏
        val rad = degrees * Math.PI.toFloat() / 180f
        val c = kotlin.math.cos(rad)
        val s = kotlin.math.sin(rad)
        val m = FloatArray(20)
        m[0] = 0.213f + c * 0.787f - s * 0.213f
        m[1] = 0.715f - c * 0.715f - s * 0.715f
        m[2] = 0.072f - c * 0.072f + s * 0.928f
        m[5] = 0.213f - c * 0.213f + s * 0.143f
        m[6] = 0.715f + c * 0.285f + s * 0.140f
        m[7] = 0.072f - c * 0.072f - s * 0.283f
        m[10] = 0.213f - c * 0.213f - s * 0.787f
        m[11] = 0.715f - c * 0.715f + s * 0.715f
        m[12] = 0.072f + c * 0.928f + s * 0.072f
        m[18] = 1f
        return ColorMatrix(m)
    }

    fun applyColorMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
            isFilterBitmap = true
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /* ─────────────── 锐化 / 增强 ─────────────── */

    /** overshoot 限幅半宽（AMD CAS 量级 ≈ 0.061 × 255 ≈ 16） */
    private const val OVERSHOOT_CLAMP = 16

    /**
     * 经典 Unsharp Mask（3x3），amount 0..1。
     * 输出限幅到十字邻域 [min−16, max+16]，消灭白边/halo。
     */
    fun unsharpMask(src: Bitmap, amount: Float): Bitmap {
        if (amount <= 0f) return src
        val k = amount * 1.2f
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = pixels.copyOf()
        parallelStripes(h - 2) { ys, ye ->
            for (y in 1 + ys until 1 + ye) {
                for (x in 1 until w - 1) {
                    val idx = y * w + x
                    val c = pixels[idx]
                    fun ch(shift: Int): Int {
                        val v = (c shr shift) and 0xFF
                        val a = (pixels[idx - 1] shr shift) and 0xFF
                        val b = (pixels[idx + 1] shr shift) and 0xFF
                        val d = (pixels[idx - w] shr shift) and 0xFF
                        val e = (pixels[idx + w] shr shift) and 0xFF
                        val hi = max(max(a, b), max(d, e))
                        val lo = min(min(a, b), min(d, e))
                        val nv = (v + (v * 4 - a - b - d - e) * k / 4f).roundToInt()
                        return nv.coerceIn(lo - OVERSHOOT_CLAMP, hi + OVERSHOOT_CLAMP).coerceIn(0, 255)
                    }
                    out[idx] = (c and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                }
            }
        }
        copyBorderPixels(out, w, h)
        return createBitmap(w, h, out)
    }

    /**
     * 处理循环跳过边缘 1px（1 until h-1）时，边缘保持原始像素——
     * 锐化后内部与边缘形成可见接缝。用内邻行/列覆盖边缘像素。
     */
    private fun copyBorderPixels(out: IntArray, w: Int, h: Int) {
        if (w <= 2 || h <= 2) return
        for (x in 0 until w) {
            out[x] = out[x + w]                 // 顶行 ← 次行
            out[(h - 1) * w + x] = out[(h - 2) * w + x] // 底行 ← 次底行
        }
        for (y in 0 until h) {
            out[y * w] = out[y * w + 1]                 // 左列 ← 次列
            out[y * w + w - 1] = out[y * w + w - 2]     // 右列 ← 次右列
        }
    }

    /**
     * 对比度自适应锐化（AMD CAS 简化版 + overshoot 限幅）——
     * 平面区域强提升、边缘区弱提升防光晕；输出钳制到十字邻域极值 ±16。
     */
    fun casSharpen(src: Bitmap, amount: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = pixels.copyOf()
        parallelStripes(h - 2) { ys, ye ->
            for (y in 1 + ys until 1 + ye) {
                for (x in 1 until w - 1) {
                    val idx = y * w + x
                    val c = pixels[idx]
                    val cr = (c shr 16) and 0xFF
                    val cg = (c shr 8) and 0xFF
                    val cb = c and 0xFF
                    val a = pixels[idx - 1]
                    val b = pixels[idx + 1]
                    val cc = pixels[idx - w]
                    val d = pixels[idx + w]
                    val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
                    val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
                    val cr2 = (cc shr 16) and 0xFF; val cg2 = (cc shr 8) and 0xFF; val cb2 = cc and 0xFF
                    val dr = (d shr 16) and 0xFF; val dg = (d shr 8) and 0xFF; val db = d and 0xFF
                    val minR = min(min(ar, br), min(cr2, dr)); val maxR = max(max(ar, br), max(cr2, dr))
                    val minG = min(min(ag, bg), min(cg2, dg)); val maxG = max(max(ag, bg), max(cg2, dg))
                    val minB = min(min(ab, bb), min(cb2, db)); val maxB = max(max(ab, bb), max(cb2, db))
                    val ampR = if (maxR > minR) amount * (1f - (maxR - minR) / 255f * 0.8f) else 0f
                    val ampG = if (maxG > minG) amount * (1f - (maxG - minG) / 255f * 0.8f) else 0f
                    val ampB = if (maxB > minB) amount * (1f - (maxB - minB) / 255f * 0.8f) else 0f
                    val nr = (cr + (cr * 2 - minR - maxR) * ampR).roundToInt()
                        .coerceIn(minR - OVERSHOOT_CLAMP, maxR + OVERSHOOT_CLAMP).coerceIn(0, 255)
                    val ng = (cg + (cg * 2 - minG - maxG) * ampG).roundToInt()
                        .coerceIn(minG - OVERSHOOT_CLAMP, maxG + OVERSHOOT_CLAMP).coerceIn(0, 255)
                    val nb = (cb + (cb * 2 - minB - maxB) * ampB).roundToInt()
                        .coerceIn(minB - OVERSHOOT_CLAMP, maxB + OVERSHOOT_CLAMP).coerceIn(0, 255)
                    out[idx] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
                }
            }
        }
        copyBorderPixels(out, w, h)
        return createBitmap(w, h, out)
    }

    /**
     * Anime4K 档：FastLineDarken 形态学线条重建（AviSynth FastLineDarken 思路重写）。
     *
     * 1. 背景场 = 亮度先 3x3 膨胀再 3x3 腐蚀（闭运算，抹掉线条得到"纸面"亮度）；
     * 2. 线深 = min(背景, lumaCap) − 当前亮度，仅当超过 threshold 才有效——
     *    平坦区 diff 恒 0 天然不动（无 Sobel 的噪声响应问题）；
     * 3. 按线深比例加深（深线多加深、淡线少加深，不把淡网点压成死黑）；
     * 4. 强度随 strength 缩放，附平坦区轻降噪。
     */
    fun anime4kLines(src: Bitmap, strength: Float, kExtra: Float = 0f): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        if (w < 4 || h < 4) return src

        val lumaCap = 191
        val threshold = 4f
        val k = 0.35f + 0.85f * strength + kExtra   // 加深系数（Restore 档叠加 kExtra 提高可辨度）
        val flatDenoise = ANIME4K_FLAT_DENOISE * strength // 平坦区降噪权重（第六轮 0.20→0.12 防灰移）

        // luma 数组（先求和再除，避免逐项截断）
        val lum = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            lum[i] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }

        // 分离式 5x5 闭运算（膨胀 H3V3×2 → 腐蚀 H3V3×2），覆盖 2~4px 常见漫画线宽。
        // 全程三个缓冲区引用交换，零中间拷贝（2M 像素下省 ~32MB 瞬时分配）。
        // 第六轮第 5 条：pass 级行条带并行（读写缓冲独立，±1 行越界只读安全）。
        val tmp = IntArray(w * h)
        val bufA = IntArray(w * h)
        val background = IntArray(w * h)
        fun hPass(src: IntArray, dst: IntArray, maxFilter: Boolean) {
            parallelStripes(h) { y0, y1 ->
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in 0 until w) {
                        val a = src[row + max(0, x - 1)]
                        val b = src[row + x]
                        val c = src[row + min(w - 1, x + 1)]
                        dst[row + x] = if (maxFilter) max(a, max(b, c)) else min(a, min(b, c))
                    }
                }
            }
        }
        fun vPass(src: IntArray, dst: IntArray, maxFilter: Boolean) {
            parallelStripes(h) { y0, y1 ->
                for (y in y0 until y1) {
                    val rUp = max(0, y - 1) * w
                    val rCur = y * w
                    val rDn = min(h - 1, y + 1) * w
                    for (x in 0 until w) {
                        val a = src[rUp + x]
                        val b = src[rCur + x]
                        val c = src[rDn + x]
                        dst[y * w + x] = if (maxFilter) max(a, max(b, c)) else min(a, min(b, c))
                    }
                }
            }
        }
        // 膨胀 ×2（等效 5x5 max）
        hPass(lum, tmp, maxFilter = true); vPass(tmp, bufA, maxFilter = true)
        hPass(bufA, tmp, maxFilter = true); vPass(tmp, bufA, maxFilter = true)
        // 腐蚀 ×2（等效 5x5 min）→ 背景场
        hPass(bufA, tmp, maxFilter = false); vPass(tmp, background, maxFilter = false)
        hPass(background, tmp, maxFilter = false); vPass(tmp, background, maxFilter = false)

        val out = pixels.copyOf()
        parallelStripes(h - 2) { ys, ye ->
            for (y in 1 + ys until 1 + ye) {
                for (x in 1 until w - 1) {
                    val idx = y * w + x
                    val l = lum[idx]
                    val bg = min(background[idx], lumaCap)
                    val diff = (bg - l).toFloat()
                    var delta = 0f
                    if (diff > threshold) {
                        // 线条：按深度加深（负 delta）；单像素压暗量上限 72——
                        // 否则 strength 拉满时深线 100→191 的 diff=91×k=1.2 直接推到 0
                        // （中间调瞬间死黑、与内部线稿糊成黑块）
                        delta = -diff.coerceAtMost(72f) * k
                    } else {
                        // 平坦区：向邻域亮度靠拢，轻降噪
                        val mean = (lum[idx - 1] + lum[idx + 1] + lum[idx - w] + lum[idx + w]) / 4f
                        delta = (mean - l) * flatDenoise
                    }
                    if (delta == 0f) continue
                    val c = pixels[idx]
                    fun channel(shift: Int): Int {
                        val v = (c shr shift) and 0xFF
                        // 以亮度 delta 同步作用到各通道（保持色相）
                        return (v + delta).roundToInt().coerceIn(0, 255)
                    }
                    out[idx] = (c and 0xFF000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
                }
            }
        }
        copyBorderPixels(out, w, h)
        return createBitmap(w, h, out)
    }

    /** Waifu2x 类：2x 边缘保持放大（bilinear 放大 + 双边滤波细化 + 限幅轻锐化） */
    fun waifu2xLike(src: Bitmap, strength: Float): Bitmap {
        val scale = if (max(src.width, src.height) * 2 > MAX_EDGE) {
            MAX_EDGE.toFloat() / max(src.width, src.height)
        } else 2f
        if (scale <= 1.05f) return casSharpen(src, 0.3f + 0.3f * strength)
        val m = Matrix().apply { setScale(scale, scale) }
        val up = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val denoised = bilateralLite(up, radius = 2, sigmaColor = 28f * (0.5f + 0.5f * strength))
        return unsharpMask(denoised, 0.25f + 0.45f * strength)
    }

    /**
     * ANIME4K 轻量档（新反馈第 5 条）：Restore CNN 线条重建/降噪（≤1600 长边预算）。
     * 钳制过的源用 Lanczos（非双线性）回原尺寸——细节保留；收尾用 anime4kLines
     * （FastLineDarken）而非全幅 CAS：线条按深度加深（锐利感）+ 平坦区向邻域均值轻降噪
     * ——全幅 CAS 会放大压缩噪声，与本档降噪定位冲突（平坦区噪声 RMS 实测反升 47%）。
     * 终审补强（B 路判定旧版"与原始几乎不可辨"）：①线深系数叠加 kExtra；
     * ②边缘掩码 CAS（仅 3×3 亮度极差 ≥34 的边缘像素锐化，平坦噪声不进）——
     * 差异幅度提升到人眼可辨，同时保持平坦区降噪特性。
     * 输出=原尺寸；降噪 + 线重建 + 线条加深 + 边缘锐化四重效果肉眼可辨。
     * 第六轮第 5 条：bilateralLite/lines/CAS/CNN 全部行条带并行（真机 ~3× 提速）。
     */
    fun anime4kRestore(src: Bitmap, strength: Float): Bitmap {
        // 预降噪：Restore 网络对 iid 噪声分布外（平坦噪声实测放大 1.8×）——
        // Anime4K 官方 Mode A 同样以降噪前置配合 Restore；bilateral 保边，
        // 线条不受影响，平坦噪声先清掉再进网络
        val den = bilateralLite(src, radius = 1, sigmaColor = 36f)
        val restored = Anime4KCnn.restore(den, strength)
        val native = if (restored.width != src.width || restored.height != src.height) {
            lanczosScaleTo(restored, src.width, src.height)
        } else restored
        val lines = anime4kLines(native, strength, kExtra = 0.25f + 0.35f * strength)
        return casSharpenEdges(lines, 0.30f + 0.35f * strength)
    }

    /** ANIME4K 平坦区降噪权重（第六轮下调：0.20→0.12——视觉终审实测 0.20 在
     *  噪声底上产生可感的整体灰移，观感"变脏"；0.12 保留降噪又无明显灰移） */
    internal const val ANIME4K_FLAT_DENOISE = 0.12f

    /**
     * 边缘掩码 CAS（第 5 条终审补强）：CAS 只作用于"3×3 亮度极差 ≥ [edgeRange]"
     * 的边缘像素及其 1px 邻域——软化漫画页的线条/网点边缘获得锐化增益，
     * 平坦区的压缩噪声（σ≤10 时 3×3 极差绝大多数 <34）保持原样，
     * 不放大噪声、不破坏降噪档的平坦区特性。
     */
    fun casSharpenEdges(src: Bitmap, amount: Float, edgeRange: Int = 34): Bitmap {
        if (amount <= 0f || src.width < 4 || src.height < 4) return src
        val sharpened = casSharpen(src, amount)
        val w = src.width
        val h = src.height
        val orig = IntArray(w * h)
        src.getPixels(orig, 0, w, 0, 0, w, h)
        val sharped = IntArray(w * h)
        sharpened.getPixels(sharped, 0, w, 0, 0, w, h)
        fun lum(p: Int) = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        val out = orig.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val c = lum(orig[i])
                var hit = false
                var dy = -1
                while (dy <= 1 && !hit) {
                    var dx = -1
                    while (dx <= 1) {
                        if (Math.abs(lum(orig[i + dy * w + dx]) - c) >= edgeRange) { hit = true; break }
                        dx++
                    }
                    dy++
                }
                if (hit) out[i] = sharped[i]
            }
        }
        return createBitmap(w, h, out)
    }

    /**
     * WAIFU2X 完整档（新反馈第 5 条 + 第六轮自适应）：
     * - 低/中分辨率源（长边 < 2400）：Upscale CNN 2x 真超分，输出 ≥ 原分辨率
     *   （旧版 900 钳制使 2800px 页"超分"后只剩 1800px，实为降级）；
     * - 高分辨率源（长边 ≥ 2400）：2x 输出超过显示密度上限，超分无物理增益且
     *   显示端缩回后不可辨（第六轮实测：BENCH lapRatio 0.28）——改为
     *   "高分辨率细节强化"：restore CNN + 全幅强 CAS（与 ANIME4K 档的
     *   边缘掩码轻锐化明确区隔，档间可辨性有量化断言）。
     */
    fun anime4kUpscale(src: Bitmap, strength: Float): Bitmap {
        val long = max(src.width, src.height)
        if (long < 8) return src
        if (long >= 2400) {
            // 高分辨率页：2x 无意义（显示 ≤2800），做同尺寸细节强化
            val den = bilateralLite(src, radius = 1, sigmaColor = 32f)
            val restored = Anime4KCnn.restore(den, strength)
            val native = if (restored.width != src.width || restored.height != src.height) {
                lanczosScaleTo(restored, src.width, src.height)
            } else restored
            return casSharpen(native, 0.55f + 0.4f * strength)
        }
        val targetLong = min(long * 2, 3200)
        val cnnSrcLong = (targetLong / 2).coerceAtLeast(8)
        val cnnSrc = if (long > cnnSrcLong) lanczosScale(src, cnnSrcLong.toFloat() / long) else src
        val up = Anime4KCnn.upscale2x(cnnSrc, strength, maxSrcEdge = cnnSrcLong)
        return if (up.width >= src.width && up.height >= src.height) up
        else lanczosScaleTo(up, max(src.width, up.width), max(src.height, up.height))
    }

    /**
     * 超分辨率（新反馈第 5 条重做）：低分辨率源（长边 ≤1800）真 Lanczos 2x 重建 +
     * 边缘掩码锐化；高分辨率源（显示密度已饱和，2x 无增益徒增内存）直接全分辨率
     * 边缘掩码锐化。输出恒 ≥ 原分辨率。第六轮：CAS 全幅版在噪声底上不可辨，
     * 与 CAS 档一同改边缘掩码强锐化（量级更高以区分 CAS 档）。
     */
    fun superResolution(src: Bitmap, strength: Float): Bitmap {
        val long = max(src.width, src.height)
        val casAmount = 0.7f + 0.5f * strength
        return if (long <= 1800) {
            casSharpenEdges(lanczosScale(src, 2f), casAmount)
        } else {
            casSharpenEdges(src, casAmount)
        }
    }

    /** 简化双边滤波（颜色相似度加权均值），边缘保持降噪 */
    fun bilateralLite(src: Bitmap, radius: Int, sigmaColor: Float): Bitmap {
        if (radius <= 0) return src
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = pixels.copyOf()
        val sigma2 = 2f * sigmaColor * sigmaColor
        // 步长保护：超大图降采样窗口，避免 O(w*h*(2r+1)^2) 爆炸
        val r = if (w * h > 2_000_000) 1 else radius
        parallelStripes(h - 2 * r) { ys, ye ->
            for (y in r + ys until r + ye) {
                for (x in r until w - r) {
                    val idx = y * w + x
                    val c = pixels[idx]
                    val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
                    var sw = 0f; var sr = 0f; var sg = 0f; var sb = 0f
                    for (dy in -r..r) {
                        for (dx in -r..r) {
                            val p = pixels[idx + dy * w + dx]
                            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
                            val dist2 = ((pr - cr) * (pr - cr) + (pg - cg) * (pg - cg) + (pb - cb) * (pb - cb)).toFloat()
                            val wgt = kotlin.math.exp(-dist2 / sigma2)
                            sw += wgt; sr += pr * wgt; sg += pg * wgt; sb += pb * wgt
                        }
                    }
                    if (sw > 0f) {
                        val nr = (sr / sw).roundToInt().coerceIn(0, 255)
                        val ng = (sg / sw).roundToInt().coerceIn(0, 255)
                        val nb = (sb / sw).roundToInt().coerceIn(0, 255)
                        out[idx] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
                    }
                }
            }
        }
        return createBitmap(w, h, out)
    }

    /**
     * Lanczos3 重采样（a=3）。两级 pass 均走 IntArray 批量读写：
     * 水平 pass 逐行 → IntArray 中间缓冲（打包 ARGB，仅为 Float 方案 1/4 内存）→ 垂直 pass 逐列 → 批量生成位图。
     */
    fun lanczosScale(src: Bitmap, scale: Float): Bitmap {
        if (scale <= 0f || abs(scale - 1f) < 0.01f) return src
        val nw = max(1, (src.width * scale).roundToInt()).coerceAtMost(MAX_EDGE)
        val nh = max(1, (src.height * scale).roundToInt()).coerceAtMost(MAX_EDGE)
        return lanczosScaleTo(src, nw, nh)
    }

    /**
     * Lanczos3 重采样到精确目标尺寸（第六轮第 5 条）：比例缩放的舍入会使
     * 回程尺寸偏 ±2px（2000x2800 → 2000x2802），FillBounds 渲染轻微拉伸。
     * 两级 pass 行条带并行。
     */
    fun lanczosScaleTo(src: Bitmap, nw: Int, nh: Int): Bitmap {
        if (nw <= 0 || nh <= 0) return src
        val sw = src.width
        val sh = src.height
        if (nw == sw && nh == sh) return src
        val scaleX = nw.toFloat() / sw
        val scaleY = nh.toFloat() / sh
        val srcPixels = IntArray(sw * sh)
        src.getPixels(srcPixels, 0, sw, 0, 0, sw, sh)
        val mid = IntArray(nw * sh)
        // 水平 pass（逐行，行并行）
        parallelStripes(sh) { y0, y1 ->
            for (y in y0 until y1) {
                val rowOff = y * sw
                for (x in 0 until nw) {
                    val sx = x / scaleX
                    val center = sx.toInt()
                    var r = 0f; var g = 0f; var b = 0f; var a = 0f; var wsum = 0f
                    for (t in -3..3) {
                        val sxx = (center + t).coerceIn(0, sw - 1)
                        val wgt = lanczos3(sx - sxx)
                        if (wgt == 0f) continue
                        val p = srcPixels[rowOff + sxx]
                        r += ((p shr 16) and 0xFF) * wgt
                        g += ((p shr 8) and 0xFF) * wgt
                        b += (p and 0xFF) * wgt
                        a += ((p shr 24) and 0xFF) * wgt
                        wsum += wgt
                    }
                    mid[y * nw + x] = packArgb(a, r, g, b, wsum)
                }
            }
        }
        // 垂直 pass（逐输出行，行并行）
        val outPixels = IntArray(nw * nh)
        parallelStripes(nh) { y0, y1 ->
            for (y in y0 until y1) {
                val sy = y / scaleY
                val center = sy.toInt()
                for (x in 0 until nw) {
                    var r = 0f; var g = 0f; var b = 0f; var a = 0f; var wsum = 0f
                    for (t in -3..3) {
                        val syy = (center + t).coerceIn(0, sh - 1)
                        val wgt = lanczos3(sy - syy)
                        if (wgt == 0f) continue
                        val p = mid[syy * nw + x]
                        r += ((p shr 16) and 0xFF) * wgt
                        g += ((p shr 8) and 0xFF) * wgt
                        b += (p and 0xFF) * wgt
                        a += ((p shr 24) and 0xFF) * wgt
                        wsum += wgt
                    }
                    outPixels[y * nw + x] = packArgb(a, r, g, b, wsum)
                }
            }
        }
        return createBitmap(nw, nh, outPixels)
    }

    private fun packArgb(a: Float, r: Float, g: Float, b: Float, wsum: Float): Int {
        if (wsum == 0f) return 0xFF000000.toInt()
        val nr = (r / wsum).roundToInt().coerceIn(0, 255)
        val ng = (g / wsum).roundToInt().coerceIn(0, 255)
        val nb = (b / wsum).roundToInt().coerceIn(0, 255)
        val na = (a / wsum).roundToInt().coerceIn(0, 255)
        return (na shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    private fun lanczos3(x: Float): Float {
        val v = abs(x)
        if (v < 1e-6f) return 1f
        if (v >= 3f) return 0f
        val pix = Math.PI.toFloat() * v
        return (3f * sin(pix) * sin(pix / 3f) / (pix * pix))
    }

    /** 通用 3x3 卷积 */
    fun convolve3x3(src: Bitmap, kernel: FloatArray): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = pixels.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                var r = 0f; var g = 0f; var b = 0f
                var ki = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val p = pixels[idx + dy * w + dx]
                        val k = kernel[ki++]
                        r += ((p shr 16) and 0xFF) * k
                        g += ((p shr 8) and 0xFF) * k
                        b += (p and 0xFF) * k
                    }
                }
                val c = pixels[idx]
                out[idx] = (c and 0xFF000000.toInt()) or
                    (r.roundToInt().coerceIn(0, 255) shl 16) or
                    (g.roundToInt().coerceIn(0, 255) shl 8) or
                    b.roundToInt().coerceIn(0, 255)
            }
        }
        return createBitmap(w, h, out)
    }

    private fun createBitmap(w: Int, h: Int, pixels: IntArray): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /* ─────────────── 沉浸式动态背景 ─────────────── */

    /**
     * 从页面提取主色调（用于沉浸式背景）：量化直方图 + 去极值 + 降饱和压暗。
     * 返回 ARGB 颜色。
     */
    fun dominantBackground(src: Bitmap): Int {
        val w = src.width
        val h = src.height
        if (w < 2 || h < 2) return 0xFF101014.toInt()
        val sw = 24
        val sh = 24
        val scale = min(sw.toFloat() / w, sh.toFloat() / h)
        val small = if (scale < 1f) {
            val m = Matrix().apply { setScale(scale, scale) }
            Bitmap.createBitmap(src, 0, 0, w, h, m, true)
        } else src

        val hist = HashMap<Int, IntArray>(256) // key: 量化rgb, value: [count, rSum, gSum, bSum]
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val p = small.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                // 过滤近黑/近白（对背景无意义的极端色）
                if (lum < 24 || lum > 238) continue
                val key = ((r shr 5) shl 10) or ((g shr 5) shl 5) or (b shr 5)
                val e = hist.getOrPut(key) { IntArray(4) }
                e[0]++; e[1] += r; e[2] += g; e[3] += b
            }
        }
        if (hist.isEmpty()) return 0xFF101014.toInt()
        val best = hist.maxByOrNull { it.value[0] }?.value ?: return 0xFF101014.toInt()
        var r = best[1] / best[0]
        var g = best[2] / best[0]
        var b = best[3] / best[0]
        // 降饱和 35% + 压暗到 ~25% 亮度：仍显著暗于页面（不抢主体），
        // 但保留可感知色调。旧参数（保留 30% 色度 + 压到亮度 34）会把任何
        // 输入压成几乎相同的暗灰——暖米/冷蓝纸底处理后色距仅 ~4/255，
        // 沉浸式渐变在录屏逐帧下不可见（第三轮复审实测）。
        val lum = (r * 299 + g * 587 + b * 114) / 1000
        r = (lum + (r - lum) * 0.65f).roundToInt()
        g = (lum + (g - lum) * 0.65f).roundToInt()
        b = (lum + (b - lum) * 0.65f).roundToInt()
        val targetLum = 64f
        val cur = max(1, (r * 299 + g * 587 + b * 114) / 1000)
        val f = targetLum / cur
        r = (r * f).roundToInt().coerceIn(0, 255)
        g = (g * f).roundToInt().coerceIn(0, 255)
        b = (b * f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
