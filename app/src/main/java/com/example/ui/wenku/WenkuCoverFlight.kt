// android.graphics.Vertices / Canvas.drawVertices 在 API 30 被 Mesh / drawMesh 取代，
// 但 minSdk 24 只能用它们；语义与 ui.Vertices.raw 一致，因此这里整体压掉弃用告警。
@file:Suppress("DEPRECATION")

package com.example.ui.wenku

import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
 * | --- | --- |
// （历史注释在批量改写中损坏，已按实现删除）
 * | `motion_ui.dart:42-167` `PaperFlightPose` + `PaperCoverRectTween.lerp` | [WenkuFlightPose] |
 * | `motion_ui.dart:200-520` `PaperCoverBend` / `PaperCoverRender` | [WenkuPaperMesh] + `Canvas.drawVertices` |
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
 * | `archive_motion.dart:278-436` `ArchivePlaneTransition` | [WenkuPlanePose] |
 *
// （历史注释在批量改写中损坏，已按实现删除）
 *
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
 * ## 时钟约定
 *
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
 */

// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）

/** （历史注释在批量改写中损坏，已按实现删除） */
const val WENKU_MESH_COLUMNS: Int = 17

/** （历史注释在批量改写中损坏，已按实现删除） */
const val WENKU_MESH_ROWS: Int = 32

/** （历史注释在批量改写中损坏，已按实现删除） */
const val WENKU_MESH_EDGE_SAMPLES: Int = 8

private const val PI_F: Float = 3.1415927f

/** （历史注释在批量改写中损坏，已按实现删除） */
private val BACKING_ARGB: Int = 0xFF202020.toInt()

// （历史注释在批量改写中损坏，已按实现删除）
// 绾暟瀛︼細WenkuFlightPose
// （历史注释在批量改写中损坏，已按实现删除）

/** （历史注释在批量改写中损坏，已按实现删除） */
data class WenkuFlightRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * .5f
    val centerY: Float get() = (top + bottom) * .5f

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun inflate(delta: Float): WenkuFlightRect =
        WenkuFlightRect(left - delta, top - delta, right + delta, bottom + delta)

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun shift(dx: Float, dy: Float): WenkuFlightRect =
        WenkuFlightRect(left + dx, top + dy, right + dx, bottom + dy)
}

/**
 * `motion_ui.dart:42-167` 的完整移植：`PaperFlightPose`（travel / heroTimeline / depthAt /
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
 * ```
 * assertEquals(0f, WenkuFlightPose.travel(.12f))          // 璧烽鍓嶄笉鍔? * assertEquals(1f, WenkuFlightPose.travel(.58f))          // 鍒拌揪鍓嶅凡鍒颁綅
 * assertEquals(0f, WenkuFlightPose.depthAt(.5f, .5f, 0f))
 * assertEquals(0f, WenkuFlightPose.depthAt(.5f, .5f, 1f))
 * assertEquals(18f, WenkuFlightPose.lift(.18f), 1e-4f)    // 宄板€煎湪 .18
 * assertTrue(WenkuFlightPose.roll(.5f) < 0f)              // -0.045 * sin(pi/2)
 * assertEquals(49, WenkuFlightPose.bendPoints().size)     // 32 琛?+ 16 杈圭紭 + 绔偣
 * ```
 */
object WenkuFlightPose {

/** （历史注释在批量改写中损坏，已按实现删除） */
    const val REFERENCE_FACE_WIDTH: Float = 120f
    const val REFERENCE_FACE_HEIGHT: Float = 164f

/** （历史注释在批量改写中损坏，已按实现删除） */
    const val CORNER_INSET: Float = 8f

/** （历史注释在批量改写中损坏，已按实现删除） */
    const val RECT_LIFT_LIMIT: Float = 32f
    const val RECT_LIFT_BASE: Float = 12f
    const val RECT_LIFT_PER_PIXEL: Float = .075f

/** （历史注释在批量改写中损坏，已按实现删除） */
    const val BEND_FOCAL: Float = 300f

// （历史注释在批量改写中损坏，已按实现删除）

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun bezier(x1: Float, y1: Float, x2: Float, y2: Float, value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var low = 0f
        var high = 1f
        var t = x
        repeat(20) {
            t = (low + high) * .5f
            if (bezierComponent(t, x1, x2) < x) low = t else high = t
        }
        return bezierComponent(t, y1, y2)
    }

