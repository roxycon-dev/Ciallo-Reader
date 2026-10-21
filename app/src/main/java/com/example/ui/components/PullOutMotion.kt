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
    const val OPEN_K = 140f
    const val OPEN_D = 15f
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
    var claimed = false
    var activatedAt = 0L
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
    source.view = WeakReference(view)
    // Geometry only: never record, hide or take ownership of a card's render nodes.
    onGloballyPositioned { source.bounds = it.boundsInWindow().translate(view.paperWindowOffset()) }
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
    val spring = PaperSpring()
}
private val paperLifecycles = WeakHashMap<MutableTransitionState<Boolean>, PaperLifecycle>()
@Composable
internal fun rememberPaperLifecycle(state: MutableTransitionState<Boolean>): PaperLifecycle = remember(state) {
    paperLifecycles.getOrPut(state) { PaperLifecycle() }
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
    val currentTarget by rememberUpdatedState(target)
    val initiallyVisible = remember { visibleState.currentState || (target && parentPaper != null) }
    if (target && !life.retained) {
        life.origin = PullOutSources.claim() ?: parentOrigin ?: PullOutSource().apply { virtual = true }
        life.retained = true; life.closed = false
        if (initiallyVisible && !life.registered) {
            life.spring.position = 1f; life.progress = 1f; life.flat = true; life.inputBlocked = false
        }
    }
    SideEffect { life.registered = true }
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
                        val moving = life.spring.advance(destination, currentTarget, dt, lightweight = true)
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
                key(life.origin) { StablePaperSurface(life) { scope.content() } }
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
