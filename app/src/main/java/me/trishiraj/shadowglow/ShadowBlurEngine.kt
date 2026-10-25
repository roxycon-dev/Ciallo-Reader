/*
 * 确定性 CPU 模糊阴影引擎（Deterministic CPU Shadow Blur）
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 为什么需要它：
 *   旧实现（`drawBlurredShadowPath` / `shadowGlow`）用 `Paint.setMaskFilter(
 *   BlurMaskFilter(...))` 做模糊。按 Android 官方限制，**硬件加速画布不支持
 *   MaskFilter**（`View`/`RenderNode` 走 HW 管线时该 filter 被静默忽略），
 *   于是路径被当成「实心硬边形状」直接填色 —— 这正是上一版 UI 上出现的
 *   「大块硬边纯色框（品牌绿）」与「方框」。
 *
 *   本引擎改为 **在应用内用 CPU 做确定性模糊**：
 *     1. 把 shape 的 outline 路径按 `scale`（0.25/0.5/1.0）降采样画进一张
 *        ARGB_8888 位图，填充色即阴影色（含 alpha）；
 *     2. 只对**位图 alpha 通道**做三次盒式模糊（3× box blur ≈ 高斯，纯整数
 *        运算，与 GPU/ROM 无关，逐像素可复现），再按原阴影 RGB 重建像素；
 *     3. 用 `drawImage(..., filterQuality = Low)` 双线性放大回画布，
 *        模糊被平滑拉伸，看不出像素块；
 *     4. 结果按「轮廓签名 + 模糊半径 + 降采样比 + 描边宽 + 颜色」缓存
 *        （LruCache，字节预算），滚动/重组时零重算。
 *
 * ⚠️ 教训：**不要把 alpha 交给 `ColorFilter.tint(color, SrcIn)`**。实测在
 *   本工程环境下该 tint 会忽略 color 的 alpha、按满不透明色着色，导致阴影
 *   直接变成一块高饱和色（≈「大块纯色框」）。因此本引擎把「颜色 + alpha」
 *   直接烘焙进掩码位图，绘制时不再使用任何 colorFilter。
 *
 * 与 `Modifier.shadow` 的关系：
 *   参数语义（elevation→模糊&偏移、ambient/spot 双层的叠加）由调用方
 *   `consistentShadow` 对齐，本文件只提供「按给定 σ 画一层模糊形状」。
 */
package me.trishiraj.shadowglow

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import androidx.collection.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 【临时】性能采集开关。为 true 时每 [SHADOW_PERF_LOG_EVERY] 次解析打印一次缓存命中率到
 * logcat（tag = `ShadowPerf`）。**验收完成后必须置回 false**（不得留在交付包里）。
 */
/**
 * 【临时】性能采集开关。为 true 时每 [SHADOW_PERF_LOG_EVERY] 次解析打印一次
 * 静态桶 / 动画桶各自的缓存命中率到 logcat（tag = `ShadowPerf`）。
 * 已于 2026-09-23 采完数据后置回 **false**（不得留在交付包里）。
 * 需要复测时临时置 true 即可，两个桶的计数是分开的。
 */
private const val SHADOW_PERF_LOG = false
private const val SHADOW_PERF_LOG_EVERY = 120L

/**
 * 掩码缓存分桶。
 *
 * 为什么要分桶：**逐帧动画的辉光会挤掉静态阴影的掩码**。
 * `shadowGlow` 的呼吸效果让 σ 每帧变化 → 缓存键每帧不同 → 每帧插入一条新掩码；
 * 一个大按钮的呼吸辉光掩码在 0.25 降采样下约 178 KB，一个呼吸周期（σ 从 76px
 * 到 98px，量化到 0.5px）就是 ~45 条 ≈ 8 MB。若与静态阴影共用同一个 LruCache，
 * 这 8 MB 会持续处于「最近使用」状态，把 33 处 `consistentShadow` 的掩码全部挤出
 * 缓存 —— 滚动时每张卡片都要重新做一遍完整模糊。这正是「高」档不卡、而「极致」
 * 档（唯一启用 `shadowGlow` 呼吸+光尾的档位）变卡的主因之一。
 *
 * 分桶后：静态桶 10 MB 只服务稳定键；动画桶 4 MB 容纳整条呼吸周期
 * （降采样到 0.125 后整周期 ≈ 2–3.7 MB，随 density 变化），
 * 跑满一轮后呼吸辉光的模糊计算降为 0。
 */