    private fun bezierComponent(t: Float, a: Float, b: Float): Float {
        val remaining = 1f - t
        return 3f * remaining * remaining * t * a + 3f * remaining * t * t * b + t * t * t
    }

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun easeInOutCubic(value: Float): Float = bezier(.645f, .045f, .355f, 1f, value)

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun easeOutCubic(value: Float): Float = bezier(.215f, .61f, .355f, 1f, value)

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun easeInOut(value: Float): Float = bezier(.42f, 0f, .58f, 1f, value)

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun heroClock(value: Float): Float = bezier(.4f, 0f, .2f, 1f, value)

// （历史注释在批量改写中损坏，已按实现删除）

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun smooth(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun travel(progress: Float): Float =
        easeInOutCubic(((progress - .12f) / .46f).coerceIn(0f, 1f))

    /**
 */
    fun heroTimeline(value: Float): Float {
        if (value <= 0f || value >= 1f) return value.coerceIn(0f, 1f)
        var low = 0f
        var high = 1f
        repeat(18) {
            val parameter = (low + high) / 2f
            if (smooth(parameter) < value) low = parameter else high = parameter
        }
        val t = (low + high) / 2f
        val remaining = 1f - t
        // x of Cubic(.4, 0, .2, 1), after solving its y above.
        return 1.2f * remaining * remaining * t + .6f * remaining * t * t + t * t * t
    }

    /**
// （历史注释在批量改写中损坏，已按实现删除）
     *
     * ```
     * peel     = _smooth((p - .025 - v * .11) / .11)
     * landing  = clamp((p - .58) / .42)
     * contact  = (1 - v) * .66 + u * .34
     * floating = 1 - _smooth((landing - contact * .70) / .30)
     * breeze   = sin((v * 1.3 - p * 1.6) * 2蟺)
     * depth    = peel * floating * (17 + 7 * sin(v * 蟺) + 3 * breeze * sin(p * 蟺))
     * ```
     *
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
     */
    fun depthAt(u: Float, v: Float, p: Float): Float {
        if (p <= 0f || p >= 1f) return 0f
        val row = v.coerceIn(0f, 1f)
        val column = u.coerceIn(0f, 1f)
        val peel = smooth((p - .025f - row * .11f) / .11f)
        val landing = ((p - .58f) / .42f).coerceIn(0f, 1f)
// （历史注释在批量改写中损坏，已按实现删除）
        val contact = (1f - row) * .66f + column * .34f
        val floating = 1f - smooth((landing - contact * .70f) / .30f)
        val breeze = sin((row * 1.3f - p * 1.6f) * PI_F * 2f)
        return peel * floating * (17f + 7f * sin(row * PI_F) + 3f * breeze * sin(p * PI_F))
    }

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun lift(p: Float): Float =
        18f * smooth(p / .18f) * (1f - smooth((p - .42f) / .16f))

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun roll(p: Float): Float =
        -.045f * sin(p.coerceIn(0f, 1f) * PI_F) * (1f - smooth((p - .42f) / .16f))

    /**
// （历史注释在批量改写中损坏，已按实现删除）
     *
     * ```
     * corner = min(v, 1 - v) / radiusY
     * inset  = corner >= 1 ? 0 : radiusX * (1 - sqrt(max(0, 1 - (1 - corner)虏)))
     * ```
     */
    fun columnInset(row: Float, radiusX: Float, radiusY: Float): Float {
        if (radiusY <= 0f) return 0f
        val v = row.coerceIn(0f, 1f)
        val corner = min(v, 1f - v) / radiusY
        if (corner >= 1f) return 0f
        return radiusX * (1f - sqrt(max(0f, 1f - (1f - corner) * (1f - corner))))
    }

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun radiusX(): Float = (CORNER_INSET / REFERENCE_FACE_WIDTH).coerceIn(0f, .5f)

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun radiusY(): Float = (CORNER_INSET / REFERENCE_FACE_HEIGHT).coerceIn(0f, .5f)

    /**
 */
    fun meshRows(radiusY: Float): FloatArray {
        val radius = radiusY.coerceIn(0f, .5f)
        val rows = LinkedHashSet<Float>()
        for (i in 0..WENKU_MESH_ROWS) rows.add(i.toFloat() / WENKU_MESH_ROWS.toFloat())
        for (i in 1..WENKU_MESH_EDGE_SAMPLES) {
            val v = radius * (1f - cos(i.toFloat() / WENKU_MESH_EDGE_SAMPLES.toFloat() * PI_F / 2f))
            rows.add(v)
            rows.add(1f - v)
        }
        return rows.sorted().toFloatArray()
    }

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun bendPoints(): Array<Pair<Float, Float>> {
        val rx = radiusX()
        val ry = radiusY()
        val rows = meshRows(ry)
        return Array(rows.size) { index ->
            rows[index] to columnInset(rows[index], rx, ry)
        }
    }

// （历史注释在批量改写中损坏，已按实现删除）

    /**
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
     * @param begin 璧烽鐭╁舰
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
     */
    fun rectAt(
        begin: WenkuFlightRect,
        end: WenkuFlightRect,
        returning: Boolean,
        value: Float,
        endPaintOffsetX: Float = 0f,
        endPaintOffsetY: Float = 0f,
        heroCurve: Boolean = false
    ): WenkuFlightRect {
        val target = end.shift(endPaintOffsetX, endPaintOffsetY)
        if (value <= 0f) return begin
        if (value >= 1f) return target
        val t = if (heroCurve) heroTimeline(value) else value
        val journey = if (returning) 1f - travel(1f - t) else travel(t)
        if (journey <= 0f) return begin
        if (journey >= 1f) return target
        val centerX = begin.centerX + (target.centerX - begin.centerX) * journey
        val centerY = begin.centerY + (target.centerY - begin.centerY) * journey
        val distance = hypot(target.centerX - begin.centerX, target.centerY - begin.centerY)
        val lift = min(RECT_LIFT_LIMIT, RECT_LIFT_BASE + distance * RECT_LIFT_PER_PIXEL) *
            sin(journey * PI_F)
        val width = begin.width + (target.width - begin.width) * journey
        val height = begin.height + (target.height - begin.height) * journey
        return WenkuFlightRect(
            left = centerX - width * .5f,
            top = centerY - lift - height * .5f,
            right = centerX + width * .5f,
            bottom = centerY - lift + height * .5f
        )
    }
}

// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）

/**
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
 * ```
 * progress    = Cubic(.18, .72, .18, 1).transform(animation.value)
 * displacement = (-24 * dir * (1 - p), 12 * (1 - p))
 * slant        = min(180dp, width * .46)
 * edge         = (width + slant) * p
 * viewport     = (0,0) 鈫?(edge,0) 鈫?(edge - slant,h) 鈫?(0,h)
 * ```
 */
object WenkuPlanePose {

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun ease(raw: Float): Float = WenkuFlightPose.bezier(.18f, .72f, .18f, 1f, raw)

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun offsetX(progress: Float, direction: Int = 1): Float = -24f * direction * (1f - progress)

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun offsetY(progress: Float): Float = 12f * (1f - progress)

/** （历史注释在批量改写中损坏，已按实现删除） */
    fun slant(widthPx: Float, maxSlantPx: Float): Float = min(maxSlantPx, widthPx * .46f)

