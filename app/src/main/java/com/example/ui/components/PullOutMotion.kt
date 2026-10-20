package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint as NativePaint
import android.graphics.Color as NativeColor
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Indication
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.*

/** Native Android bitmap mesh; every overlay uses these exact parameters. */
object PullOutConfig {
    const val COLUMNS = 26
    const val ROWS = 150
    const val NECK = .30f
    const val DECAY = 90f
    const val BEND = 16f
    const val ARCH = 10f
    const val FLUTTER = 4f
    const val OPEN_K = 140f
    const val OPEN_D = 15f
    const val CLOSE_K = 260f
    const val CLOSE_D = 34f
    const val SCRIM = .35f
    const val DISMISS_DISTANCE = 64f
    const val DRAG_TRAVEL = 300f
    fun spring(opening: Boolean): SpringSpec<Float> {
        val k = if (opening) OPEN_K else CLOSE_K
        val d = if (opening) OPEN_D else CLOSE_D
        return spring(stiffness = k, dampingRatio = d / (2f * sqrt(k)), visibilityThreshold = .0005f)
    }
}

internal class PullOutSource {
    var bounds = Rect.Zero
    var hidden by mutableStateOf(false)
    var bitmap: Bitmap? = null
    var capture: (suspend () -> Bitmap?)? = null
    var leases = 0
    var disposed = false
}
internal object PullOutSources { var latest: PullOutSource? = null }

/** Bare reading-surface taps have no button to hide; the paper still emerges from the actual contact. */
internal fun recordPaperTouch(pointInWindow: Offset, density: Float) {
    val radius = 12f * density
    PullOutSources.latest = PullOutSource().apply {
        bounds = Rect(pointInWindow - Offset(radius, radius), pointInWindow + Offset(radius, radius))
        disposed = true
    }
}

private fun androidx.compose.ui.graphics.layer.GraphicsLayer.clearPaperRecording() {
    record(androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr,
        androidx.compose.ui.unit.IntSize(1, 1)) { }
}

/** Captures the actual button, including its text and alpha corners. No synthetic button painting. */
fun Modifier.pullOutAnchor(): Modifier = composed {
    val source = remember { PullOutSource() }
    val layer = rememberGraphicsLayer()
    var captureRequested by remember { mutableStateOf(false) }
    var hasRecording by remember { mutableStateOf(false) }
    val captureLock = remember { Mutex() }
    DisposableEffect(source) { onDispose {
        source.disposed = true; source.capture = null
        if (source.leases == 0 && PullOutSources.latest !== source) { source.bitmap?.recycle(); source.bitmap = null }
    } }
    SideEffect {
        source.capture = {
            captureLock.withLock {
                try {
                    source.bitmap ?: if (hasRecording) {
                        layer.toImageBitmap().asAndroidBitmap().let { original ->
                            original.copy(Bitmap.Config.ARGB_8888, false).also { source.bitmap = it }
                        }
                    } else null
                } catch (_: Exception) { null }
                finally {
                    if (hasRecording) { layer.clearPaperRecording(); hasRecording = false }
                }
            }
        }
    }
    // Release display-list references even when this button does not open an overlay.
    LaunchedEffect(hasRecording) { if (hasRecording) source.capture?.invoke() }
    this.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        if (source.bounds.size != bounds.size) { source.bitmap?.recycle(); source.bitmap = null }
        source.bounds = bounds
    }.pointerInput(source) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed && !it.previousPressed }) {
                    PullOutSources.latest?.takeIf { it !== source && it.disposed && it.leases == 0 }?.let { it.bitmap?.recycle(); it.bitmap = null }
                    PullOutSources.latest = source
                    captureRequested = true
                    // Registration is cheap. Read pixels only when an overlay actually opens.
                }
            }
        }
    }.drawWithContent {
        // Snapshot layers must stay detached from the live RenderNode graph. Glass content can
        // sample an ancestor backdrop; drawing the snapshot back into that ancestor creates a cycle.
        if (captureRequested) { layer.record { this@drawWithContent.drawContent() }; hasRecording = true; captureRequested = false }
        if (!source.hidden) drawContent()
    }
}

