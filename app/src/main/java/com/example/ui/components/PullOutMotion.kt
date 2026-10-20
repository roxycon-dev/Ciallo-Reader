package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint as NativePaint
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Indication
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.flow.first
import kotlin.math.*

/** One configuration for native GPU paper, its reduced-motion fallback and all modal hosts. */
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
    const val MAX_TEXTURE_PIXELS = 1_000_000f
    const val MAX_TEXTURE_SIDE = 1600f
    const val SOURCE_LIFETIME_MS = 900L
    const val FALLBACK_OPEN_K = 800f
    const val FALLBACK_OPEN_D = 50f
    const val FALLBACK_CLOSE_K = 1000f
    const val FALLBACK_CLOSE_D = 60f
    fun spring(opening: Boolean): SpringSpec<Float> {
        val k = if (opening) OPEN_K else CLOSE_K
        val d = if (opening) OPEN_D else CLOSE_D
        return spring(stiffness = k, dampingRatio = d / (2f * sqrt(k)), visibilityThreshold = .0005f)
    }
}

internal class PullOutSource {
    var bounds = Rect.Zero // Screen coordinates, shared safely across Dialog windows.
    var hidden by mutableStateOf(false)
    var hideOwners = 0
    var preview: WeakReference<android.graphics.Bitmap>? = null
    fun hide() { hideOwners++; hidden = true }
    fun reveal() { hideOwners = (hideOwners - 1).coerceAtLeast(0); hidden = hideOwners > 0 }
    var view: WeakReference<android.view.View>? = null
    var parent: PullOutSource? = null
    var virtual = false
    var disposed = false
    var claimed = false
    var activatedAt = 0L
    var capture by mutableStateOf<kotlinx.coroutines.CompletableDeferred<GraphicsLayer>?>(null)
    var capturedLayer: GraphicsLayer? = null
    var releaseCapture: (() -> Unit)? = null
    fun returnSource(): PullOutSource {
        var origin = this
        repeat(8) {
            if (!origin.disposed || origin.virtual) return origin
            val next = origin.parent ?: return origin
            if (next === this) return origin
            origin = next
        }
        return origin
    }
}
internal object PullOutSources {
    var latest: PullOutSource? = null
    fun activate(source: PullOutSource) { latest = source; source.activatedAt = android.os.SystemClock.uptimeMillis(); source.claimed = false }
    fun claim(): PullOutSource? = latest?.takeIf {
        it.bounds.width > 0 && it.bounds.height > 0 && !it.claimed && android.os.SystemClock.uptimeMillis() - it.activatedAt <= PullOutConfig.SOURCE_LIFETIME_MS
    }?.also { it.claimed = true }?.returnSource()
}
internal val LocalPaperOrigin = compositionLocalOf<PullOutSource?> { null }
internal val LocalPaperOwner = compositionLocalOf<PaperLifecycle?> { null }

internal fun android.view.View.paperWindowOffset(): Offset {
    val screen = IntArray(2); val window = IntArray(2)
    getLocationOnScreen(screen); getLocationInWindow(window)
    return Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
}
internal fun recordPaperTouch(pointInWindow: Offset, density: Float) {
    val radius = 12f * density
    PullOutSources.activate(PullOutSource().apply {
        bounds = Rect(pointInWindow - Offset(radius, radius), pointInWindow + Offset(radius, radius)); virtual = true
    })
}
@Composable
internal fun rememberPaperTrigger(): PullOutSource {
    val origin = LocalPaperOrigin.current
    val source = remember { PullOutSource() }
    SideEffect { if (origin !== source) source.parent = origin }
    DisposableEffect(source) { onDispose { source.disposed = true } }
    return source
}

