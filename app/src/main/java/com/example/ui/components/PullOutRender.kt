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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import kotlin.math.*

/** Diagnostics count only animation draws; useful for targeted device performance checks. */
// Limit GPU readbacks, not animation frames. Glass-heavy windows otherwise submit two
// simultaneous readbacks, then allocate/copy both full images at the same time.
private val paperReadbacks = Semaphore(1)

internal object PaperRenderStats {
    var gpuFrames = 0
    var fallbackFrames = 0
    var maxMeshBuildNanos = 0L
    var snapshots = 0
    var captureFailures = 0
    var captureRetries = 0
    var buttonCaptures = 0
    var maxCaptureNanos = 0L
    var blendLayers = 0
    var texturePasses = 0
    var recordings = 0
    var capturePhase = "idle"
    var asyncSnapshots = 0
    var legacySnapshots = 0
    var readbackFallbacks = 0
    fun reset() { gpuFrames = 0; fallbackFrames = 0; maxMeshBuildNanos = 0; snapshots = 0; captureFailures = 0; captureRetries = 0; buttonCaptures = 0; maxCaptureNanos = 0; blendLayers = 0; texturePasses = 0; recordings = 0; capturePhase = "idle"; asyncSnapshots = 0; legacySnapshots = 0; readbackFallbacks = 0 }
}
private suspend fun readPaperLayer(layer: androidx.compose.ui.graphics.layer.GraphicsLayer): Bitmap {
    try { return paperReadbacks.withPermit { capturePaperBitmap(layer) } }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        // A transient surface/readback race must not turn this or subsequent papers
        // into a generic transition. Retry the same retained display list next frame.
        PaperRenderStats.captureRetries++
        withFrameNanos { }
        return paperReadbacks.withPermit { capturePaperBitmap(layer) }
    }
}
private data class PaperPixels(val card: Bitmap, val shadow: Bitmap?, val shadowOffset: IntArray,
    val scale: Float, val crop: Offset)