/** Same gesture semantics as Foundation, with automatic source geometry and texture capture. */
fun Modifier.pullOutClickable(
    enabled: Boolean = true, onClickLabel: String? = null, role: Role? = null, onClick: () -> Unit,
): Modifier = composed { pullOutAnchor().clickable(enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick) }

fun Modifier.pullOutClickable(
    interactionSource: MutableInteractionSource?, indication: Indication?, enabled: Boolean = true,
    onClickLabel: String? = null, role: Role? = null, onClick: () -> Unit,
): Modifier = composed { pullOutAnchor().clickable(interactionSource = interactionSource, indication = indication,
    enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick) }

/** Smooth hinge integral: the released length has unit slope; only the material at the slit compresses. */
internal object PaperMesh {
    private fun softplus(x: Float, softness: Float): Float = when {
        x / softness > 20f -> x
        x / softness < -20f -> 0f
        else -> softness * ln(1f + exp(x / softness))
    }
    fun vertex(u: Float, v: Float, width: Float, height: Float, source: Rect, p: Float, density: Float): Offset {
        if (p <= 0f) return Offset(source.left + u * source.width, source.top + v * source.height)
        if (p == 1f) return Offset(u * width, v * height)
        val q = p.coerceIn(0f, 1f)
        val wave = sin(PI * q).toFloat()
        val slit = source.center.y.coerceIn(0f, height)
        val finalY = v * height
        val delta = finalY - slit
        val far = max(slit, height - slit).coerceAtLeast(1f)
        val softness = 12f * density
        val lock = (1f - q) * (far + softness * 10f) - q * softness * 10f
        val released = softplus(abs(delta) - lock, softness) - softplus(-lock, softness)
        val compressedScale = source.height / height.coerceAtLeast(1f)
        val mapped = released + (abs(delta) - released) * compressedScale
        val free = (released / far).coerceIn(0f, 1f)
        val y0 = source.center.y + (slit / height.coerceAtLeast(1f) - .5f) * source.height * (1f - q) + (slit - source.center.y) * q
        val neck = 1f - (1f - PullOutConfig.NECK) * wave.pow(.7f) * exp(-abs(mapped) / (PullOutConfig.DECAY * density))
        val unfold = q + (1f - q) * (1f - exp(-released / (24f * density)))
        val openWidth = source.width + (width - source.width) * unfold
        val centerX = source.center.x + (width / 2 - source.center.x) * q
        val bend = PullOutConfig.BEND * density * wave * free * free
        val flutter = PullOutConfig.FLUTTER * density * wave * free * sin(v * 4f + q * 5f)
        val arch = PullOutConfig.ARCH * density * wave * free * (1f - (2 * u - 1).pow(2))
        val overshoot = (p - 1f).coerceAtLeast(0f)
        return Offset(centerX + (u - .5f) * openWidth * neck + bend + flutter,
            y0 + delta.sign * (mapped + arch) - overshoot * height * .10f)
    }
    fun blend(v: Float, height: Float, slit: Float, p: Float): Float {
        if (p <= 0) return 0f
        if (p >= 1) return 1f
        val far = abs(v * height - slit) / height.coerceAtLeast(1f)
        val t = (p * 1.7f + far * sin(PI * p).toFloat() * .8f - .3f).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}

private class PaperRecordingState {
    var size = androidx.compose.ui.geometry.Size.Zero
    var flat = false
}

/** Content remains live at rest; only the transient deformation is rasterized and meshed. */
@Composable
fun PullOutSurface(progress: Float, modifier: Modifier = Modifier, onReady: () -> Unit = {}, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val source = remember { PullOutSources.latest }
    val density = LocalDensity.current.density
    val reduce = com.example.ui.feedback.LocalReduceMotion.current || LocalRenderQuality.current.minimal
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var texture by remember { mutableStateOf<Bitmap?>(null) }
    var button by remember { mutableStateOf<Bitmap?>(null) }
    var recorded by remember { mutableStateOf(false) }
    var recordingGeneration by remember { mutableIntStateOf(0) }
    val recording = remember { PaperRecordingState() }
    var failed by remember { mutableStateOf(false) }
    var cropOrigin by remember { mutableStateOf(Offset.Zero) }
    val vertices = remember { FloatArray((PullOutConfig.COLUMNS + 1) * (PullOutConfig.ROWS + 1) * 2) }
    val cardColors = remember { IntArray((PullOutConfig.COLUMNS + 1) * (PullOutConfig.ROWS + 1)) }
    val sourceColors = remember { IntArray(cardColors.size) }
    val paint = remember { NativePaint(NativePaint.ANTI_ALIAS_FLAG or NativePaint.FILTER_BITMAP_FLAG) }
    var scratch by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(recordingGeneration, bounds.size) {
        if (reduce || source == null) { onReady(); return@LaunchedEffect }
        if (!recorded || bounds.width <= 0 || bounds.height <= 0) return@LaunchedEffect
        try {
            val copy = try {
                layer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                // The mesh owns pixels only, never a live display list with pooled child layers.
                layer.clearPaperRecording()
            }
            // Full-screen layout wrappers often contain one small panel. Crop transparent margins,
            // so the physical paper is the panel itself rather than an invisible screen-sized sheet.
            val row = IntArray(copy.width)
            var left = copy.width; var right = -1; var top = copy.height; var bottom = -1
            for (y in 0 until copy.height) {
                copy.getPixels(row, 0, copy.width, 0, y, copy.width, 1)
                for (x in row.indices) if ((row[x] ushr 24) > 4) {
                    left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
                }
            }
            texture?.recycle()
            if (right >= left && bottom >= top) {
                texture = Bitmap.createBitmap(copy, left, top, right - left + 1, bottom - top + 1)
                if (texture !== copy) copy.recycle()
                cropOrigin = Offset(left.toFloat(), top.toFloat())
            } else { texture = copy; cropOrigin = Offset.Zero }
            button = source?.bitmap ?: source?.capture?.invoke()
            // Inline expansions may live inside their trigger; hiding that ancestor would hide the panel.
            val sourceContainsPanel = source != null && source.bounds.contains(bounds.topLeft) &&
                source.bounds.contains(bounds.bottomRight - Offset(.01f, .01f))
            if (source != null && !sourceContainsPanel) source.hidden = true
        } catch (_: Exception) { failed = true }
        catch (_: OutOfMemoryError) { texture?.recycle(); texture = null; failed = true }
        onReady()
    }
    DisposableEffect(source) {
        source?.let { it.leases++ }
        onDispose {
            source?.let { it.leases--; if (it.leases == 0) { it.hidden = false; if (it.disposed && PullOutSources.latest !== it) { it.bitmap?.recycle(); it.bitmap = null } } }
            texture?.recycle(); scratch?.recycle()
        }
    }
    Box(modifier.onGloballyPositioned { bounds = it.boundsInWindow() }.drawWithContent {
        val p = progress
        val flat = p >= .9999f && p <= 1.0001f
        if (!reduce && source != null && (!recorded || recording.size != size || recording.flat && !flat)) {
            layer.record { this@drawWithContent.drawContent() }
            recording.size = size; recorded = true; recordingGeneration++
        }
        recording.flat = flat
        if (flat) { drawContent(); return@drawWithContent }
        val bitmap = texture
        if (reduce || failed || source == null) {
            // Explicit unsupported / accessibility fallback; the mesh is never approximated with strips.
            val scale = .86f + .14f * p.coerceIn(0f, 1f)
            val canvas = drawContext.canvas.nativeCanvas
            val saved = canvas.save()
            canvas.scale(scale, scale, size.width / 2f, size.height / 2f)
            canvas.saveLayerAlpha(0f, 0f, size.width, size.height, (255 * p.coerceIn(0f, 1f)).toInt())
            drawContent()
            canvas.restoreToCount(saved)
            return@drawWithContent
        }
        if (bitmap == null) return@drawWithContent
        try {
            val anchor = source.bounds.translate(-bounds.topLeft - cropOrigin)
            val paperWidth = bitmap.width.toFloat()
            val paperHeight = bitmap.height.toFloat()
            for (row in 0..PullOutConfig.ROWS) {
                val v = row.toFloat() / PullOutConfig.ROWS
                val blend = PaperMesh.blend(v, paperHeight, anchor.center.y.coerceIn(0f, paperHeight), p)
                val compressed = (1f - blend) * sin(PI * p.coerceIn(0f, 1f)).toFloat()
                for (col in 0..PullOutConfig.COLUMNS) {
                    val u = col.toFloat() / PullOutConfig.COLUMNS
                    val index = row * (PullOutConfig.COLUMNS + 1) + col
                    val point = PaperMesh.vertex(u, v, paperWidth, paperHeight, anchor, p, density)
                    vertices[index * 2] = point.x; vertices[index * 2 + 1] = point.y
                    val shade = (255 * (1f - .035f * compressed * (1f + sin(v * 9f + u * 3f) * sin(v * 17f - u * 2f)))).toInt().coerceIn(0, 255)
                    cardColors[index] = NativeColor.argb((blend * 255).toInt(), shade, shade, shade)
                    sourceColors[index] = NativeColor.argb(((1f - blend) * 255).toInt(), 255, 255, 255)
                }
            }
            // Supersampling is essential: drawBitmapMesh itself ignores Paint's antialias bit.
            val rasterScale = min(2f / density, sqrt(3_000_000f / (paperWidth * paperHeight).coerceAtLeast(1f)))
            val pad = 40f * density
            val left = min(-pad, anchor.left - pad); val top = min(-pad, anchor.top - pad)
            val right = max(paperWidth + pad, anchor.right + pad); val bottom = max(paperHeight + pad, anchor.bottom + pad)
            val w = ((right - left) * rasterScale).roundToInt().coerceAtLeast(1)
            val h = ((bottom - top) * rasterScale).roundToInt().coerceAtLeast(1)
            var buffer = scratch
            if (buffer == null || buffer.width != w || buffer.height != h) {
                buffer?.recycle(); buffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); scratch = buffer
            }
            buffer.eraseColor(NativeColor.TRANSPARENT)
            val canvas = NativeCanvas(buffer); canvas.scale(rasterScale, rasterScale); canvas.translate(-left, -top)
            paint.alpha = 255
            paint.setShadowLayer(14f * density, 0f, 8f * density, NativeColor.argb((76 * p.coerceIn(0f, 1f)).toInt(), 0, 0, 0))
            canvas.drawBitmapMesh(bitmap, PullOutConfig.COLUMNS, PullOutConfig.ROWS, vertices, 0, cardColors, 0, paint)
            paint.clearShadowLayer()
            button?.takeUnless { it.isRecycled }?.let { canvas.drawBitmapMesh(it, PullOutConfig.COLUMNS, PullOutConfig.ROWS, vertices, 0, sourceColors, 0, paint) }
            drawContext.canvas.nativeCanvas.drawBitmap(buffer, null, android.graphics.RectF(left + cropOrigin.x, top + cropOrigin.y, right + cropOrigin.x, bottom + cropOrigin.y), paint)
        } catch (_: Exception) { failed = true; drawContent() }
        catch (_: OutOfMemoryError) { scratch?.recycle(); scratch = null; failed = true; drawContent() }
    }) { content() }
}