internal enum class ShadowCacheBucket {
    /** 静态阴影（`consistentShadow`）：键稳定，命中率极高。 */
    STATIC,

    /** 逐帧动画辉光（`shadowGlow` 呼吸 / 光尾）：键随动画变化，预算小。 */
    ANIMATED,
}

/** 静态阴影掩码的字节预算（约 10 MB）。 */
private const val SHADOW_MASK_CACHE_BYTES_STATIC = 10 * 1024 * 1024

/**
 * 动画辉光掩码的字节预算（约 4 MB）。
 *
 * 目标：一整条呼吸周期（σ 从 0.577×48dp 到 0.577×62dp，量化到 0.5px ≈ 45 档）
 * 在 0.125 降采样下约占 2.0–3.7 MB（随设备 density 变化），留 4 MB 使其在高分屏上
 * 也能整周期驻留 —— 跑满一轮后呼吸辉光的模糊计算降为 0。
 */
private const val SHADOW_MASK_CACHE_BYTES_ANIMATED = 4 * 1024 * 1024

/** 掩码位图单边像素上限，防御性上限避免极端尺寸撑爆内存。 */
private const val SHADOW_MASK_MAX_SIDE = 2048

/** 【缓存键】轮廓签名 + 量化模糊量 + 降采样比 + 描边宽 + 颜色（值比较，与对象引用无关）。 */
private data class MaskKey(
    val shapeSig: String,
    val sigmaQ: Int,
    val scaleQ: Int,
    val strokeQ: Int,
    val colorArgb: Int,
)

private object ShadowMaskCache {
    private fun createLru(maxBytes: Int): LruCache<MaskKey, Bitmap> =
        object : LruCache<MaskKey, Bitmap>(maxBytes) {
            override fun sizeOf(key: MaskKey, value: Bitmap): Int = value.byteCount
        }

    val staticLru: LruCache<MaskKey, Bitmap> = createLru(SHADOW_MASK_CACHE_BYTES_STATIC)
    val animatedLru: LruCache<MaskKey, Bitmap> = createLru(SHADOW_MASK_CACHE_BYTES_ANIMATED)

    /** 命中/未命中计数（仅用于性能验收，开销为两次 Long 自增）—— 按桶分开统计。 */
    var hits: Long = 0L
        private set
    var misses: Long = 0L
        private set
    var hitsStatic: Long = 0L
        private set
    var missesStatic: Long = 0L
        private set
    var hitsAnimated: Long = 0L
        private set
    var missesAnimated: Long = 0L
        private set

    /** 掩码构建的累计次数与耗时（ns），用于算「平均单次构建耗时」。 */
    var builds: Long = 0L
        private set
    var buildNanos: Long = 0L
        private set

    fun recordBuild(nanos: Long) {
        builds++
        buildNanos += nanos
    }

    /** 平均单次掩码构建耗时（ms）；没有样本时返回 NaN。 */
    fun avgBuildMs(): Double =
        if (builds == 0L) Double.NaN else buildNanos / 1_000_000.0 / builds.toDouble()

    fun recordHit(bucket: ShadowCacheBucket) {
        hits++
        if (bucket == ShadowCacheBucket.ANIMATED) hitsAnimated++ else hitsStatic++
    }

    fun recordMiss(bucket: ShadowCacheBucket) {
        misses++
        if (bucket == ShadowCacheBucket.ANIMATED) missesAnimated++ else missesStatic++
    }

    private fun rate(h: Long, m: Long): String {
        val t = h + m
        return if (t == 0L) "n/a" else "%.1f%%".format(h * 100.0 / t)
    }

    fun statsText(): String =
        "total[hits=$hits misses=$misses rate=${rate(hits, misses)}] " +
            "STATIC[hits=$hitsStatic misses=$missesStatic rate=${rate(hitsStatic, missesStatic)}] " +
            "ANIMATED[hits=$hitsAnimated misses=$missesAnimated rate=${rate(hitsAnimated, missesAnimated)}] " +
            "avgBuildMs=${"%.3f".format(avgBuildMs())} (n=$builds)"