/** Registration is geometry only. Ordinary clicks never allocate, record or read a bitmap. */
fun Modifier.pullOutAnchor(): Modifier = composed { pullOutAnchor(rememberPaperTrigger()) }
internal fun Modifier.pullOutAnchor(source: PullOutSource): Modifier = composed {
    val view = LocalView.current
    val graphics = LocalGraphicsContext.current
    source.view = WeakReference(view)
    DisposableEffect(source, graphics) {
        source.releaseCapture = {
            source.capturedLayer?.let { graphics.releaseGraphicsLayer(it) }
            source.capturedLayer = null; source.capture = null
        }
        onDispose { source.capture?.cancel(); source.releaseCapture?.invoke(); source.releaseCapture = null }
    }
    onGloballyPositioned { source.bounds = it.boundsInWindow().translate(view.paperWindowOffset()) }
        .pointerInput(source) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed && !it.previousPressed }) PullOutSources.activate(source)
                }
            }
        }.drawWithContent {
            if (!source.hidden || source.capture?.isCompleted == false) {
                val request = source.capture
                if (request != null && !request.isCompleted && size.width > 0 && size.height > 0) {
                    val captured = graphics.createGraphicsLayer()
                    source.capturedLayer = captured
                    val ratio = min(1f, min(PullOutConfig.MAX_TEXTURE_SIDE / max(size.width, size.height),
                        sqrt(PullOutConfig.MAX_TEXTURE_PIXELS / (size.width * size.height))))
                    captured.record(size = IntSize((size.width * ratio).toInt().coerceAtLeast(1), (size.height * ratio).toInt().coerceAtLeast(1))) {
                        withTransform({ scale(ratio, ratio, Offset.Zero) }) { this@drawWithContent.drawContent() }
                    }
                    if (!source.hidden) withTransform({ scale(1f / ratio, 1f / ratio, Offset.Zero) }) { drawLayer(captured) }
                    request.complete(captured)
                } else if (!source.hidden) drawContent()
            }
        }
}
/** Selection and long presses register at activation, including accessibility and held presses. */
fun Modifier.pullOutSelectable(selected: Boolean, interactionSource: MutableInteractionSource?, indication: Indication?,
    enabled: Boolean = true, role: Role? = null, onClick: () -> Unit): Modifier = composed {
    val source = rememberPaperTrigger()
    pullOutAnchor(source).selectable(selected, interactionSource, indication, enabled, role) { PullOutSources.activate(source); onClick() }
}
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.pullOutCombinedClickable(enabled: Boolean = true, onClickLabel: String? = null, role: Role? = null,
    onLongClickLabel: String? = null, onLongClick: (() -> Unit)? = null, onDoubleClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null, indication: Indication? = androidx.compose.foundation.LocalIndication.current, onClick: () -> Unit): Modifier {
    val source = rememberPaperTrigger()
    return pullOutAnchor(source).combinedClickable(interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        indication = indication, enabled = enabled, onClickLabel = onClickLabel, role = role, onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick?.let { { PullOutSources.activate(source); it() } },
        onDoubleClick = onDoubleClick?.let { { PullOutSources.activate(source); it() } }) { PullOutSources.activate(source); onClick() }
}
fun Modifier.pullOutClickable(enabled: Boolean = true, onClickLabel: String? = null, role: Role? = null, onClick: () -> Unit): Modifier = composed {
    val source = rememberPaperTrigger()
    pullOutAnchor(source).clickable(enabled = enabled, onClickLabel = onClickLabel, role = role) { PullOutSources.activate(source); onClick() }
}
fun Modifier.pullOutClickable(interactionSource: MutableInteractionSource?, indication: Indication?, enabled: Boolean = true,
    onClickLabel: String? = null, role: Role? = null, onClick: () -> Unit): Modifier = composed {
    val source = rememberPaperTrigger()
    pullOutAnchor(source).clickable(interactionSource = interactionSource, indication = indication, enabled = enabled,
        onClickLabel = onClickLabel, role = role) { PullOutSources.activate(source); onClick() }
}

/** A single physical clock owns drawing, retention and action completion. No second exit timer. */
internal class PaperLifecycle {
    var progress by mutableFloatStateOf(0f)
    var retained by mutableStateOf(false)
    var closed by mutableStateOf(true)
    var ready by mutableStateOf(false)
    var flat = false
    var inputBlocked by mutableStateOf(true)
    var registered by mutableStateOf(false)
    var captureRevision by mutableIntStateOf(0)
    var hostAttached = false
    var afterDismiss: (() -> Unit)? = null
    var meshEnabled = false
    var origin: PullOutSource? = null
    var carriedByParent = false
    val spring = PaperSpring()
}
private val paperLifecycles = WeakHashMap<MutableTransitionState<Boolean>, PaperLifecycle>()
@Composable
internal fun rememberPaperLifecycle(state: MutableTransitionState<Boolean>): PaperLifecycle = remember(state) {
    paperLifecycles.getOrPut(state) { PaperLifecycle() }
}

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