/** Exact spring integration retains both displacement and velocity across target changes. */
internal class PaperSpring {
    var position = 0f
    var velocity = 0f
    fun advance(target: Float, opening: Boolean, seconds: Float): Boolean {
        val dt = seconds.coerceIn(0f, .033f)
        val k = if (opening) PullOutConfig.OPEN_K else PullOutConfig.CLOSE_K
        val halfD = (if (opening) PullOutConfig.OPEN_D else PullOutConfig.CLOSE_D) / 2f
        val x = position - target
        val discriminant = halfD * halfD - k
        val next: Float
        if (discriminant < 0f) {
            val w = sqrt(-discriminant); val a = x; val b = (velocity + halfD * x) / w
            val decay = exp(-halfD * dt); val c = cos(w * dt); val sn = sin(w * dt)
            val z = a * c + b * sn
            next = decay * z
            velocity = decay * ((-a * w * sn + b * w * c) - halfD * z)
        } else if (discriminant > .00001f) {
            val w = sqrt(discriminant); val r1 = -halfD + w; val r2 = -halfD - w
            val a = (velocity - r2 * x) / (r1 - r2); val b = x - a
            val e1 = exp(r1 * dt); val e2 = exp(r2 * dt)
            next = a * e1 + b * e2; velocity = r1 * a * e1 + r2 * b * e2
        } else {
            val b = velocity + halfD * x; val z = x + b * dt; val decay = exp(-halfD * dt)
            next = decay * z; velocity = decay * (b - halfD * z)
        }
        position = target + next
        if (abs(next) < .0005f && abs(velocity) < .03125f) { position = target; velocity = 0f; return false }
        return true
    }
}

