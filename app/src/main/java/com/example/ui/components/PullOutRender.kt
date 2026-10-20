package com.example.ui.components

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.*

/** Diagnostics count only animation draws; useful for targeted device performance checks. */
internal object PaperRenderHealth { var useLightweightFallback = false }

internal object PaperRenderStats {
    var gpuFrames = 0
    var fallbackFrames = 0
    var maxMeshBuildNanos = 0L
    var snapshots = 0
    fun reset() { gpuFrames = 0; fallbackFrames = 0; maxMeshBuildNanos = 0; snapshots = 0 }
}
private data class PaperPixels(val card: Bitmap, val shadow: Bitmap?, val shadowOffset: IntArray,
    val scale: Float, val crop: Offset)

private fun android.view.View.paperWindow(): Window? {
    (parent as? DialogWindowProvider)?.window?.let { return it }
    var current = context
    while (current is ContextWrapper) { if (current is Activity) return current.window; current = current.baseContext }
    return null
}
/** PixelCopy is requested only by a real opening, never by ordinary button presses. */
private suspend fun capturePaperButton(source: PullOutSource): Bitmap? {
    if (source.hidden) source.preview?.get()?.let { return it }
    if (Build.VERSION.SDK_INT < 26 || source.virtual) return null
    val view = source.view?.get()?.takeIf { it.isAttachedToWindow } ?: return null
    val window = view.paperWindow() ?: return null
    val rect = source.bounds.translate(-view.paperWindowOffset())
    if (rect.width <= 0 || rect.height <= 0) return null
    val width = rect.width.roundToInt().coerceIn(1, 1024)
    val height = rect.height.roundToInt().coerceIn(1, 512)
    return try {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val copied = withTimeoutOrNull(250) { suspendCancellableCoroutine { continuation ->
            PixelCopy.request(window, android.graphics.Rect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt()),
                bitmap, { result -> if (continuation.isActive) continuation.resume(if (result == PixelCopy.SUCCESS) bitmap else null) }, Handler(Looper.getMainLooper()))
        } }
        if (copied == null) null else withContext(Dispatchers.Default) {
            val masked = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(masked)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val radius = min(height / 2f, 24f * view.resources.displayMetrics.density)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(copied, 0f, 0f, paint)
            masked.prepareToDraw(); source.preview = java.lang.ref.WeakReference(masked); masked
        }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
}
private fun cropPaper(bitmap: Bitmap, ratio: Float, density: Float): PaperPixels {
    val row = IntArray(bitmap.width)
    var left = bitmap.width; var right = -1; var top = bitmap.height; var bottom = -1
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        for (x in row.indices) if ((row[x] ushr 24) > 4) {
            left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
        }
    }
    if (right < left || bottom < top) return PaperPixels(bitmap, null, intArrayOf(0, 0), ratio, Offset.Zero)
    // Retain transparent texels so hardware bilinear sampling feathers the mesh boundary.
    left = (left - 2).coerceAtLeast(0); right = (right + 2).coerceAtMost(bitmap.width - 1)
    top = (top - 2).coerceAtLeast(0); bottom = (bottom + 2).coerceAtMost(bitmap.height - 1)
    val card = Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    val offset = IntArray(2)
    val shadow = runCatching { card.extractAlpha(Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter((10f * density * ratio).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
    }, offset) }.getOrNull()
    card.prepareToDraw(); shadow?.prepareToDraw()
    return PaperPixels(card, shadow, offset, ratio, Offset(left / ratio, top / ratio))
}