    fun lruFor(bucket: ShadowCacheBucket): LruCache<MaskKey, Bitmap> =
        if (bucket == ShadowCacheBucket.ANIMATED) animatedLru else staticLru
}

/**
 * 掩码构建的临时像素缓冲池。
 *
 * `buildMaskBitmap` 每次调用都需要 3 张 `IntArray`（源像素 / alpha 通道 / 模糊中间量）。
 * 在逐帧动画（呼吸辉光、巡游光尾）场景下这是每秒上百次的大块分配 → 明显的 GC 抖动。
 * 这里按「不小于申请长度的最小 4096-int 档位」复用数组：
 * 同档位同时借出源像素、alpha、水平临时量及列累计量，每档最多复用 4 个。
 * 全池最多保留 4 MiB，按最近使用次序逐个淘汰；超大数组不驻留。
 * 缓冲区只在一次构建内部使用、构建结束即归还，不参与任何绘制结果，
 * **对输出像素零影响**（纯内存复用）。
 */
private object MaskScratchBuffers {
    private val pool = ShadowScratchPool()
    fun acquire(length: Int): IntArray = pool.acquire(length)
    fun release(buffer: IntArray) = pool.release(buffer)
}

/** 浮点量化到 0.5px（用于把 `Float` 尺寸变成可稳定比较的 `Int` 缓存键）。 */
private fun Float.halfPx(): Int = (this * 2f).roundToInt()

/**
 * 已解析、可直接绘制的模糊阴影：`ImageBitmap` 与其源/目标矩形都已算好。
 *
 * 关键点：`asImageBitmap()` / `IntSize` / `IntOffset` 等**全部在解析期算好**，
 * 绘制期只剩一次 `drawImage`，**零分配、无路径运算**。
 */
internal class ResolvedBlurredShadow(
    val image: ImageBitmap,
    val srcSize: IntSize,
    val dstOffset: IntOffset,
    val dstSize: IntSize,
)

/**
 * 把 [path]（本地坐标）用 `color·alpha` 着色、按 [sigmaPx]（高斯 σ，像素）模糊，
 * 平移 ([dx],[dy]) 后解析成一张可复用的 [ResolvedBlurredShadow]。
 *
 * ⚠️ 本函数是**重活**（可能 `computeBounds` / 建位图 / 三次盒式模糊）——
 * **必须在 `drawWithCache` 的组合期调用，绝不要在每帧的 draw 里调用**。
 *
 * @param bounds 路径外接矩形（调用方预算好，避免每帧 `computeBounds`）
 * @param strokeWidthPx 描边宽（Fill 传 0）
 * @return null 表示无需绘制（alpha≈0 / σ 非法 / 路径空）
 */