    /**
 */
    fun viewportPoints(
        width: Float,
        height: Float,
        progress: Float,
        direction: Int = 1,
        slant: Float
    ): FloatArray {
        val p = progress.coerceIn(0f, 1f)
        val edge = (width + slant) * p
        val x = { value: Float -> if (direction >= 0) value else width - value }
        return floatArrayOf(
            x(0f), 0f,
            x(edge), 0f,
            x(edge - slant), height,
            x(0f), height
        )
    }
}

// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）

/** drawBitmapMesh samples a uniform grid. Match those UVs before applying the paper bend. */
private class WenkuPaperMesh {
    val positions = FloatArray((WENKU_MESH_ROWS + 1) * WENKU_MESH_COLUMNS * 2)

    fun fill(progress: Float, width: Float, height: Float) {
        val lastColumn = WENKU_MESH_COLUMNS - 1
        for (row in 0..WENKU_MESH_ROWS) {
            val v = row.toFloat() / WENKU_MESH_ROWS
            for (column in 0..lastColumn) {
                val u = column.toFloat() / lastColumn
                val scale = WenkuFlightPose.BEND_FOCAL /
                    (WenkuFlightPose.BEND_FOCAL - WenkuFlightPose.depthAt(u, v, progress))
                val index = (row * WENKU_MESH_COLUMNS + column) * 2
                positions[index] = width * (.5f + (u - .5f) * scale)
                positions[index + 1] = height * (.5f + (v - .5f) * scale)
            }
        }
    }
}

private class WenkuPaperBrush {

/** （历史注释在批量改写中损坏，已按实现删除） */
    val fill: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

/** （历史注释在批量改写中损坏，已按实现删除） */
    val fallback: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BACKING_ARGB }