/** One CPU evaluation per mesh row; expensive functions are not repeated for all 27 columns. */
internal fun fillPaperMesh(vertices: FloatArray, cardColors: IntArray, sourceColors: IntArray,
    width: Float, height: Float, source: Rect, p: Float, density: Float) {
    val q = p.coerceIn(0f, 1f); val wave = sin(PI * q).toFloat()
    val slit = source.center.y.coerceIn(0f, height); val far = max(slit, height - slit).coerceAtLeast(1f)
    val softness = 12f * density
    val lock = (1f - q) * (far + softness * 10f) - q * softness * 10f
    fun softplus(x: Float): Float = when { x / softness > 20f -> x; x / softness < -20f -> 0f; else -> softness * ln(1f + exp(x / softness)) }
    val base = softplus(-lock)
    val y0 = source.center.y + (slit / height.coerceAtLeast(1f) - .5f) * source.height * (1f - q) + (slit - source.center.y) * q
    val centerX = source.center.x + (width / 2 - source.center.x) * q
    for (row in 0..PullOutConfig.ROWS) {
        val v = row.toFloat() / PullOutConfig.ROWS
        val delta = v * height - slit
        val released = softplus(abs(delta) - lock) - base
        val mapped = released + (abs(delta) - released) * source.height / height.coerceAtLeast(1f)
        val free = (released / far).coerceIn(0f, 1f)
        val neck = 1f - (1f - PullOutConfig.NECK) * wave.pow(.7f) * exp(-abs(mapped) / (PullOutConfig.DECAY * density))
        val unfold = q + (1f - q) * (1f - exp(-released / (24f * density)))
        val openWidth = source.width + (width - source.width) * unfold
        val bend = PullOutConfig.BEND * density * wave * free * free
        val flutter = PullOutConfig.FLUTTER * density * wave * free * sin(v * 4f + q * 5f)
        val blend = PaperMesh.blend(v, height, slit, p)
        val shade = (255 * (1f - .035f * (1f - blend) * wave * (1f + sin(v * 9f)))).toInt().coerceIn(0, 255)
        val card = android.graphics.Color.argb((blend * 255).toInt(), shade, shade, shade)
        val button = android.graphics.Color.argb(((1f - blend) * 255).toInt(), 255, 255, 255)
        for (col in 0..PullOutConfig.COLUMNS) {
            val u = col.toFloat() / PullOutConfig.COLUMNS
            val index = row * (PullOutConfig.COLUMNS + 1) + col
            val arch = PullOutConfig.ARCH * density * wave * free * (1f - (2 * u - 1).pow(2))
            val point = when {
                p <= 0f -> Offset(source.left + u * source.width, source.top + v * source.height)
                p == 1f -> Offset(u * width, v * height)
                else -> Offset(centerX + (u - .5f) * openWidth * neck + bend + flutter,
                    y0 + delta.sign * (mapped + arch) - (p - 1f).coerceAtLeast(0f) * height * .10f)
            }
            vertices[index * 2] = point.x; vertices[index * 2 + 1] = point.y
            cardColors[index] = card; sourceColors[index] = button
        }
    }
}

/** Hardware drawBitmapMesh submits the immutable textures and reusable vertex arrays directly to HWUI.
 * No software Canvas, supersampled framebuffer, bitmap upload, or per-frame blur is used.
 */
