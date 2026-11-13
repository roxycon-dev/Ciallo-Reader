package com.example.ui.wenku

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * **一本 3D 书的完整绘制层（纯 `android.graphics`，不依赖 Compose 组合）。**
 *
 * 之所以把它从 [ArchiveShelf] 里抽出来：打开 / 归位动画如果由"书架画布转正"
 * + "覆盖层把贴图放大"两个渲染器接力，就一定有交接断点（旋转结束时缩放的
 * 速度从 0 重新起步，用户看到的就是"两段动画"）。抽成独立渲染器之后，
 * 书架和根覆盖层可以用**同一个** [ShelfVolumeArtist] + **同一个** [ShelfOpenTimeline]
 * 画同一本书，交接那一帧的参数完全一致，画面自然连成一段。
 *
 * 坐标：全部是 px。`ox/oy` 是书"左上后角"的画布坐标，[ShelfVolumePose.pivotX/Y]
 * 是封面面中心（缩放锚点）。
 */

/** 一本书在某一帧的姿态。所有字段都是时间的连续函数，没有任何"切换点"。 */
internal data class ShelfVolumePose(
    val ox: Float,
    val oy: Float,
    /** 封面面的水平投影系数：`.66` = 斜看，`1` = 正对读者。 */
    val depthX: Float,
    /** 封面面的垂直投影系数：`-.16` = 俯视书口可见，`0` = 正对读者。 */
    val depthY: Float,
    /** 书页顶面斜切。 */
    val skew: Float,
    /** 书脊厚度系数：`1` = 满厚度，`0` = 收成一页纸。 */
    val thicknessScale: Float,
    /** 封面工艺（覆膜/布纹/装订槽/倒角/编号）强度；收到 0 就只剩原始贴图。 */
    val detail: Float,
    val alpha: Float,
    val scale: Float
)

/** 书架静止姿态：斜放、满厚度、满工艺。时间轴 `p = 0` 必须与它逐参数一致。 */
internal val RESTING_VOLUME_POSE = ShelfVolumePose(
    ox = 0f,
    oy = 0f,
    depthX = .66f,
    depthY = -.16f,
    skew = .10f,
    thicknessScale = 1f,
    detail = 1f,
    alpha = 1f,
    scale = 1f
)

/** 绘制一本书需要的全部素材（px 已定，跨画布可直接复用）。 */
internal class ShelfVolumeArt(
    val id: Int,
    /** `depthX = 1` 时封面面满版的宽度。 */
    val coverWidth: Float,
    /** 本书高度（含 ±3.5% 的高度差）。 */
    val body: Float,
    /** 满厚度。 */
    val thickness: Float,
    val topInset: Float,
    val tint: Int,
    val material: Int,
    val cover: ImageBitmap?,
    val number: String,
    val fileCode: String,
    val runs: List<SpineRun>,
    val truncated: Boolean,
    val palette: ShelfPalette,
    val unit: Float
)

/** 书脊竖排的一段文字（拉丁连排、CJK 单字）。 */
internal class SpineRun(val text: String, val latin: Boolean)

/**
 * 打开 / 归位的时间轴：**一个进度，一条路径。**
 *
 * `p` 从 0 到 1 单调推进，所有自由度都是 `p` 的连续函数：
 * - 位置与缩放共用一条钟形曲线（两端速度为 0），所以中途没有速度断点；
 * - 转正（`depthX .66→1`、`depthY -.16→0`、`skew .10→0`、书脊收薄）在 `p≈.02~.64` 完成，
 *   与缩放**重叠**推进，不是"先转正再放大"；
 * - `p = 0` 时返回值与书架静止帧**逐参数相同**，封面工艺也是满强度。
 *
 * 因此"书架最后一帧"和"覆盖层第一帧"不是"插值调得像"，而是同一个函数在同一个 p 上的求值。
 */
internal object ShelfOpenTimeline {
    /** 打开全程：抬起 → 转正 → 铺满。 */
    const val OPEN_MS = 880