/** Registered lifecycle keeps the complete exit composed; physics starts only after the texture is ready. */
@Composable
fun PullOutVisibility(
    visibleState: MutableTransitionState<Boolean>, modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(visibleState = visibleState, modifier = modifier, enter = EnterTransition.None, exit = ExitTransition.None) {
        val reduce = com.example.ui.feedback.LocalReduceMotion.current
        val minimal = LocalRenderQuality.current.minimal
        var ready by remember { mutableStateOf(PullOutSources.latest == null || reduce || minimal) }
        val visibleTarget = LocalPaperVisibleTarget.current
        // This registered child retains AnimatedVisibility during closing. Its target does not depend
        // on bitmap readiness: same-state target updates can otherwise complete at progress zero.
        @Suppress("UNUSED_VARIABLE")
        val lifecycle = transition.animateFloat(transitionSpec = {
            if (reduce) snap() else PullOutConfig.spring(targetState == EnterExitState.Visible)
        }, label = "pullOutLifecycle") { if (it == EnterExitState.Visible) 1f else 0f }
        val physics = remember { PaperSpring() }
        var p by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(ready, visibleState.targetState, visibleTarget, reduce) {
            if (!ready) return@LaunchedEffect
            val opening = visibleState.targetState
            val target = if (opening) visibleTarget else 0f
            if (reduce) { physics.position = target; physics.velocity = 0f; p = target; return@LaunchedEffect }
            var last = withFrameNanos { it }
            do {
                val now = withFrameNanos { it }
                val moving = physics.advance(target, opening, ((now - last) / 1e9f).coerceIn(0f, .033f))
                last = now; p = physics.position
            } while (moving)
        }
        PullOutSurface(p, onReady = { ready = true }) { content() }
    }
}

/** Compatibility for existing expanded cards and reader chrome. Old transition parameters are ignored centrally. */
@Composable
fun PullOutVisibility(
    visible: Boolean, modifier: Modifier = Modifier, enter: EnterTransition = EnterTransition.None,
    exit: ExitTransition = ExitTransition.None, label: String = "pullOut", content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    PullOutVisibility(state, modifier, content)
}

@Composable
fun PullOutVisibility(
    visibleState: MutableTransitionState<Boolean>, modifier: Modifier = Modifier,
    enter: EnterTransition, exit: ExitTransition, label: String = "pullOut", content: @Composable AnimatedVisibilityScope.() -> Unit,
) = PullOutVisibility(visibleState, modifier, content)
