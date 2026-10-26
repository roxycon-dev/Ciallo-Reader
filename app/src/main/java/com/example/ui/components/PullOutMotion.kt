package com.example.ui.components

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.flow.first
import kotlin.math.*

/** Shared spring and geometry for every modal; live Compose content stays in its own window. */
object PullOutConfig {
    const val SCRIM = .35f
    const val DISMISS_DISTANCE = 64f
    const val DRAG_TRAVEL = 300f
    const val SURFACE_SCALE = .96f
    const val SURFACE_TRAVEL_DP = 12f
    const val WARP_ROWS = 64
    const val WARP_FORWARD_ROWS = 128
    const val WARP_NECK = .30f
    const val WARP_DECAY_DP = 90f
    const val WARP_SOFTNESS_DP = 12f
    const val WARP_UNFOLD_DP = 24f
    const val WARP_BEND_DP = 16f
    const val WARP_FLUTTER_DP = 4f
    const val WARP_SHADE = .025f
    const val WARP_OPACITY_GAIN = 5f
    const val WARP_MIN_WIDTH_DP = 100f
    const val WARP_MIN_HEIGHT_DP = 48f
    const val WARP_VIEWPORT_CUTOFF = .96f
    const val OPEN_K = 140f
    // Near-critical arrival: a flat surface must never re-enter the warp on a late rebound.
    const val OPEN_D = 24f
    const val CLOSE_K = 260f
    const val CLOSE_D = 34f
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
    var view: WeakReference<android.view.View>? = null
    var parent: PullOutSource? = null
    var virtual = false
    var disposed = false
    var frozenOpening = false
    var claimed = false
    var activatedAt = 0L
    fun freezeForHandoff() = PullOutSource().also { snapshot ->
        snapshot.bounds = bounds; snapshot.view = view; snapshot.virtual = virtual
        snapshot.parent = parent?.returnSource() ?: returnSource()
        snapshot.disposed = true; snapshot.frozenOpening = true
    }
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
    }?.also { it.claimed = true }?.let { if (it.frozenOpening) it else it.returnSource() }
}
internal val LocalPaperOrigin = compositionLocalOf<PullOutSource?> { null }
internal val LocalPaperOwner = compositionLocalOf<PaperLifecycle?> { null }
internal val LocalPaperFullSurface = compositionLocalOf { false }

internal fun android.view.View.paperWindowOffset(screen: IntArray = IntArray(2), window: IntArray = IntArray(2)): Offset {
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
    val screenLocation = remember(view) { IntArray(2) }
    val windowLocation = remember(view) { IntArray(2) }
    source.view = WeakReference(view)
    // Geometry only: never record, hide or take ownership of a card's render nodes.
    onGloballyPositioned { source.bounds = it.boundsInWindow().translate(view.paperWindowOffset(screenLocation, windowLocation)) }
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
    var flat by mutableStateOf(false)
    var inputBlocked by mutableStateOf(true)
    var registered by mutableStateOf(false)
    var hostAttached = false
    var afterDismiss: (() -> Unit)? = null
    var origin: PullOutSource? = null
    var softPaper = false
    var allowFullViewport = false
    var handingOff = false
    val spring = PaperSpring()
}
private val paperLifecycles = WeakHashMap<MutableTransitionState<Boolean>, PaperLifecycle>()
@Composable
internal fun rememberPaperLifecycle(state: MutableTransitionState<Boolean>): PaperLifecycle = remember(state) {
    paperLifecycles.getOrPut(state) { PaperLifecycle() }
}