    /** 归位全程：比打开略快一点（回程不需要"被拿起来"的仪式感）。 */
    const val CLOSE_MS = 720

    /** 归位时封面盖住阅读器所需的时间：盖满之后再切路由，用户看不到页面切换。 */
    const val CLOSE_COVER_IN_MS = 120

    /** 几何（位置 + 缩放）走完的进度：之后只剩"封面铺满 → 淡出"。 */
    const val GEOMETRY_END = .78f

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun poseAt(
        p: Float,
        art: ShelfVolumeArt,
        restOx: Float,
        restOy: Float,
        targetCenterX: Float,
        targetCenterY: Float,
        targetScale: Float,
        /** 非 null 时直接用它当透明度（归位：封面从阅读器这一侧淡入）。 */
        alphaOverride: Float? = null
    ): ShelfVolumePose {
        val progress = p.coerceIn(0f, 1f)
        // 位置/缩放：一条钟形曲线，两端导数为 0，中途不会"重新起步"。
        // 几何在 GEOMETRY_END 处走到终点（封面正好铺满整屏），之后的进度只做淡出。
        val moveT = smooth(progress / GEOMETRY_END)
        // 转正：与缩放重叠，在 64% 处收干净。
        val flatT = smooth((progress - .02f) / .62f)
        val depthX = .66f + (1f - .66f) * flatT
        val depthY = -.16f * (1f - flatT)
        val skew = .10f * (1f - flatT)
        val thicknessScale = 1f - flatT
        // 工艺收得比几何更晚一点，但铺满前必须收干净：
        // 最后一帧只剩原始贴图，和阅读器的第一眼不会打架。
        val detail = 1f - smooth((progress - .30f) / .44f)
        val alpha = alphaOverride
            ?: (1f - smooth((progress - .80f) / .20f))

        val thickness = art.thickness * thicknessScale
        val dx = art.coverWidth * depthX
        val dy = art.coverWidth * depthY
        val restPivotX = restOx + thickness + dx * .5f
        val restPivotY = restOy + art.topInset + dy * .5f + art.body * .5f
        val pivotX = restPivotX + (targetCenterX - restPivotX) * moveT
        val pivotY = restPivotY + (targetCenterY - restPivotY) * moveT
        val scale = 1f + (targetScale - 1f) * moveT
        // ox/oy 由"封面面中心必须落在 pivot 上"反解出来：p = 0 时恰好等于书架上的静止位置。
        val ox = pivotX - (thickness + dx * .5f)
        val oy = pivotY - (art.topInset + dy * .5f + art.body * .5f)
        return ShelfVolumePose(
            ox = ox,
            oy = oy,
            depthX = depthX,
            depthY = depthY,
            skew = skew,
            thicknessScale = thicknessScale,
            detail = detail,
            alpha = alpha.coerceIn(0f, 1f),
            scale = scale
        )
    }

    /** 铺满 `viewport` 需要的缩放：用 `max`（裁切）而不是 `min`（留边），屏幕上不会露出底色。 */
    fun fillScale(art: ShelfVolumeArt, viewportWidth: Float, viewportHeight: Float): Float {
        if (art.coverWidth <= 0f || art.body <= 0f) return 1f
        return max(viewportWidth / art.coverWidth, viewportHeight / art.body)
    }
}

/**
 * 把 [ShelfVolumeArt] + [ShelfVolumePose] 画到任意 Canvas 上。
 * 书架每帧用它画静止的书，覆盖层用同一个实例画飞行中的那一本。
 */
internal class ShelfVolumeArtist {
    private val paints = HashMap<Int, ShelfVolumePaint>(24)
    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val fontMetrics = Paint.FontMetrics()
    private val source = Rect()
    private val destination = Rect()
    private val widthCache = HashMap<String, Float>(32)

    private val topPath = Path()
    private val pagePath = Path()
    private val spinePath = Path()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val codePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mono: Typeface = Typeface.create("monospace", Typeface.NORMAL)
    private val serif: Typeface = Typeface.create("serif", Typeface.BOLD)