    /** 圆角裁剪路径：drawBitmapMesh 本身不带圆角，起飞/落地阶段靠它保持卡片形状。 */
    val clip: AndroidPath = AndroidPath()

}

// （历史注释在批量改写中损坏，已按实现删除）
// 瀵瑰 API
// （历史注释在批量改写中损坏，已按实现删除）

/** （历史注释在批量改写中损坏，已按实现删除） */
data class WenkuCoverSource(
    val bounds: Rect,
    val image: ImageBitmap?,
    val cornerRadius: Dp = 3.dp
)

/** （历史注释在批量改写中损坏，已按实现删除） */
data class WenkuCoverTarget(
    val bounds: Rect,
    val image: ImageBitmap?,
    val cornerRadius: Dp = 3.dp
)

/** （历史注释在批量改写中损坏，已按实现删除） */
@Composable
fun WenkuCoverFlight(
    visible: Boolean,
    source: WenkuCoverSource?,
    target: WenkuCoverTarget?,
    departing: Boolean,
    modifier: Modifier = Modifier,
    /** true = 接着上一段动效继续放大：去掉起步 12% 的"停顿"，矩形立刻开始运动。 */
    seamless: Boolean = false,
    onFinished: () -> Unit = {}
) {
    val a11y = rememberWenkuAccessibility()
    val finish = rememberUpdatedState(onFinished)
    val progress = remember { Animatable(0f) }
    val durationMs = if (departing) WenkuMotion.COVER_FLIGHT_MS else WenkuMotion.COVER_RETURN_MS

    LaunchedEffect(visible, departing, durationMs, a11y.reduceMotion) {
        if (!visible) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        if (a11y.reduceMotion) {
// （历史注释在批量改写中损坏，已按实现删除）
            progress.snapTo(1f)
            finish.value()
            return@LaunchedEffect
        }
        progress.snapTo(0f)
// （历史注释在批量改写中损坏，已按实现删除）
        progress.animateTo(1f, tween(durationMillis = durationMs, easing = LinearEasing))
        finish.value()
    }

    if (!visible) return
    val from = source ?: return
    val to = target ?: return
    val scrimColor = androidx.compose.material3.MaterialTheme.colorScheme.background.toArgb()

    val begin = if (departing) from.bounds else to.bounds
    val end = if (departing) to.bounds else from.bounds
    val image = from.image ?: to.image
    val cornerRadiusPx = with(LocalDensity.current) { from.cornerRadius.toPx() }
    val bitmap = remember(image) { image?.asAndroidBitmap() }
    val mesh = remember { WenkuPaperMesh() }
    val brush = remember { WenkuPaperBrush() }
    val maskPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.BLACK
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .drawWithContent {
                drawWenkuCoverFlight(
                    scrimColor = scrimColor,
                    seamless = seamless,
                    progress = progress.value,
                    begin = begin,
                    end = end,
                    returning = !departing,
                    departing = departing,
                    silhouetteBounds = from.bounds,
                    bitmap = bitmap,
                    cornerRadiusPx = cornerRadiusPx,
                    mesh = mesh,
                    brush = brush,
                    maskPaint = maskPaint
                )
            }
    )
}