/** Exact spring integration retains both displacement and velocity across target changes. */
internal class PaperSpring {
    private class Coefficients(k: Float, d: Float) {
        val halfD = d / 2f
        val discriminant = halfD * halfD - k
        val w = sqrt(abs(discriminant))
        val r1 = -halfD + w
        val r2 = -halfD - w
    }
    private companion object {
        val open = Coefficients(PullOutConfig.OPEN_K, PullOutConfig.OPEN_D)
        val close = Coefficients(PullOutConfig.CLOSE_K, PullOutConfig.CLOSE_D)
        val fallbackOpen = Coefficients(PullOutConfig.FALLBACK_OPEN_K, PullOutConfig.FALLBACK_OPEN_D)
        val fallbackClose = Coefficients(PullOutConfig.FALLBACK_CLOSE_K, PullOutConfig.FALLBACK_CLOSE_D)
    }
    var position = 0f
    var velocity = 0f
    fun advance(target: Float, opening: Boolean, seconds: Float, lightweight: Boolean = false): Boolean {
        val dt = seconds.coerceIn(0f, .033f)
        val coefficients = if (lightweight) { if (opening) fallbackOpen else fallbackClose } else if (opening) open else close
        val halfD = coefficients.halfD
        val x = position - target
        val discriminant = coefficients.discriminant
        val next: Float
        if (discriminant < 0f) {
            val w = coefficients.w; val a = x; val b = (velocity + halfD * x) / w
            val decay = exp(-halfD * dt); val c = cos(w * dt); val sn = sin(w * dt)
            val z = a * c + b * sn
            next = decay * z
            velocity = decay * ((-a * w * sn + b * w * c) - halfD * z)
        } else if (discriminant > .00001f) {
            val r1 = coefficients.r1; val r2 = coefficients.r2
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
    deformContent: Boolean = true, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val life = rememberPaperLifecycle(visibleState)
    val parentPaper = LocalPaperOwner.current
    val quality = LocalRenderQuality.current
    val reduce = com.example.ui.feedback.LocalReduceMotion.current || quality.minimal
    val enhancedMotion = quality.enhancedMotion
    val parentOrigin = LocalPaperOrigin.current
    val fullSurface = LocalPaperFullSurface.current
    val dragTarget = if (parentPaper == null) LocalPaperVisibleTarget.current else 1f
    val currentDragTarget by rememberUpdatedState(dragTarget)
    val target = visibleState.targetState
    val currentTarget by rememberUpdatedState(target)
    val initiallyVisible = remember { visibleState.currentState || (target && parentPaper != null && !parentPaper.flat) }
    if (target && !life.retained) {
        life.origin = (if (fullSurface && parentPaper == null) parentOrigin ?: PullOutSources.claim()
            else PullOutSources.claim() ?: parentOrigin) ?: PullOutSource().apply { virtual = true }
        life.softPaper = enhancedMotion && !reduce && android.os.Build.VERSION.SDK_INT >= 33 && life.origin?.virtual == false &&
            !(initiallyVisible && !life.registered && parentPaper != null && !parentPaper.flat)
        life.allowFullViewport = fullSurface && (parentPaper == null || parentPaper.flat)
        life.retained = true; life.closed = false; life.handingOff = false
        if (initiallyVisible && !life.registered) {
            life.spring.position = 1f; life.progress = 1f; life.flat = true; life.inputBlocked = false
        }
    }
    SideEffect {
        life.registered = true
        // A quality change while a window is open must disable its expensive exit too.
        if (!enhancedMotion || reduce) life.softPaper = false
    }
    DisposableEffect(life) { onDispose { life.registered = false } }
    // Preserve existing callers' transition state while our one clock owns retention.
    AnimatedVisibility(visibleState, enter = EnterTransition.None, exit = ExitTransition.None) { }
    // Do not key this effect on target: repeated taps/reversals retain velocity and
    // cannot cancel an exit completion or strand a retained window halfway closed.
    LaunchedEffect(life, reduce) {
        while (true) {
            val opening = currentTarget
            var destination = if (opening) currentDragTarget else 0f
            if (life.retained) {
                if (!opening && life.origin?.frozenOpening == true) life.origin = life.origin?.returnSource()
                life.inputBlocked = true; life.flat = false
                if (reduce) {
                    life.spring.position = destination; life.spring.velocity = 0f; life.progress = destination
                } else {
                    var last = withFrameNanos { it }
                    do {
                        val now = withFrameNanos { it }
                        destination = if (currentTarget) currentDragTarget else 0f
                        // A paused/overloaded device must not do an unbounded catch-up loop.
                        val dt = ((now - last) / 1e9f).coerceIn(0f, .033f)
                        val moving = life.spring.advance(destination, currentTarget, dt, lightweight = !life.softPaper)
                        last = now; life.progress = life.spring.position
                    } while (moving)
                }
                life.flat = currentTarget && destination == 1f
                life.inputBlocked = !life.flat
                if (!currentTarget) { life.retained = false; life.closed = true }
            }
            val settled = currentTarget to currentDragTarget
            // No frame loop, readback, renderer or timer survives a settled transition.
            snapshotFlow { currentTarget to currentDragTarget }.first { it != settled }
        }
    }
    if (life.retained) {
        AnimatedVisibility(true, modifier, enter = EnterTransition.None, exit = ExitTransition.None) {
            val scope = this
            CompositionLocalProvider(LocalPaperOwner provides life, LocalPaperOrigin provides life.origin) {
                // Returning from a disposed menu changes geometry, not content identity.
                // Re-keying here would rebuild every glass card during the close animation.
                if (deformContent) StablePaperSurface(life) { scope.content() } else scope.content()
            }
        }
    }
}
@Composable
fun PullOutVisibility(visible: Boolean, modifier: Modifier = Modifier, enter: EnterTransition = EnterTransition.None,
    exit: ExitTransition = ExitTransition.None, label: String = "pullOut", content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val state = remember { MutableTransitionState(false) }; state.targetState = visible
    PullOutVisibility(state, modifier, content = content)
}
@Composable
fun PullOutVisibility(visibleState: MutableTransitionState<Boolean>, modifier: Modifier = Modifier,
    enter: EnterTransition, exit: ExitTransition, label: String = "pullOut", content: @Composable AnimatedVisibilityScope.() -> Unit) =
    PullOutVisibility(visibleState, modifier, content = content)