    private val clothPaint: Paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(clothTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
    private val clothMatrix = Matrix()

    fun clearCache() {
        paints.clear()
        widthCache.clear()
    }

    fun draw(canvas: android.graphics.Canvas, art: ShelfVolumeArt, pose: ShelfVolumePose) {
        if (pose.alpha <= .004f) return
        val pivotX = restPivotX(art, pose)
        val pivotY = restPivotY(art, pose)
        val save = canvas.save()
        canvas.translate(pivotX, pivotY)
        if (abs(pose.scale - 1f) > 1e-4f) canvas.scale(pose.scale, pose.scale)
        canvas.translate(-pivotX, -pivotY)
        drawVolume(canvas, art, pose)
        canvas.restoreToCount(save)
    }

    /**
     * `draw` 里的缩放锚点是**本帧**的封面面中心；因为 `pose.ox/oy` 是反解出来的，
     * 这里必须用同一个公式重新算一次，不能直接用 `pose.pivotX/Y`（那是目标位置）。
     */
    private fun restPivotX(art: ShelfVolumeArt, pose: ShelfVolumePose): Float {
        val thickness = art.thickness * pose.thicknessScale
        return pose.ox + thickness + art.coverWidth * pose.depthX * .5f
    }

    private fun restPivotY(art: ShelfVolumeArt, pose: ShelfVolumePose): Float {
        val dy = art.coverWidth * pose.depthY
        return pose.oy + art.topInset + dy * .5f + art.body * .5f
    }

    private fun paintFor(art: ShelfVolumeArt): ShelfVolumePaint {
        paints[art.id]?.let { if (it.tint == art.tint) return it }
        val palette = art.palette
        val thickness = art.thickness
        val spineBase = when (art.material) {
            MATERIAL_CLOTH -> shade(art.tint, -.06f)
            MATERIAL_MATTE -> shade(art.tint, .04f)
            else -> shade(art.tint, -.14f)
        }
        val specular = when (art.material) {
            MATERIAL_GLOSS -> .34f
            MATERIAL_CLOTH -> .12f
            else -> .20f
        }
        val spineFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, thickness, 0f,
                intArrayOf(
                    shade(spineBase, -.16f),
                    shade(spineBase, -.04f),
                    shade(spineBase, specular * .7f),
                    shade(spineBase, .02f),
                    shade(spineBase, -.10f),
                    shade(spineBase, -.24f)
                ),
                floatArrayOf(0f, .16f, .34f, .58f, .82f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val spineShade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, thickness, 0f,
                intArrayOf(
                    android.graphics.Color.argb(108, 0, 0, 0),
                    android.graphics.Color.argb(6, 0, 0, 0),
                    android.graphics.Color.argb(44, 255, 255, 255),
                    android.graphics.Color.argb(16, 0, 0, 0),
                    android.graphics.Color.argb(66, 0, 0, 0),
                    android.graphics.Color.argb(150, 0, 0, 0)
                ),
                floatArrayOf(0f, .18f, .36f, .60f, .86f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val boardTop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (art.material == MATERIAL_GLOSS) shade(spineBase, .10f) else shade(spineBase, -.02f)
        }
        val paperFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.paper }
        val pageLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.pageLine
            strokeWidth = max(.55f * art.unit, thickness * .025f)
            style = Paint.Style.STROKE
        }
        val paint = ShelfVolumePaint(
            thickness = thickness,
            height = art.body,
            topInset = art.topInset,
            material = art.material,
            tint = art.tint,
            spineFill = spineFill,
            spineShade = spineShade,
            boardTop = boardTop,
            paperFill = paperFill,
            pageLine = pageLine
        )
        paints[art.id] = paint
        return paint
    }