internal fun resolveBlurredShadow(
    path: Path,
    bounds: RectF,
    signature: String,
    color: Color,
    sigmaPx: Float,
    dx: Float,
    dy: Float,
    strokeWidthPx: Float,
    style: DrawStyle,
    alpha: Float = 1f,
    useCache: Boolean = true,
    bucket: ShadowCacheBucket = ShadowCacheBucket.STATIC,
): ResolvedBlurredShadow? {
    val effectiveAlpha = (color.alpha * alpha).coerceIn(0f, 1f)
    if (effectiveAlpha <= 0.002f) return null
    if (sigmaPx.isNaN() || sigmaPx < 0f) return null
    if (bounds.isEmpty) return null

    // 量化到 0.5px，让缓存键稳定（动画里 σ 连续变化时也只有少量档位）
    val sigmaQ = (sigmaPx * 2f).roundToInt() / 2f

    // 位图需覆盖「模糊可见范围（≈3σ）」+ 半个描边
    val pad = sigmaQ * 3f + strokeWidthPx / 2f + 1.5f
    val left = bounds.left - pad
    val top = bounds.top - pad
    val right = bounds.right + pad
    val bottom = bounds.bottom + pad

    val scale = pickMaskScale(sigmaQ, bucket)
    val sigmaScaled = sigmaQ * scale
    val bakeColorArgb = color.copy(alpha = effectiveAlpha).toArgb()

    // 掩码构建耗时只在采集开关打开时才量（nanoTime 本身也要花时间）
    val mask: Bitmap = if (useCache) {
        // 只有真正要写缓存时才构造键（逐帧动画且 useCache=false 的路径省掉一次分配）
        val key = MaskKey(
            shapeSig = signature,
            sigmaQ = sigmaQ.halfPx(),
            scaleQ = (scale * 1000f).roundToInt(),
            strokeQ = (strokeWidthPx * scale).halfPx(),
            colorArgb = bakeColorArgb,
        )
        val lru = ShadowMaskCache.lruFor(bucket)
        val cached = lru.get(key)
        if (cached != null) {
            ShadowMaskCache.recordHit(bucket)
            cached
        } else {
            ShadowMaskCache.recordMiss(bucket)
            val t0 = if (SHADOW_PERF_LOG) System.nanoTime() else 0L
            buildMaskBitmap(path, left, top, right, bottom, scale, sigmaScaled, strokeWidthPx, style, bakeColorArgb)
                .also {
                    if (SHADOW_PERF_LOG) ShadowMaskCache.recordBuild(System.nanoTime() - t0)
                    lru.put(key, it)
                }
        }
    } else {
        val t0 = if (SHADOW_PERF_LOG) System.nanoTime() else 0L
        buildMaskBitmap(path, left, top, right, bottom, scale, sigmaScaled, strokeWidthPx, style, bakeColorArgb)
            .also {
                if (SHADOW_PERF_LOG) ShadowMaskCache.recordBuild(System.nanoTime() - t0)
            }
    }

    if (SHADOW_PERF_LOG) {
        val n = ShadowMaskCache.hits + ShadowMaskCache.misses
        if (n > 0L && n % SHADOW_PERF_LOG_EVERY == 0L) {
            Log.i("ShadowPerf", ShadowMaskCache.statsText())
        }
    }

    val dstWidth = (mask.width / scale).roundToInt().coerceAtLeast(1)
    val dstHeight = (mask.height / scale).roundToInt().coerceAtLeast(1)
    return ResolvedBlurredShadow(
        image = mask.asImageBitmap(),
        srcSize = IntSize(mask.width, mask.height),
        dstOffset = IntOffset((left + dx).roundToInt(), (top + dy).roundToInt()),
        dstSize = IntSize(dstWidth, dstHeight),
    )
}

/** 绘制期唯一要做的事：把已解析的阴影贴上去（零分配、无路径运算）。 */
internal fun DrawScope.drawResolvedBlurredShadow(s: ResolvedBlurredShadow) {
    drawImage(
        image = s.image,
        srcOffset = IntOffset.Zero,
        srcSize = s.srcSize,
        dstOffset = s.dstOffset,
        dstSize = s.dstSize,
        alpha = 1f,
        style = Fill,
        colorFilter = null,
        filterQuality = FilterQuality.Low,
    )
}

/**
 * 便捷版：自行 `computeBounds` 后解析并绘制。
 *
 * ⚠️ **仅用于每帧形状都在变的场景**（如巡游光尾，`useCache = false`）。
 * 常规阴影请在 `drawWithCache` 里用 [resolveBlurredShadow] + [drawResolvedBlurredShadow]，
 * 否则会退化成「每帧算路径 + 每帧查表」。
 */
internal fun DrawScope.drawDeterministicBlurredPath(
    path: Path,
    signature: String,
    color: Color,
    sigmaPx: Float,
    dx: Float = 0f,
    dy: Float = 0f,
    style: DrawStyle = Fill,
    alpha: Float = 1f,
    useCache: Boolean = true,
    bucket: ShadowCacheBucket = ShadowCacheBucket.STATIC,
) {
    val bounds = RectF()
    @Suppress("DEPRECATION")
    path.computeBounds(bounds, true)
    val resolved = resolveBlurredShadow(
        path = path,
        bounds = bounds,
        signature = signature,
        color = color,
        sigmaPx = sigmaPx,
        dx = dx,
        dy = dy,
        strokeWidthPx = (style as? Stroke)?.width ?: 0f,
        style = style,
        alpha = alpha,
        useCache = useCache,
        bucket = bucket,
    ) ?: return
    drawResolvedBlurredShadow(resolved)
}