/** Exact spring integration retains both displacement and velocity across target changes. */
internal class PaperSpring {
    var position = 0f
    var velocity = 0f
    fun advance(target: Float, opening: Boolean, seconds: Float, lightweight: Boolean = false): Boolean {
        val dt = seconds.coerceIn(0f, .033f)
        val k = if (lightweight) { if (opening) PullOutConfig.FALLBACK_OPEN_K else PullOutConfig.FALLBACK_CLOSE_K }
            else if (opening) PullOutConfig.OPEN_K else PullOutConfig.CLOSE_K
        val halfD = (if (lightweight) { if (opening) PullOutConfig.FALLBACK_OPEN_D else PullOutConfig.FALLBACK_CLOSE_D }
            else if (opening) PullOutConfig.OPEN_D else PullOutConfig.CLOSE_D) / 2f
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

/** Draw state is read in the draw phase; animating a paper never recomposes its content each frame. */
@Composable
fun PullOutVisibility(visibleState: MutableTransitionState<Boolean>, modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val life = rememberPaperLifecycle(visibleState)
    val parentPaper = LocalPaperOwner.current
    val reduce = com.example.ui.feedback.LocalReduceMotion.current || LocalRenderQuality.current.minimal
    val parentOrigin = LocalPaperOrigin.current
    val dragTarget = if (parentPaper == null) LocalPaperVisibleTarget.current else 1f
    val currentDragTarget by rememberUpdatedState(dragTarget)
    val target = visibleState.targetState
    var previousTarget by remember { mutableStateOf(target) }
    if (target && !life.retained) {
        life.origin = PullOutSources.claim() ?: parentOrigin ?: PullOutSource().apply { virtual = true }
        life.meshEnabled = !reduce && android.os.Build.VERSION.SDK_INT >= 26
        life.retained = true; life.closed = false; life.ready = !life.meshEnabled
        if (parentPaper != null && !parentPaper.flat) {
            // Initial visible children belong to the parent's one texture. Do not
            // read back/animate a whole stack of nested papers at the same time.
            life.carriedByParent = true; life.meshEnabled = false; life.ready = true
            life.spring.position = 1f; life.progress = 1f; life.flat = true; life.inputBlocked = false
        }
    }
    if (previousTarget != target) {
        previousTarget = target
        life.inputBlocked = true
        if (!target && life.carriedByParent) {
            life.carriedByParent = false
            life.origin = PullOutSources.claim() ?: life.origin
            life.meshEnabled = !reduce && android.os.Build.VERSION.SDK_INT >= 26
        }
        if (!target && life.flat && life.meshEnabled) {
            life.ready = false; life.captureRevision++ // Capture current content, not its opening snapshot.
        }
    }
    if (target && life.flat && dragTarget != 1f && life.meshEnabled) {
        // Data and controls may have changed since opening. The first header drag
        // freezes the current surface once, never an old loading/skeleton frame.
        life.flat = false; life.inputBlocked = true
        life.ready = false; life.captureRevision++
    }
    SideEffect { life.registered = true }
    DisposableEffect(life) { onDispose { life.registered = false } }
    // Keep legacy callers' state observable without letting its transition dispose the real paper.
    AnimatedVisibility(visibleState, enter = EnterTransition.None, exit = ExitTransition.None) { }
    LaunchedEffect(target, life.ready, reduce) {
        if (!target && !life.ready) {
            // An opening dismissed before capture finishes has nothing visible to animate back.
            if (life.spring.position == 0f) { life.retained = false; life.closed = true; return@LaunchedEffect }
        }
        if (!life.ready || !life.retained) return@LaunchedEffect
        var destination = if (target) currentDragTarget else 0f
        while (life.retained && life.ready) {
        life.inputBlocked = true; life.flat = false
        if (reduce) { life.spring.position = destination; life.spring.velocity = 0f; life.progress = destination }
        else {
            var last = withFrameNanos { it }
            do {
                val now = withFrameNanos { it }
                destination = if (target) currentDragTarget else 0f
                var remaining = ((now - last) / 1e9f).coerceIn(0f, .5f)
                var moving = true
                while (remaining > 0f) {
                    val step = min(.033f, remaining)
                    moving = life.spring.advance(destination, target, step, lightweight = !life.meshEnabled); remaining -= step
                }
                last = now; life.progress = life.spring.position
            } while (moving)
        }
        life.flat = target && destination == 1f
        life.inputBlocked = !life.flat
        if (!target) { life.retained = false; life.closed = true; break }
        // Stay asleep at rest. Moving the header changes the destination sampled by
        // the running clock; it never cancels/restarts that clock on every MOVE.
        val settled = destination
        destination = snapshotFlow { currentDragTarget }.first { it != settled }
        }
    }
    if (life.retained) {
        AnimatedVisibility(true, modifier, enter = EnterTransition.None, exit = ExitTransition.None) {
            val scope = this
            CompositionLocalProvider(LocalPaperOwner provides life, LocalPaperOrigin provides life.origin) {
                key(life.origin) { HardwarePaperSurface(life, reduce) { scope.content() } }
            }
        }
    }
}
@Composable
fun PullOutVisibility(visible: Boolean, modifier: Modifier = Modifier, enter: EnterTransition = EnterTransition.None,
    exit: ExitTransition = ExitTransition.None, label: String = "pullOut", content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val state = remember { MutableTransitionState(false) }; state.targetState = visible
    PullOutVisibility(state, modifier, content)
}
@Composable
fun PullOutVisibility(visibleState: MutableTransitionState<Boolean>, modifier: Modifier = Modifier,
    enter: EnterTransition, exit: ExitTransition, label: String = "pullOut", content: @Composable AnimatedVisibilityScope.() -> Unit) =
    PullOutVisibility(visibleState, modifier, content)