    /** 颜色 + 透明度一次算好：`base` 自带 alpha 时与本帧的入场 alpha 相乘。 */
    private fun Paint.tone(base: Int, alpha: Float, ink: Float) {
        val combined = (alpha.coerceIn(0f, 1f) * ink.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        color = (base and 0x00ffffff) or (combined shl 24)
    }

    private fun drawVolume(canvas: android.graphics.Canvas, art: ShelfVolumeArt, pose: ShelfVolumePose) {
        val paint = paintFor(art)
        val palette = art.palette
        val unit = art.unit
        val cover = art.coverWidth
        val body = paint.height
        val t = (paint.thickness * pose.thicknessScale).coerceAtLeast(2f)
        if (cover < 4f || body < 4f) return
        val ink = pose.alpha.coerceIn(0f, 1f)
        val detail = pose.detail.coerceIn(0f, 1f)
        val alpha = (255f * ink).roundToInt().coerceIn(0, 255)
        val dx = cover * pose.depthX
        val dy = cover * pose.depthY
        val skew = pose.skew
        val top = pose.oy + paint.topInset
        val outer = canvas.save()
        canvas.translate(pose.ox, top)

        // ---- 1) 顶部切面：纸页块 + 封面"包边" ----
        val inset = min(t * .30f, palette.pageInset)
        val fore = 1f - (palette.overhang / cover).coerceIn(0f, .12f)
        val pageTop = inset * skew
        topPath.rewind()
        topPath.moveTo(0f, 0f)
        topPath.lineTo(t, t * skew)
        topPath.lineTo(t + dx, t * skew + dy)
        topPath.lineTo(dx, dy)
        topPath.close()
        paint.boardTop.alpha = alpha
        canvas.drawPath(topPath, paint.boardTop)

        pagePath.rewind()
        pagePath.moveTo(inset, pageTop)
        pagePath.lineTo(t - inset, (t - inset) * skew)
        pagePath.lineTo(t - inset + dx * fore, (t - inset) * skew + dy * fore)
        pagePath.lineTo(inset + dx * fore, pageTop + dy * fore)
        pagePath.close()
        paint.paperFill.alpha = alpha
        canvas.drawPath(pagePath, paint.paperFill)

        if (t - inset * 2f > unit * 2.4f) {
            // 纸页层：沿深度方向排的细线，像一叠纸的切口
            val lines = 6
            val x0 = inset + t * .06f
            val x1 = t - inset - t * .05f
            paint.pageLine.alpha = (86f * ink).roundToInt().coerceIn(0, 255)
            for (i in 1 until lines) {
                val p = i / lines.toFloat()
                val bx = x0 + (x1 - x0) * p
                val by = bx * skew
                canvas.drawLine(bx, by, bx + dx * fore, by + dy * fore, paint.pageLine)
            }
        }

        // 纸页靠书脊一侧的压影：纸是有厚度的，不是一块平色
        val shadeSave = canvas.save()
        canvas.clipPath(pagePath)
        fill.tone(palette.paperDeep, .45f, ink)
        canvas.drawRect(inset, pageTop - unit, inset + dx * .20f, (t - inset) * skew + abs(dy) + unit, fill)
        fill.tone(palette.highlight, .18f, ink)
        canvas.drawRect(inset + dx * fore * .82f, pageTop - unit, inset + dx * fore, (t - inset) * skew + abs(dy) + unit, fill)
        canvas.restoreToCount(shadeSave)
        // 前口（靠近读者那一侧）板边高光
        hairline.strokeWidth = max(.6f * unit, t * .04f)
        hairline.tone(palette.highlight, .60f, ink)
        canvas.drawLine(t, t * skew, t + dx, t * skew + dy, hairline)

        // ---- 2) 书脊：圆脊 + 封面图连续包裹 + 装订槽 ----
        values[0] = 1f; values[1] = 0f; values[2] = 0f
        values[3] = skew; values[4] = 1f; values[5] = 0f
        values[6] = 0f; values[7] = 0f; values[8] = 1f
        matrix.setValues(values)
        val spineSave = canvas.save()
        canvas.concat(matrix)
        spinePath.rewind()
        val radius = min(t * .46f, palette.spineRadius)
        spinePath.addRoundRect(0f, 0f, t, body, radius, radius, Path.Direction.CW)
        val spineClip = canvas.save()
        canvas.clipPath(spinePath)
        paint.spineFill.alpha = alpha
        canvas.drawRect(0f, 0f, t, body, paint.spineFill)
        // 封面图沿书脊连续包裹：取封面最左一条竖带压到书脊上，再由柱面明暗统一收光。
        art.cover?.let { bitmap ->
            if (bitmap.width > 8 && bitmap.height > 8) {
                // 从 6% 处取样，避开封面最左边那道压暗/边框，书脊才不会被染黑。
                val wrapStart = (bitmap.width * .06f).toInt()
                source.set(wrapStart, 0, (bitmap.width * .22f).toInt().coerceAtLeast(2), bitmap.height)
                destination.set(0, 0, t.roundToInt().coerceAtLeast(1), body.roundToInt())
                bitmapPaint.alpha = alpha
                canvas.drawBitmap(bitmap.asAndroidBitmap(), source, destination, bitmapPaint)
            }
        }
        paint.spineShade.alpha = (235f * ink).roundToInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, t, body, paint.spineShade)
        drawSpineDetails(canvas, art, t, body, paint, ink, alpha)
        canvas.restoreToCount(spineClip)
        // 装订槽：书脊与封面之间一条压暗的缝
        hairline.strokeWidth = max(.8f * unit, 1f)
        hairline.tone(0x000000, .55f, ink)
        canvas.drawLine(t - .55f * unit, 0f, t - .55f * unit, body, hairline)
        canvas.restoreToCount(spineSave)

        // ---- 3) 封面：满版印刷 + 材质 + 装订槽 + 倒角 ----
        values[0] = pose.depthX; values[1] = 0f; values[2] = t
        values[3] = pose.depthY; values[4] = 1f; values[5] = t * skew
        values[6] = 0f; values[7] = 0f; values[8] = 1f
        matrix.setValues(values)
        val coverSave = canvas.save()
        canvas.concat(matrix)
        // 没有贴图时封面面填**纸色**而不是书脊色：纸色永远不会像"黑封面"，
        // 而且贴图下一轮就补上了（见 ArchiveShelf.shelfWarmWindow）。
        fill.tone(if (art.cover == null) palette.paper else paint.boardTop.color, 1f, ink)
        canvas.drawRect(0f, 0f, cover, body, fill)
        art.cover?.let { bitmap ->
            source.set(0, 0, bitmap.width, bitmap.height)
            destination.set(0, 0, cover.roundToInt(), body.roundToInt())
            bitmapPaint.alpha = alpha
            canvas.drawBitmap(bitmap.asAndroidBitmap(), source, destination, bitmapPaint)
        }
        when (paint.material) {
            MATERIAL_GLOSS -> {
                // 覆膜亮面：一条斜向的柔和反光（渐变而不是硬边条），角度跟着书架光线
                val sheenSave = canvas.save()
                canvas.rotate(-14f)
                fill.shader = LinearGradient(
                    0f, 0f, cover * .85f, 0f,
                    intArrayOf(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.argb(44, 255, 255, 255),
                        android.graphics.Color.argb(64, 255, 255, 255),
                        android.graphics.Color.argb(20, 255, 255, 255),
                        android.graphics.Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, .26f, .38f, .46f, .70f),
                    Shader.TileMode.CLAMP
                )
                fill.alpha = (255f * .55f * ink * detail).roundToInt().coerceIn(0, 255)
                canvas.drawRect(-cover * .2f, -body, cover * 1.2f, body * 2f, fill)
                canvas.restoreToCount(sheenSave)
                fill.shader = null
            }
            MATERIAL_CLOTH -> {
                clothMatrix.setScale(unit * .55f, unit * .55f)
                clothPaint.shader?.setLocalMatrix(clothMatrix)
                clothPaint.alpha = (58f * ink * detail).roundToInt().coerceIn(0, 255)
                canvas.drawRect(0f, 0f, cover, body, clothPaint)
            }
            else -> Unit
        }
        // 上下收影：让封面不是平贴的色块（很轻，压过头就成脏）
        fill.tone(0x000000, .045f, ink * detail)
        canvas.drawRect(0f, 0f, cover, body * .18f, fill)
        fill.tone(0x000000, .035f, ink * detail)
        canvas.drawRect(0f, body * .92f, cover, body, fill)

        // 装订槽：贴着书脊的凹槽 + 折痕高光（要克制，别变成两条白杠）
        fill.tone(0x000000, .26f, ink * detail)
        canvas.drawRect(0f, 0f, palette.hingeWidth, body, fill)
        fill.tone(0xFFFFFF, .10f, ink * detail)
        canvas.drawRect(palette.hingeWidth, 0f, palette.hingeWidth + unit * .7f, body, fill)
        // 封面板倒角：上沿提亮、下沿压暗，读起来像一块有厚度的板
        hairline.strokeWidth = max(.55f * unit, .55f)
        hairline.tone(palette.highlight, .22f, ink * detail)
        canvas.drawLine(0f, 0f, cover, 0f, hairline)
        canvas.drawLine(0f, 0f, 0f, body, hairline)
        hairline.tone(0x000000, .30f, ink * detail)
        canvas.drawLine(0f, body - .5f, cover, body - .5f, hairline)
        canvas.drawLine(cover - .5f, 0f, cover - .5f, body, hairline)
        // 馆藏编号：极淡，像盖在封面下角的一行编号
        codePaint.typeface = mono
        codePaint.letterSpacing = palette.codeSpacing
        codePaint.textSize = palette.codeSize
        codePaint.tone(palette.highlight, CODE_ALPHA, ink * detail)
        canvas.drawText(art.fileCode, palette.hingeWidth + unit * 3f, body - unit * 4.6f, codePaint)
        canvas.restoreToCount(coverSave)
        canvas.restoreToCount(outer)
    }

