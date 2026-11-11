package com.example.ui.wenku

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Density
import coil.compose.AsyncImagePainter
import kotlin.math.max
import kotlin.math.min

/**
 * 3D 书架的单本书封面素材层。
 *
 * 这一层的职责只有一个：**把任意来源的封面变成"能贴到实体书封面面上"的像素**。
 * 两件事必须在这里做掉，否则后面的 Canvas 只能拿到一张带留白的方图：
 *
 * 1. **裁成书封比例**（`centerCrop`）。书架几何里封面面是 `cover × body`，
 *    比例约 0.61；真实封面大多 0.68～0.72。之前是等比塞进内框，于是四周留一圈
 *    米白纸边 —— 这正是"贴了封面图片的多面体盒子"观感的来源。这里直接按目标
 *    比例中心裁切，封面满版出血，之后才谈得上实体书。
 * 2. **没有封面时生成一张可用的封面**。列表书架用的是"纸色卡片 + 书名 + 格式"，
 *    3D 书架过去是一条纯色 + 极淡标题，飞行到阅读器时更是直接掉到 `#202020` 黑块。
 *    这里用同一套语义（书名 + 作者 + 格式标签）生成一张印刷风封面，3D 书、飞行
 *    动画、阅读器落点三处共用同一张图，颜色与纸质再也不会对不上。
 *
 * 生成结果全部是**确定性**的（只吃 id/title 的 hash），同一本书每次一样；
 * 每本书只光栅化一次，交给 [ShelfCoverStore] 做 LRU。
 */

/** 生成封面用的配色：封面主色、压暗色、点缀色。刻意压低饱和度，像印刷品。 */
private fun blend(from: Int, to: Int, fraction: Float): Int {
    val f = fraction.coerceIn(0f, 1f)
    val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt()
    val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt()
    val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt()
    val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
    return Color.argb(a, r, g, b)
}

/** `fraction > 0` 提亮、`< 0` 压暗；用来做书脊的柱面明暗与封面板的倒角。 */
internal fun shade(color: Int, fraction: Float): Int =
    if (fraction >= 0f) blend(color, Color.WHITE, fraction)
    else blend(color, Color.BLACK, -fraction)