@Composable
internal fun HardwarePaperSurface(life: PaperLifecycle, reduce: Boolean, content: @Composable () -> Unit) {
    val origin = remember { life.origin }
    val layer = rememberGraphicsLayer()
    val view = LocalView.current
    val density = LocalDensity.current.density
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var pixels by remember { mutableStateOf<PaperPixels?>(null) }
    var button by remember { mutableStateOf<Bitmap?>(null) }
    var ownsHide by remember { mutableStateOf(false) }
    fun revealSource() { if (ownsHide) { origin?.reveal(); ownsHide = false } }
    var requestRecord by remember { mutableStateOf(life.meshEnabled) }
    var recorded by remember { mutableIntStateOf(0) }
    var captureInProgress by remember { mutableStateOf(false) }
    var recordedRatio by remember { mutableFloatStateOf(1f) }
    val vertices = remember { FloatArray((PullOutConfig.COLUMNS + 1) * (PullOutConfig.ROWS + 1) * 2) }
    val shadowVertices = remember { FloatArray(vertices.size) }
    val cardColors = remember { IntArray(vertices.size / 2) }
    val sourceColors = remember { IntArray(vertices.size / 2) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val buttonReady = remember { CompletableDeferred<Unit>() }
    LaunchedEffect(Unit) {
        try { if (life.meshEnabled) button = origin?.let { capturePaperButton(it) } }
        finally { buttonReady.complete(Unit) }
    }
    LaunchedEffect(life.captureRevision) { if (life.meshEnabled) requestRecord = true }
    LaunchedEffect(recorded) {
        if (!life.meshEnabled) { life.ready = true; return@LaunchedEffect }
        if (recorded == 0 || bounds.width <= 0 || bounds.height <= 0) return@LaunchedEffect
        try {
            val image = withTimeoutOrNull(700) { layer.toImageBitmap() }?.asAndroidBitmap()
                ?: throw IllegalStateException("Paper capture timed out")
            val ratio = recordedRatio
            val result = withContext(Dispatchers.Default) { cropPaper(image.copy(Bitmap.Config.ARGB_8888, false), ratio, density) }
            pixels = result; PaperRenderStats.snapshots++
            buttonReady.await()
            if (button == null) { life.meshEnabled = false; revealSource() }
            if (button != null && origin != null && !(origin.bounds.contains(bounds.topLeft) && origin.bounds.contains(bounds.bottomRight - Offset(.01f, .01f)))) { if (!ownsHide) { origin.hide(); ownsHide = true } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { life.meshEnabled = false; revealSource() }
        catch (_: OutOfMemoryError) { pixels = null; life.meshEnabled = false; revealSource() }
        finally {
            // A recorded child graph never outlives its pixel conversion, even on cancellation.
            layer.record(Density(1f), LayoutDirection.Ltr, IntSize(1, 1)) { }
            captureInProgress = false
        }
        life.ready = true
    }
    DisposableEffect(Unit) { onDispose {
        // HWUI may still reference the previous frame. Let its bitmap references retire naturally.
        revealSource()
    } }
    Box(Modifier.onGloballyPositioned {
        val next = it.boundsInWindow().translate(view.paperWindowOffset())
        if (bounds.size != next.size && bounds != Rect.Zero && life.meshEnabled) { requestRecord = true }
        bounds = next
    }.drawWithContent {
        val p = life.progress
        if (requestRecord && !captureInProgress && size.width > 0 && size.height > 0 && life.meshEnabled) {
            val ratio = min(1f, min(PullOutConfig.MAX_TEXTURE_SIDE / max(size.width, size.height),
                sqrt(PullOutConfig.MAX_TEXTURE_PIXELS / (size.width * size.height))))
            recordedRatio = ratio
            layer.record(size = IntSize((size.width * ratio).roundToInt().coerceAtLeast(1), (size.height * ratio).roundToInt().coerceAtLeast(1))) {
                withTransform({ scale(ratio, ratio, Offset.Zero) }) { this@drawWithContent.drawContent() }
            }
            requestRecord = false; captureInProgress = true; recorded++
        }
        if (p == 1f) { drawContent(); return@drawWithContent }
        val texture = pixels
        val canvas = drawContext.canvas.nativeCanvas
        if (reduce || !life.meshEnabled || !canvas.isHardwareAccelerated) {
            if (ownsHide) revealSource()
            PaperRenderStats.fallbackFrames++
            val saved = canvas.save()
            val scale = .96f + .04f * p.coerceIn(0f, 1f)
            canvas.scale(scale, scale, size.width / 2f, size.height / 2f)
            canvas.saveLayerAlpha(0f, 0f, size.width, size.height, (255 * p.coerceIn(0f, 1f)).toInt())
            drawContent(); canvas.restoreToCount(saved)
            return@drawWithContent
        }
        if (texture == null || !life.ready) {
            if (p > 0f) drawContent()
            return@drawWithContent
        }
        val source = origin ?: return@drawWithContent
        val anchor = source.bounds.translate(-bounds.topLeft - texture.crop)
        val width = texture.card.width / texture.scale; val height = texture.card.height / texture.scale
        val start = System.nanoTime()
        fillPaperMesh(vertices, cardColors, sourceColors, width, height, anchor, p, density)
        PaperRenderStats.maxMeshBuildNanos = max(PaperRenderStats.maxMeshBuildNanos, System.nanoTime() - start)
        val saved = canvas.save(); canvas.translate(texture.crop.x, texture.crop.y)
        try {
            texture.shadow?.takeIf { life.shadowEnabled }?.let { shadow ->
                val dx = texture.shadowOffset[0] / texture.scale; val dy = texture.shadowOffset[1] / texture.scale
                val dw = (shadow.width - texture.card.width) / texture.scale
                val dh = (shadow.height - texture.card.height) / texture.scale
                for (row in 0..PullOutConfig.ROWS) for (col in 0..PullOutConfig.COLUMNS) {
                    val index = (row * (PullOutConfig.COLUMNS + 1) + col) * 2
                    shadowVertices[index] = vertices[index] + dx + col.toFloat() / PullOutConfig.COLUMNS * dw
                    shadowVertices[index + 1] = vertices[index + 1] + dy + row.toFloat() / PullOutConfig.ROWS * dh + 6f * density
                }
                paint.color = android.graphics.Color.BLACK; paint.alpha = (55 * p.coerceIn(0f, 1f)).toInt()
                canvas.drawBitmapMesh(shadow, PullOutConfig.COLUMNS, PullOutConfig.ROWS, shadowVertices, 0, null, 0, paint)
            }
            paint.color = android.graphics.Color.WHITE; paint.alpha = 255
            canvas.drawBitmapMesh(texture.card, PullOutConfig.COLUMNS, PullOutConfig.ROWS, vertices, 0, cardColors, 0, paint)
            button?.let { canvas.drawBitmapMesh(it, PullOutConfig.COLUMNS, PullOutConfig.ROWS, vertices, 0, sourceColors, 0, paint) }
            PaperRenderStats.gpuFrames++
        } catch (_: Exception) { life.meshEnabled = false; revealSource(); drawContent() }
        finally { canvas.restoreToCount(saved) }
    }) {
        content()
        // A frozen paper is visual only. Its final-layout buttons cannot fire under a moving sheet.
        val texture = pixels
        if (life.inputBlocked && texture != null && life.meshEnabled) {
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(texture.crop.x.roundToInt(), texture.crop.y.roundToInt()) }
                .size((texture.card.width / texture.scale / density).dp, (texture.card.height / texture.scale / density).dp)
                .pointerInput(life) {
                    awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } }
                })
        }
    }
}