    /** 书脊细节：堵头布、编号、竖排书名。全部在书脊坐标系里画。 */
    private fun drawSpineDetails(
        canvas: android.graphics.Canvas,
        art: ShelfVolumeArt,
        spine: Float,
        body: Float,
        paint: ShelfVolumePaint,
        ink: Float,
        alpha: Int
    ) {
        val palette = art.palette
        val unit = palette.densityUnit
        // 堵头布：上下各一条细带，颜色取封面主色的提亮版
        fill.tone(shade(paint.tint, if (palette.isDark) .34f else .20f), .9f, ink)
        canvas.drawRect(0f, unit * 1.2f, spine, unit * 3.2f, fill)
        canvas.drawRect(0f, body - unit * 3.4f, spine, body - unit * 1.4f, fill)
        hairline.strokeWidth = .5f * unit
        hairline.tone(0x000000, .30f, ink)
        canvas.drawLine(0f, unit * 3.2f, spine, unit * 3.2f, hairline)
        canvas.drawLine(0f, body - unit * 3.4f, spine, body - unit * 3.4f, hairline)

        val labelInk = inkOn(paint.tint)
        // 编号
        codePaint.typeface = mono
        codePaint.letterSpacing = palette.codeSpacing
        codePaint.textSize = palette.codeSize
        codePaint.tone(labelInk, .9f, ink)
        val numberWidth = codePaint.measureText(art.number)
        if (numberWidth < spine * .92f) {
            canvas.drawText(
                art.number,
                spine * .5f - numberWidth / 2f,
                baseline(codePaint, unit * 6.2f, palette.codeLine),
                codePaint
            )
        }
    }