/** （历史注释在批量改写中损坏，已按实现删除） */
@Composable
fun WenkuCoverArrival(
    ready: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    WenkuCoverArrivalFrame(frameKey = null, ready = ready, modifier = modifier, content = content)
}

/** （历史注释在批量改写中损坏，已按实现删除） */
@Composable
fun WenkuCoverArrivalFrame(
    frameKey: Any?,
    ready: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val a11y = rememberWenkuAccessibility()
    var presented by rememberSaveable(frameKey) {
        mutableStateOf(WenkuCoverArrivalCache.wasPresented(frameKey))
    }
    var everReady by remember(frameKey) { mutableStateOf(ready) }
    var started by remember(frameKey) { mutableStateOf(false) }
    val progress = remember(frameKey) { Animatable(0f) }
    val hasFrame = everReady || ready

    LaunchedEffect(ready) {
        if (ready) everReady = true
    }

    LaunchedEffect(hasFrame, a11y.reduceMotion) {
        if (!hasFrame || presented || started) return@LaunchedEffect
        if (a11y.reduceMotion) {
            presented = true
            WenkuCoverArrivalCache.markPresented(frameKey)
            return@LaunchedEffect
        }
// （历史注释在批量改写中损坏，已按实现删除）
        withFrameNanos { }
        if (started || presented || !hasFrame) return@LaunchedEffect
        started = true
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = WenkuMotion.ENTER_MS, easing = LinearEasing)
        )
        presented = true
        WenkuCoverArrivalCache.markPresented(frameKey)
    }

    if (!hasFrame) {
        // No decoded frame yet: draw the content untouched so a cover can never be blanked out
        // by the wrapper (a missing `ready` signal must only cost the reveal, not the pixels).
        Box(modifier) { content() }
        return
    }
    if (!started && !presented) {
        // Ready and about to play: hold one frame at the clip origin (the slot underneath keeps
        // showing the page/skeleton, matching cover_arrival.dart).
        Box(modifier) {}
        return
    }
    if (presented) {
        Box(modifier) { content() }
        return
    }
    Box(
        modifier
            .graphicsLayer {
                val p = WenkuPlanePose.ease(progress.value.coerceIn(0f, 1f))
                translationX = WenkuPlanePose.offsetX(p) * density
                translationY = WenkuPlanePose.offsetY(p) * density
            }
            .drawWithCache {
                val viewport = Path()
                val maxSlant = 180.dp.toPx()
                onDrawWithContent {
                    val p = WenkuPlanePose.ease(progress.value.coerceIn(0f, 1f))
                    if (p >= 1f) {
                        drawContent()
                        return@onDrawWithContent
                    }
                    val points = WenkuPlanePose.viewportPoints(
                        width = size.width,
                        height = size.height,
                        progress = p,
                        direction = 1,
                        slant = WenkuPlanePose.slant(size.width, maxSlant)
                    )
                    viewport.rewind()
                    viewport.moveTo(points[0], points[1])
                    viewport.lineTo(points[2], points[3])
                    viewport.lineTo(points[4], points[5])
                    viewport.lineTo(points[6], points[7])
                    viewport.close()
                    clipPath(viewport) { this@onDrawWithContent.drawContent() }
                }
            }
    ) {
        content()
    }
}

/**
 */
object WenkuCoverArrivalCache {

    private val presented: MutableMap<Any, Boolean> =
        Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    fun wasPresented(key: Any?): Boolean = key != null && presented[key] == true

    fun markPresented(key: Any?) {
        if (key != null) presented[key] = true
    }

    fun clear(key: Any?) {
        if (key != null) presented.remove(key)
    }
}

// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）
// （历史注释在批量改写中损坏，已按实现删除）

private fun Rect.toFlightRect(units: Float): WenkuFlightRect =
    WenkuFlightRect(left / units, top / units, right / units, bottom / units)

private fun WenkuFlightRect.toRect(units: Float): Rect =
    Rect(left * units, top * units, right * units, bottom * units)