/**
 * 圆角矩形辉光（[shadowGlow] 用）：语义等价于「用 [sigmaPx] 模糊一个
 * 圆角矩形」，但走 CPU 确定模糊，不再有硬边。
 */
internal fun DrawScope.drawDeterministicGlowRoundRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    cornerRadiusPx: Float,
    color: Color,
    sigmaPx: Float,
    dx: Float = 0f,
    dy: Float = 0f,
    alpha: Float = 1f,
    bucket: ShadowCacheBucket = ShadowCacheBucket.STATIC,
) {
    if (right <= left || bottom <= top) return
    val radius = cornerRadiusPx.coerceIn(0f, (right - left) / 2f)
    val path = Path().apply {
        addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)
    }
    val sig = "RR|${(right - left).halfPx()}|${(bottom - top).halfPx()}|${radius.halfPx()}"
    drawDeterministicBlurredPath(path, sig, color, sigmaPx, dx, dy, Fill, alpha, bucket = bucket)
}

/**
 * 依据 σ 与缓存桶选降采样比：σ 越大位图越小、放大倍率越高（模糊本身会抹平像素块）。
 *
 * ⚠️ **0.125 这一档只对 ANIMATED 桶开放，STATIC 桶最高只到 0.25。**
 *
 * 依据（QA 实测口径，2026-09-23）：
 *  · 静态侧 `consistentShadow` 共 **39 处调用点**，最大 elevation **32.dp**
 *    → σ = 32 × density × 0.22；即便 density = 3.5 也只有 24.6px < 32。
 *    即静态侧**永远落不到 0.125 档**，逐像素与改动前完全一致。
 *  · 动态侧只有 `shadowGlow`（AppButton 极致档），blurRadius 上限 **48.dp**
 *    （+14.dp 呼吸 → 62.dp），σ = 0.577 × 62 × 2.625 ≈ 94px → 会落到 0.125 档。
 *
 * 为什么 0.125 在视觉上等价：掩码被模糊后已不含细于 ~3σ_mask 的结构，
 * 双线性放大对平滑函数的重建误差约 (h²/8)·f''，其中 f''≈0.85·0.24/σ_mask²。
 * σ_mask = 76×0.125 ≈ 9.5 → 误差 ≈ 2.8e-4 ≈ 0.07/255，远低于 1 个色阶。
 */
private fun pickMaskScale(sigmaPx: Float, bucket: ShadowCacheBucket): Float = when {
    sigmaPx <= 2.5f -> 1f
    sigmaPx <= 8f -> 0.5f
    sigmaPx <= 32f -> 0.25f
    // 只有逐帧动画辉光允许再降一档；静态阴影一律停在 0.25，保证像素不变
    bucket == ShadowCacheBucket.ANIMATED -> 0.125f
    else -> 0.25f
}

/**
 * 构建「模糊后的阴影掩码」位图：
 * 路径填 [colorArgb] → 提取 alpha 通道 → 三次盒式模糊 → 按阴影 RGB 重建像素。
 *
 * 只模糊 alpha 通道、RGB 取常量，可精确表达「单色形状的模糊」，
 * 且避开 Android 位图「直通/预乘」往返换算带来的双重乘 alpha 问题。
 */
private fun buildMaskBitmap(
    path: Path,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    scale: Float,
    sigmaScaled: Float,
    strokeWidthPx: Float,
    style: DrawStyle,
    colorArgb: Int,
): Bitmap {
    val bw = max(1, ceil((right - left) * scale).toInt()).coerceAtMost(SHADOW_MASK_MAX_SIDE)
    val bh = max(1, ceil((bottom - top) * scale).toInt()).coerceAtMost(SHADOW_MASK_MAX_SIDE)

    val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.scale(scale, scale)
    canvas.translate(-left, -top)

    val paint = AndroidPaint().apply {
        isAntiAlias = true
        isDither = true
        color = colorArgb
        this.style = if (style is Stroke) AndroidPaint.Style.STROKE else AndroidPaint.Style.FILL
        if (style is Stroke) {
            this.strokeWidth = style.width
            this.strokeCap = AndroidPaint.Cap.ROUND
            this.strokeJoin = AndroidPaint.Join.ROUND
        }
    }
    canvas.drawPath(path, paint)

    val boxRadius = if (sigmaScaled >= 0.3f) sigmaToBoxRadius(sigmaScaled) else 0
    if (boxRadius >= 1) {
        val rgb = colorArgb and 0x00FFFFFF
        val n = bw * bh
        // 三块临时缓冲都走复用池：逐帧动画场景下省掉每秒上百次的大块分配。
        val pixels = MaskScratchBuffers.acquire(n)
        try {
            bitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh)
            val alpha = MaskScratchBuffers.acquire(n)
            try {
                for (i in 0 until n) alpha[i] = (pixels[i] ushr 24) and 0xFF
                boxBlurThreePass(alpha, bw, bh, boxRadius, n)
                for (i in 0 until n) pixels[i] = rgb or (alpha[i] shl 24)
            } finally {
                MaskScratchBuffers.release(alpha)
            }
            bitmap.setPixels(pixels, 0, bw, 0, 0, bw, bh)
        } finally {
            MaskScratchBuffers.release(pixels)
        }
    }
    return bitmap
}