    private fun baseline(paint: Paint, top: Float, lineHeight: Float): Float {
        paint.getFontMetrics(fontMetrics)
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        return top + (lineHeight - textHeight) / 2f - fontMetrics.ascent
    }

}

/** 封面材质：布面 / 哑光纸 / 亮面覆膜。由书 id 稳定决定，不随主题变化。 */
internal const val MATERIAL_CLOTH = 0
internal const val MATERIAL_MATTE = 1
internal const val MATERIAL_GLOSS = 2

internal const val CLOTH_TILE_SEED = 11L
internal const val CODE_ALPHA = .62f

/** 布面纸的细颗粒：一次生成，所有布面书共用。 */
private fun clothTile(): Bitmap {
    val size = 48
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val random = java.util.Random(CLOTH_TILE_SEED)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            // 横竖交织：奇偶行/列给不同亮度，读起来是织纹而不是噪点
            val weave = if ((x + y) % 2 == 0) 10 else -8
            val value = (118 + random.nextInt(46) - 23 + weave).coerceIn(0, 255)
            val alpha = 16 + random.nextInt(22)
            pixels[y * size + x] = android.graphics.Color.argb(alpha, value, value, value)
        }
    }
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap
}

/** 一本书在书架上的可复用画笔：厚度一变就重建，正常滚动零分配。 */
internal class ShelfVolumePaint(
    val thickness: Float,
    val height: Float,
    val topInset: Float,
    val material: Int,
    val tint: Int,
    val spineFill: Paint,
    val spineShade: Paint,
    val boardTop: Paint,
    val paperFill: Paint,
    val pageLine: Paint
)