/** （历史注释在批量改写中损坏，已按实现删除） */
private fun DrawScope.drawWenkuCoverFlight(
    scrimColor: Int,
    seamless: Boolean,
    progress: Float,
    begin: Rect,
    end: Rect,
    returning: Boolean,
    departing: Boolean,
    silhouetteBounds: Rect,
    bitmap: android.graphics.Bitmap?,
    cornerRadiusPx: Float,
    mesh: WenkuPaperMesh,
    brush: WenkuPaperBrush,
    maskPaint: Paint
) {
    if (begin.width <= 0f || begin.height <= 0f) return
    if (end.width <= 0f || end.height <= 0f) return

    // 起飞阶段铺一层纸色：先快速盖住书架/阅读器的切换，再随封面放大淡出，
    // 于是"纸张出现"和"封面放大"是同步渐变的，没有硬切。
    // 包络：0 →(前 12%)→ 1 →(30%~64%)→ 0，两端导数都为 0，肉眼看不到起止点。
    if (departing) {
        val rise = WenkuFlightPose.smooth((progress / .12f).coerceIn(0f, 1f))
        val fall = WenkuFlightPose.smooth(((progress - .30f) / .34f).coerceIn(0f, 1f))
        val scrimAlpha = rise * (1f - fall)
        if (scrimAlpha > .004f) {
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint()
                paint.color = scrimColor
                paint.alpha = (scrimAlpha * 255f).toInt().coerceIn(0, 255)
                canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
            }
        }
    }

// （历史注释在批量改写中损坏，已按实现删除）
    val units = density
    val p = progress.coerceIn(0f, 1f)
    // 默认时间轴在起步 12% 处有一小段"抓住封面"的停顿（列表书架的手感）。
    // 从 3D 书架接手时不能有这个停顿，否则就是"第二段动画"；seamless 直接线性起步。
    // rectAt 内部还会走一次 travel()（起点 .12、终点 .58）：把 p 线性重映射到这段区间，
    // 就等于"没有起步停顿、按同样的缓动走完全程"。
    val rectValue = if (seamless && !returning) {
        (.12f + .46f * (p / .58f)).coerceIn(0f, 1f)
    } else p
    val rectDp = WenkuFlightPose.rectAt(
        begin = begin.toFlightRect(units),
        end = end.toFlightRect(units),
        returning = returning,
        value = rectValue
    )
    if (rectDp.width <= 0f || rectDp.height <= 0f) return
    val rect = rectDp.toRect(units)

// （历史注释在批量改写中损坏，已按实现删除）
    val paintBounds = rectDp.inflate(28f)