private val paperColumns = FloatArray(PullOutConfig.COLUMNS + 1) { it.toFloat() / PullOutConfig.COLUMNS }
private val paperArch = FloatArray(paperColumns.size) { val u = 2f * paperColumns[it] - 1f; 1f - u * u }
private val paperAddBlend = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
private fun Bitmap.paperSoftwareBitmap(): Bitmap =
    if (Build.VERSION.SDK_INT >= 26 && config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this

private fun android.view.View.paperWindow(): Window? {
    (parent as? DialogWindowProvider)?.window?.let { return it }
    var current = context
    while (current is ContextWrapper) { if (current is Activity) return current.window; current = current.baseContext }
    return null
}
/** PixelCopy is requested only by a real opening, never by ordinary button presses. */
private suspend fun capturePaperButton(source: PullOutSource, refresh: Boolean = false): Bitmap? =
    withContext(Dispatchers.Main.immediate) { capturePaperButtonOnMain(source, refresh) }
private suspend fun capturePaperButtonOnMain(source: PullOutSource, refresh: Boolean): Bitmap? {
    if (source.hidden && !refresh || source.disposed) source.preview?.get()?.let { return it }
    if (Build.VERSION.SDK_INT < 26 || source.virtual) return null
    // Capture the actual source display list before window blur, with its real alpha
    // corners. This avoids a screen PixelCopy racing the new Dialog and press feedback.
    if (!source.disposed && source.releaseCapture != null) {
        val request = CompletableDeferred<androidx.compose.ui.graphics.layer.GraphicsLayer>()
        try {
            source.capture = request
            val captured = withTimeoutOrNull(1200) { request.await() }
            val image = captured?.let { readPaperLayer(it) }
            if (image != null) {
                val bitmap = withContext(Dispatchers.Default) { image.paperSoftwareBitmap().apply { prepareToDraw() } }
                source.preview = java.lang.ref.WeakReference(bitmap); PaperRenderStats.buttonCaptures++
                return bitmap
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The window PixelCopy below is an independent retry. */ }
        finally { source.releaseCapture?.invoke() }
    }
    // A hidden source has no pixels in the window. PixelCopy here would capture
    // its empty background and break the closing seam; retain its last good image.
    if (source.hidden) return source.preview?.get()
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
    // Scan inward from each edge. O(perimeter) for the usual filled panel instead
    // of walking every pixel of its content and crossing JNI once for each row.
    val data = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(data, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    fun rowPresent(y: Int): Boolean { val start = y * bitmap.width; for (x in 0 until bitmap.width) if ((data[start + x] ushr 24) > 4) return true; return false }
    fun colPresent(x: Int, top: Int, bottom: Int): Boolean { for (y in top..bottom) if ((data[y * bitmap.width + x] ushr 24) > 4) return true; return false }
    var top = 0; var bottom = bitmap.height - 1; var left = 0; var right = bitmap.width - 1
    while (top <= bottom && !rowPresent(top)) top++
    if (top > bottom) return PaperPixels(bitmap, null, intArrayOf(0, 0), ratio, Offset.Zero)
    while (bottom > top && !rowPresent(bottom)) bottom--
    while (left < right && !colPresent(left, top, bottom)) left++
    while (right > left && !colPresent(right, top, bottom)) right--
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
    width: Float, height: Float, source: Rect, p: Float, density: Float): Int {
    var visibleTextures = 0 // bit 0: card contributes; bit 1: source contributes.
    val q = p.coerceIn(0f, 1f); val wave = sin(PI * q).toFloat()
    val neckWave = (1f - PullOutConfig.NECK) * wave.pow(.7f)
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
        val neck = 1f - neckWave * exp(-abs(mapped) / (PullOutConfig.DECAY * density))
        val unfold = q + (1f - q) * (1f - exp(-released / (24f * density)))
        val openWidth = source.width + (width - source.width) * unfold
        val bend = PullOutConfig.BEND * density * wave * free * free
        val flutter = PullOutConfig.FLUTTER * density * wave * free * sin(v * 4f + q * 5f)
        val t = (p * 1.7f + abs(delta) / height.coerceAtLeast(1f) * wave * .8f - .3f).coerceIn(0f, 1f)
        val blend = if (p <= 0f) 0f else if (p >= 1f) 1f else t * t * (3f - 2f * t)
        val shade = (255 * (1f - .035f * (1f - blend) * wave * (1f + sin(v * 9f)))).toInt().coerceIn(0, 255)
        val card = android.graphics.Color.argb((blend * 255).toInt(), shade, shade, shade)
        val button = android.graphics.Color.argb(((1f - blend) * 255).toInt(), 255, 255, 255)
        if (card ushr 24 != 0) visibleTextures = visibleTextures or 1
        if (button ushr 24 != 0) visibleTextures = visibleTextures or 2
        val rowWidth = openWidth * neck
        val rowCenter = centerX + bend + flutter
        val rowY = y0 + delta.sign * mapped - (p - 1f).coerceAtLeast(0f) * height * .10f
        val rowArch = delta.sign * PullOutConfig.ARCH * density * wave * free
        val offset = row * (PullOutConfig.COLUMNS + 1)
        for (col in 0..PullOutConfig.COLUMNS) {
            val u = paperColumns[col]
            val index = offset + col
            // Keep the hot loop primitive. Packing each point into Compose Offset
            // and unpacking it again added thousands of calls per frame before ART
            // warmed up, despite Offset itself being an inline value class.
            when {
                p <= 0f -> {
                    vertices[index * 2] = source.left + u * source.width
                    vertices[index * 2 + 1] = source.top + v * source.height
                }
                p == 1f -> {
                    vertices[index * 2] = u * width
                    vertices[index * 2 + 1] = v * height
                }
                else -> {
                    vertices[index * 2] = rowCenter + (u - .5f) * rowWidth
                    vertices[index * 2 + 1] = rowY + rowArch * paperArch[col]
                }
            }
            cardColors[index] = card; sourceColors[index] = button
        }
    }
    return visibleTextures
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
    fun isInlineSource() = origin?.view?.get()?.windowToken == view.windowToken
    fun sourceBelongsToSurface(): Boolean {
        if (!isInlineSource() || origin == null) return false
        val sourceBounds = origin.bounds
        fun contains(outer: Rect, inner: Rect) = outer.contains(inner.topLeft) && outer.contains(inner.bottomRight - Offset(.01f, .01f))
        return contains(bounds, sourceBounds) || contains(sourceBounds, bounds)
    }
    var requestRecord by remember { mutableStateOf(life.meshEnabled) }
    var recorded by remember { mutableIntStateOf(0) }
    var captureInProgress by remember { mutableStateOf(false) }
    var recordedRatio by remember { mutableFloatStateOf(1f) }
    val vertices = remember { FloatArray((PullOutConfig.COLUMNS + 1) * (PullOutConfig.ROWS + 1) * 2) }
    val shadowVertices = remember { FloatArray(vertices.size) }
    val cardColors = remember { IntArray(vertices.size / 2) }
    val sourceColors = remember { IntArray(vertices.size / 2) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val blendBounds = remember { android.graphics.RectF() }
    val buttonReady = remember(life.captureRevision) { CompletableDeferred<Unit>() }
    LaunchedEffect(life.captureRevision) {
        try { if (life.meshEnabled) origin?.let { capturePaperButton(it, refresh = life.captureRevision > 0) }?.let { button = it } }
        finally { buttonReady.complete(Unit) }
    }
    LaunchedEffect(life.captureRevision) { if (life.meshEnabled) requestRecord = true }
    LaunchedEffect(recorded) {
      // Recomposer/test continuation interceptors can resume after a worker or
      // semaphore on that worker. Every display-list operation, including cleanup,
      // must have an explicit UI dispatcher; only pixels leave this context.
      withContext(Dispatchers.Main.immediate) {
        if (!life.meshEnabled) { life.ready = true; return@withContext }
        if (recorded == 0) return@withContext
        try {
            PaperRenderStats.capturePhase = "await-layout"
            // Global-position callbacks can trail the first recording. Returning
            // here would strand captureInProgress forever with no new effect key.
            snapshotFlow { bounds.size }.first { it.width > 0 && it.height > 0 }
            PaperRenderStats.capturePhase = "read-card"
            val started = System.nanoTime()
            val image = readPaperLayer(layer)
            val ratio = recordedRatio
            val result = withContext(Dispatchers.Default) { cropPaper(image.paperSoftwareBitmap(), ratio, density) }
            pixels = result; PaperRenderStats.snapshots++
            PaperRenderStats.maxCaptureNanos = max(PaperRenderStats.maxCaptureNanos, System.nanoTime() - started)
            PaperRenderStats.capturePhase = "await-button"
            buttonReady.await()
            PaperRenderStats.capturePhase = "ready"
            if (button != null && origin != null && !sourceBelongsToSurface()) { if (!ownsHide) { origin.hide(); ownsHide = true } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PaperRenderStats.capturePhase = "failed"; PaperRenderStats.captureFailures++; if (pixels == null) life.meshEnabled = false; revealSource() }
        catch (_: OutOfMemoryError) { pixels = null; life.meshEnabled = false; revealSource() }
        finally {
            // A recorded child graph never outlives its pixel conversion, even on cancellation.
            layer.record(Density(1f), LayoutDirection.Ltr, IntSize(1, 1)) { }
            captureInProgress = false
        }
        life.ready = true
      }
    }
    DisposableEffect(Unit) { onDispose {
        // HWUI may still reference the previous frame. Let its bitmap references retire naturally.
        revealSource()
    } }
    LaunchedEffect(life.inputBlocked) {
        // An inline expansion's toggle must remain visible and usable after it
        // settles. A separate modal window continues to own its hidden trigger.
        if (!life.inputBlocked && isInlineSource()) revealSource()
    }
    Box(Modifier.onGloballyPositioned {
        val next = it.boundsInWindow().translate(view.paperWindowOffset())
        if (bounds.size != next.size && bounds != Rect.Zero && life.meshEnabled) { requestRecord = true }
        bounds = next
    }.drawWithContent {
        val p = life.progress
        val canvas = drawContext.canvas.nativeCanvas
        if (!canvas.isHardwareAccelerated && life.meshEnabled) {
            // Detect a genuinely unsupported software window from its actual Canvas,
            // never from a not-yet-attached Dialog View (which also reports false).
            life.meshEnabled = false; life.ready = true; requestRecord = false
            revealSource()
        }
        if (requestRecord && !captureInProgress && size.width > 0 && size.height > 0 && life.meshEnabled) {
            val ratio = min(1f, min(PullOutConfig.MAX_TEXTURE_SIDE / max(size.width, size.height),
                sqrt(PullOutConfig.MAX_TEXTURE_PIXELS / (size.width * size.height))))
            recordedRatio = ratio
            layer.record(size = IntSize((size.width * ratio).roundToInt().coerceAtLeast(1), (size.height * ratio).roundToInt().coerceAtLeast(1))) {
                withTransform({ scale(ratio, ratio, Offset.Zero) }) { this@drawWithContent.drawContent() }
            }
            requestRecord = false; captureInProgress = true; recorded++; PaperRenderStats.recordings++
        }
        if (p == 1f) { drawContent(); return@drawWithContent }
        val texture = pixels
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
        if (source.virtual && source.bounds.isEmpty) {
            val center = Offset(bounds.center.x, bounds.top + min(bounds.height / 2f, 24f * density))
            source.bounds = Rect(center - Offset(24f * density, 12f * density), center + Offset(24f * density, 12f * density))
        }
        val anchor = source.bounds.translate(-bounds.topLeft - texture.crop)
        val width = texture.card.width / texture.scale; val height = texture.card.height / texture.scale
        val start = System.nanoTime()
        val visibleTextures = fillPaperMesh(vertices, cardColors, sourceColors, width, height, anchor, p, density)
        PaperRenderStats.maxMeshBuildNanos = max(PaperRenderStats.maxMeshBuildNanos, System.nanoTime() - start)
        val saved = canvas.save(); canvas.translate(texture.crop.x, texture.crop.y)
        try {
            texture.shadow?.let { shadow ->
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
            val sourceBitmap = button
            if (sourceBitmap == null || visibleTextures == 1) {
                canvas.drawBitmapMesh(texture.card, PullOutConfig.COLUMNS, PullOutConfig.ROWS, vertices, 0, if (sourceBitmap == null) null else cardColors, 0, paint)
                PaperRenderStats.texturePasses++
            } else if (visibleTextures == 2) {
                canvas.drawBitmapMesh(sourceBitmap, PullOutConfig.COLUMNS, PullOutConfig.ROWS, vertices, 0, sourceColors, 0, paint)
                PaperRenderStats.texturePasses++
            } else {
                // Add two premultiplied, complementary weights. Ordinary source-over
                // accidentally dims the whole paper by 25% halfway through the morph.
                // A fullscreen saveLayer allocates/fills much more than the moving
                // paper. The outer columns bound X; the edge/centre columns bound
                // the symmetric arch in Y. This is the exact same composite.
                blendBounds.set(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
                for (row in 0..PullOutConfig.ROWS) {
                    val first = row * (PullOutConfig.COLUMNS + 1) * 2
                    val last = first + PullOutConfig.COLUMNS * 2
                    val middle = first + (PullOutConfig.COLUMNS / 2) * 2
                    blendBounds.left = min(blendBounds.left, vertices[first])
                    blendBounds.right = max(blendBounds.right, vertices[last])
                    blendBounds.top = min(blendBounds.top, min(vertices[first + 1], vertices[middle + 1]))
                    blendBounds.bottom = max(blendBounds.bottom, max(vertices[first + 1], vertices[middle + 1]))
                }
                blendBounds.inset(-2f, -2f)
                val blendLayer = canvas.saveLayer(blendBounds, null)
                canvas.drawBitmapMesh(texture.card, PullOutConfig.COLUMNS, PullOutConfig.ROWS, vertices, 0, cardColors, 0, paint)
                paint.xfermode = paperAddBlend
                canvas.drawBitmapMesh(sourceBitmap, PullOutConfig.COLUMNS, PullOutConfig.ROWS, vertices, 0, sourceColors, 0, paint)
                canvas.restoreToCount(blendLayer); paint.xfermode = null
                PaperRenderStats.blendLayers++; PaperRenderStats.texturePasses += 2
            }
            PaperRenderStats.gpuFrames++
        } catch (_: Exception) { life.meshEnabled = false; revealSource(); drawContent() }
        finally { paint.xfermode = null; canvas.restoreToCount(saved) }
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