internal fun luminance(color: Int): Float =
    (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f

/** 亮底用墨色字，暗底用纸色字。 */
internal fun inkOn(color: Int): Int =
    if (luminance(color) > .58f) Color.argb(255, 32, 30, 26) else Color.argb(255, 246, 243, 236)

/**
 * 封面贴图：按目标比例中心裁切后光栅化。
 *
 * @param widthPx  目标宽（= 3D 封面面宽度）
 * @param heightPx 目标高（= 3D 封面面高度，与 [ShelfMetrics.bodyPx] 同源）
 */
internal fun rasterizeShelfCover(
    painter: Any?,
    widthPx: Int,
    heightPx: Int,
    density: Density
): ImageBitmap? {
    if (widthPx <= 0 || heightPx <= 0) return null
    val success = (painter as? AsyncImagePainter)?.state as? AsyncImagePainter.State.Success ?: return null
    val drawable = success.result.drawable ?: return null
    return runCatching {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: widthPx
        val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: heightPx
        val scale = max(widthPx.toFloat() / intrinsicWidth, heightPx.toFloat() / intrinsicHeight)
        val drawnWidth = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
        val drawnHeight = (intrinsicHeight * scale).toInt().coerceAtLeast(1)
        canvas.save()
        canvas.clipRect(0, 0, widthPx, heightPx)
        canvas.translate((widthPx - drawnWidth) * .5f, (heightPx - drawnHeight) * .5f)
        drawable.setBounds(0, 0, drawnWidth, drawnHeight)
        drawable.draw(canvas)
        canvas.restore()
        bitmap.asImageBitmap()
    }.getOrNull()
}

/**
 * 无封面书的印刷风封面。与列表书架的占位卡同一语义（书名 + 作者 + 格式），
 * 但做成"真的印在封面上的东西"，而不是一块白。
 */
internal fun generateShelfCover(
    title: String,
    author: String,
    tag: String,
    dark: Boolean,
    widthPx: Int,
    heightPx: Int,
    density: Density
): ImageBitmap {
    val width = widthPx.coerceAtLeast(32)
    val height = heightPx.coerceAtLeast(48)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val unit = (width / 118f).coerceAtLeast(1f)

    // 与列表书架的占位卡同源：纸色渐变 → 细边框 → 书本图标 → 书名 → 格式。
    // 两处长得一样，用户从 3D 打开、从列表打开看到的是同一张封面。
    val paperTop = if (dark) 0xFF2B2B29.toInt() else 0xFFF4F3EF.toInt()
    val paperBottom = if (dark) 0xFF232322.toInt() else 0xFFEAE7DE.toInt()
    val tint = if (dark) 0xFF9FD8C8.toInt() else 0xFF7FD8C8.toInt()
    val ink = if (dark) 0xFFF2F2F2.toInt() else 0xFF161613.toInt()
    val muted = if (dark) 0xFFB7B7B7.toInt() else 0xFF646B62.toInt()

    canvas.drawRect(
        0f, 0f, width.toFloat(), height.toFloat(),
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width * .45f, height.toFloat(),
                paperTop, paperBottom, Shader.TileMode.CLAMP
            )
        }
    )
    // 品牌色的极淡斜向光
    canvas.drawRect(
        0f, 0f, width.toFloat(), height.toFloat(),
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                width.toFloat(), 0f, 0f, height * .8f,
                withAlphaInt(tint, .16f), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
    )

    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, unit * .8f)
        color = withAlphaInt(ink, if (dark) .16f else .10f)
    }
    canvas.drawRect(0.5f, 0.5f, width - 0.5f, height - 0.5f, border)

    // 书本图标（列表卡顶部那颗 MenuBook 的等价物，用线条画，不依赖资源）
    val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1.4f, unit * 1.9f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = withAlphaInt(tint, .70f)
    }
    val iconW = width * .26f
    val iconH = iconW * .78f
    val iconX = (width - iconW) * .5f
    val iconY = height * .10f
    canvas.drawRoundRect(iconX, iconY, iconX + iconW, iconY + iconH, unit * 1.6f, unit * 1.6f, icon)
    canvas.drawLine(
        iconX + iconW * .5f, iconY + unit * 1.2f,
        iconX + iconW * .5f, iconY + iconH - unit * 1.2f, icon
    )

    // 书名：居中，最多 4 行，放不下缩字号
    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        typeface = Typeface.create("serif", Typeface.BOLD)
        textSize = unit * 13.5f
    }
    val titleWidth = (width * .74f).toInt().coerceAtLeast(24)
    var layout = buildShelfLayout(title, titlePaint, titleWidth)
    var attempts = 0
    while ((layout.height > height * .46f || layout.lineCount > 4) && attempts < 6) {
        titlePaint.textSize *= .88f
        layout = buildShelfLayout(title, titlePaint, titleWidth)
        attempts++
    }
    canvas.save()
    canvas.translate((width - titleWidth) * .5f, height * .30f)
    layout.draw(canvas)
    canvas.restore()

    // 底部：分隔线 + 作者 + 格式
    val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = max(1f, unit * .9f)
        color = withAlphaInt(muted, .45f)
    }
    val ruleY = height * .80f
    canvas.drawLine(width * .18f, ruleY, width * .82f, ruleY, rule)
    val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlphaInt(muted, .92f)
        textSize = unit * 8.2f
        letterSpacing = .04f
    }
    val authorText = TextUtils.ellipsize(
        author.ifBlank { "—" },
        authorPaint,
        width * .64f,
        TextUtils.TruncateAt.END
    ).toString()
    canvas.drawText(authorText, (width - authorPaint.measureText(authorText)) * .5f, ruleY + unit * 12f, authorPaint)

    val tagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlphaInt(if (tag == "COMIC") 0xFFFF6B6B.toInt() else tint, .95f)
        textSize = unit * 7.4f
        typeface = Typeface.create("monospace", Typeface.BOLD)
        letterSpacing = .16f
    }
    canvas.drawText(
        tag,
        (width - tagPaint.measureText(tag)) * .5f,
        height - height * .055f,
        tagPaint
    )

    // 注意：这里**不再**画左缘压暗条。3D 书有自己的书脊和装订槽，
    // 再叠一条深色竖带会被书脊包裹采样取走，把白封面的书染成"黑书脊"。
    return bitmap.asImageBitmap()
}
private fun buildShelfLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
    val builder = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, 1.12f)
        .setIncludePad(false)
        .setMaxLines(4)
        .setEllipsize(TextUtils.TruncateAt.END)
    return builder.build()
}

private fun withAlphaInt(color: Int, alpha: Float): Int =
    (color and 0x00ffffff) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

/** 取封面左侧一条竖带的平均色：书脊纸色/布色的来源，也是书脊包裹色。 */
internal fun dominantEdgeColor(bitmap: ImageBitmap): Int = runCatching {
    val source = bitmap.asAndroidBitmap()
    val sampleWidth = (source.width * .16f).toInt().coerceAtLeast(1)
    var r = 0L
    var g = 0L
    var b = 0L
    var count = 0
    val stepY = (source.height / 24).coerceAtLeast(1)
    val stepX = (sampleWidth / 6).coerceAtLeast(1)
    var y = 0
    while (y < source.height) {
        var x = 0
        while (x < sampleWidth) {
            val pixel = source.getPixel(x, y)
            r += Color.red(pixel); g += Color.green(pixel); b += Color.blue(pixel)
            count++
            x += stepX
        }
        y += stepY
    }
    if (count == 0) Color.DKGRAY else Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
}.getOrDefault(Color.DKGRAY)