// （历史注释在批量改写中损坏，已按实现删除）
    val pose = WenkuFlightPose.heroClock(progress.coerceIn(0f, 1f))
    val liftPx = WenkuFlightPose.lift(pose) * units
    val rollDegrees = WenkuFlightPose.roll(pose) * (180f / PI_F)

    val occlusion = wenkuCoverSilhouette(
// （历史注释在批量改写中损坏，已按实现删除）
        progress = pose,
        departing = departing,
        bounds = silhouetteBounds,
        lipHeightPx = 28f * units,
        cornerRadiusPx = cornerRadiusPx
    )

    // Hand the frame over to the reader as the cover approaches its landing pose.
    val openingProgress = if (departing) progress else 1f - progress
    // 封面一直保持不透明，直到铺满整屏（约 78%）才开始淡出交给阅读器：
    // 旧版从 42% 就开始透明，放大过程"发灰"，看起来像另做了一段动画。
    val opacity = 1f - WenkuFlightPose.smooth(((openingProgress - .78f) / .22f).coerceIn(0f, 1f))
    if (opacity <= 0f) return
    val alpha = (opacity * 255).toInt()

    drawIntoCanvas { composeCanvas ->
        val canvas = composeCanvas.nativeCanvas
        if (occlusion != null) {
            canvas.saveLayer(
                paintBounds.left * units,
                paintBounds.top * units,
                paintBounds.right * units,
                paintBounds.bottom * units,
                null
            )
        }
        canvas.save()
        canvas.translate(rect.left + rect.width * .5f, rect.top + rect.height * .5f - liftPx)
        canvas.rotate(rollDegrees)
        canvas.translate(-rect.width * .5f, -rect.height * .5f)
        // 圆角跟着行程从"卡片的圆角"过渡到"目标页的直角"。
        // `drawBitmapMesh` 本身没有圆角，不裁的话起飞那一帧卡片就从圆角变成尖角。
        val traveled = if (departing) progress.coerceIn(0f, 1f) else 1f - progress.coerceIn(0f, 1f)
        val flightRadius = cornerRadiusPx * (1f - WenkuFlightPose.smooth(traveled))
        if (flightRadius > .35f) {
            val roundClip = brush.clip
            roundClip.rewind()
            roundClip.addRoundRect(
                0f, 0f, rect.width, rect.height,
                flightRadius, flightRadius,
                AndroidPath.Direction.CW
            )
            canvas.clipPath(roundClip)
        }
        if (bitmap != null) {
            brush.fill.alpha = alpha
            mesh.fill(pose, rect.width, rect.height)
            canvas.drawBitmapMesh(
                bitmap,
                WENKU_MESH_COLUMNS - 1,
                WENKU_MESH_ROWS,
                mesh.positions,
                0,
                null,
                0,
                brush.fill
            )
        } else {
            brush.fallback.alpha = alpha
            canvas.drawRoundRect(
                0f,
                0f,
                rect.width,
                rect.height,
                flightRadius,
                flightRadius,
                brush.fallback
            )
        }
        canvas.restore()
        if (occlusion != null) {
            maskPaint.alpha = (occlusion.second * 255f).toInt().coerceIn(0, 255)
            canvas.drawPath(occlusion.first, maskPaint)
            canvas.restore()
        }
    }
}

/** （历史注释在批量改写中损坏，已按实现删除） */
private fun wenkuCoverSilhouette(
    progress: Float,
    departing: Boolean,
    bounds: Rect,
    lipHeightPx: Float,
    cornerRadiusPx: Float
): Pair<AndroidPath, Float>? {
    if (bounds.width <= 0f || bounds.height <= 0f) return null
    val alpha = if (departing) {
        if (progress >= .12f) return null
        1f - WenkuFlightPose.easeInOut((progress / .12f).coerceIn(0f, 1f))
    } else {
        if (progress <= .88f) return null
        WenkuFlightPose.easeInOut(((progress - .88f) / .12f).coerceIn(0f, 1f))
    }
    if (alpha <= 0.001f) return null
    val path = AndroidPath()
    val lip = RectF(bounds.left, bounds.bottom, bounds.right, bounds.bottom + lipHeightPx)
    path.addRoundRect(lip, cornerRadiusPx, cornerRadiusPx, AndroidPath.Direction.CW)
    return path to alpha
}

/**
 * `motion_ui.dart:94-101` lift/roll applied to whatever element is currently flying
 * (the shared-element cover in this app). The shared transition already owns the bounds,
 * so this only adds the paper pose: `lift = 18 * smooth(p/.18) * (1 - smooth((p-.42)/.16))`,
 * `roll = -.045 * sin(pi p) * (1 - smooth((p-.42)/.16))`.
 *
 * Values are kept at half strength in translation so the cover never fights its own
 * bounds interpolation. Inactive state renders nothing (single graphicsLayer, no ticker).
 */
@Composable
fun Modifier.wenkuPaperFlight(active: Boolean, strength: Float = .5f): Modifier {
    val clock = remember { Animatable(1f) }
    val reduce = rememberWenkuAccessibility().reduceMotion
    LaunchedEffect(active, reduce) {
        if (active && !reduce) {
            clock.snapTo(0f)
            clock.animateTo(1f, tween(durationMillis = WenkuMotion.COVER_FLIGHT_MS, easing = LinearEasing))
        } else if (!active) {
            clock.snapTo(1f)
        }
    }
    if (!active) return this
    val pose = WenkuFlightPose.heroClock(clock.value.coerceIn(0f, 1f))
    return this.graphicsLayer {
        translationY = -WenkuFlightPose.lift(pose) * strength * density
        rotationZ = WenkuFlightPose.roll(pose) * (180f / PI_F)
    }
}