/** 3D 书架用到的全部颜色与 px 尺寸：一次算好，绘制期不再查主题。 */
internal class ShelfPalette(colors: WenkuColors, density: Float) {
    val isDark: Boolean = colors.isDark
    val densityUnit: Float = density
    val floorInk: Int = (if (colors.isDark) colors.milk else Color(0xFF8A806B)).toArgb()
    val border: Int = colors.outline.toArgb()
    val ink: Int = colors.ink.toArgb()
    val secondary: Int = colors.inkSecondary.toArgb()

    /** 纸页（切口）纸色：暖白，暗色主题压成暖灰，绝不纯白。 */
    val paper: Int = (if (colors.isDark) Color(0xFF3B3A34) else Color(0xFFF4EFE1)).toArgb()
    val paperDeep: Int = (if (colors.isDark) Color(0xFF2A2925) else Color(0xFFD8CEB8)).toArgb()
    val pageLine: Int = (if (colors.isDark) Color(0xFF1C1B18) else Color(0xFFB3A88F)).toArgb()
    val highlight: Int = (if (colors.isDark) Color(0xFFF7F6F0) else Color(0xFFFFFEF8)).toArgb()

    val codeSize: Float = 7.6f * density
    val codeLine: Float = 7.6f * 1.3f * density
    val codeSpacing: Float = .9f / 7.6f
    val titleSize: Float = 9f * density
    val titleLine: Float = 9f * 1.06f * density
    val spinePadV: Float = 9f * density
    val gapAfterNumber: Float = 6f * density
    val gapBeforeDot: Float = 5f * density
    val dotRadius: Float = 2.4f * density
    val dotSize: Float = 4.8f * density
    val hairline: Float = .7f * density
    val pageInset: Float = 2.1f * density
    val overhang: Float = 1.6f * density
    val spineRadius: Float = 4.4f * density
    val hingeWidth: Float = 2.4f * density
}

/** 页数 → 厚度：一章按 9 页估；没有章节信息时按 id 稳定兜底（各本厚薄不同）。 */
internal fun volumeThicknessPx(chapters: Int, id: Int, density: Float): Float {
    val pages = if (chapters > 0) chapters * 9f else 140f + (abs(id * 37) % 240)
    val thicknessDp = (6.0f + sqrt(pages) * .40f).coerceIn(7.5f, 25f)
    return thicknessDp * density
}

/** 高度差 ±3.5%：真实书架没有两本一样高，太大又会让标题错位。 */
internal fun volumeHeightScale(id: Int): Float {
    val swing = ((abs(id * 131) % 100) / 100f) - .5f
    return 1f + swing * .07f
}

/** 材质按 id 稳定分配，不随主题/滚动变化。 */
internal fun volumeMaterial(id: Int, titleLength: Int): Int =
    when (abs(id * 17 + titleLength) % 3) {
        0 -> MATERIAL_CLOTH
        1 -> MATERIAL_MATTE
        else -> MATERIAL_GLOSS
    }