/**
 * 高斯 σ → 三次盒式模糊的单次半径 r。
 *
 * 一次盒式模糊（窗口 2r+1）的方差 = ((2r+1)² − 1)/12 = (r²+r)/3；
 * 三次串联方差 = r²+r；令其等于 σ² 解得 r = (−1 + √(1+4σ²)) / 2。
 */
private fun sigmaToBoxRadius(sigma: Float): Int {
    val r = (-1f + sqrt(1f + 4f * sigma * sigma)) / 2f
    return r.roundToInt().coerceIn(1, 256)
}

/**
 * 对单通道（alpha）做三次盒式模糊 ≈ 高斯（水平/垂直可分离，各 3 遍）。
 *
 * @param n 参与运算的元素个数（= w * h）。缓冲区可能来自复用池而更长，
 *          因此必须显式传 `n`，不能取 `src.size`。
 */
private fun boxBlurThreePass(alpha: IntArray, w: Int, h: Int, radius: Int, n: Int) {
    val tmp = MaskScratchBuffers.acquire(n)
    try {
        repeat(3) {
            boxBlurHorizontal(alpha, tmp, w, h, radius)
            boxBlurVertical(tmp, alpha, w, h, radius)
        }
    } finally {
        MaskScratchBuffers.release(tmp)
    }
}

/** 水平方向盒式模糊（滑动窗口，边缘 clamp 复制，避免边框变暗）。 */
private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    val div = 2 * r + 1
    val inv = 1f / div
    val last = w - 1
    for (y in 0 until h) {
        val base = y * w
        // 右端：x + r + 1 ≥ w 后统一取 last；左端：x - r < 0 期间取 0。
        // 用「越界前 / 全越界后」两段循环替代逐像素 coerceIn，数学结果完全一致。
        var sum = 0
        for (i in -r..r) {
            sum += src[base + if (i < 0) 0 else if (i > last) last else i]
        }
        for (x in 0 until w) {
            dst[base + x] = ((sum * inv) + 0.5f).toInt()
            val addIdx = x + r + 1
            val subIdx = x - r
            sum += (if (addIdx > last) src[base + last] else src[base + addIdx]) -
                (if (subIdx < 0) src[base] else src[base + subIdx])
        }
    }
}

/** Same column sums and rounding, traversed by contiguous rows instead of strided columns. */
internal fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    val div = 2 * r + 1
    val inv = 1f / div
    val last = h - 1
    val sums = MaskScratchBuffers.acquire(w)
    try {
        for (x in 0 until w) sums[x] = src[x] * (r + 1)
        for (row in 1..minOf(r, last)) {
            val base = row * w
            for (x in 0 until w) sums[x] += src[base + x]
        }
        if (r > last) {
            val base = last * w
            for (x in 0 until w) sums[x] += src[base + x] * (r - last)
        }
        var base = 0
        for (y in 0 until h) {
            val addBase = minOf(y + r + 1, last) * w
            val subBase = maxOf(y - r, 0) * w
            for (x in 0 until w) {
                dst[base + x] = ((sums[x] * inv) + 0.5f).toInt()
                sums[x] += src[addBase + x] - src[subBase + x]
            }
            base += w
        }
    } finally {
        MaskScratchBuffers.release(sums)
    }
}
