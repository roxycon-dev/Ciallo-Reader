package com.example.ui.wenku

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.focusProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.roundToInt

private val LocalWenkuSequence = staticCompositionLocalOf<State<Float>?> { null }

/**
  * 详见 docs/wenku8-ui-port-plan.md
  * 详见 docs/wenku8-ui-port-plan.md */
@Composable
fun WenkuLoadedContentSequence(
    contentKey: Any? = Unit,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
    revealOnEnter: Boolean = true,
    content: @Composable () -> Unit
) {
    val a11y = rememberWenkuAccessibility()
    val animate = revealOnEnter && !a11y.reduceMotion
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(contentKey, loading, animate) {
        if (!animate) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        if (loading) {
            progress.snapTo(0f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = WenkuMotion.ENTER_MS, easing = LinearEasing))
        }
    }
    CompositionLocalProvider(LocalWenkuSequence provides progress.asState()) {
        Box(modifier) { content() }
    }
}

@Composable
internal fun WenkuContentClock(progress: State<Float>, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWenkuSequence provides progress, content = content)
}

enum class WenkuRevealAxis { Vertical, Horizontal }

/**
  * 详见 docs/wenku8-ui-port-plan.md */
fun Modifier.wenkuReveal(
    order: Int,
    axis: WenkuRevealAxis = WenkuRevealAxis.Vertical,
    reverse: Boolean = false
): Modifier = wenkuArchivePart(WenkuMotion.listStart(order), WenkuMotion.listEnd(order), axis, reverse)

internal fun Modifier.wenkuArchivePart(
    start: Float,
    end: Float,
    axis: WenkuRevealAxis = WenkuRevealAxis.Horizontal,
    reverse: Boolean = false,
    travel: Float = 6f,
): Modifier = composed {
    val sequence = LocalWenkuSequence.current
    val reduce = LocalWenkuReduceMotion.current || com.example.ui.feedback.LocalReduceMotion.current
    if (sequence == null || reduce) return@composed this
    val travelX = if (axis == WenkuRevealAxis.Horizontal) (if (reverse) -travel else travel) else 0f
    val travelY = if (axis == WenkuRevealAxis.Vertical) travel else 0f
    this
        .drawWithContent {
            val raw = sequence.value
            val p = revealProgress(raw, start, end)
            if (p <= 0f) return@drawWithContent
            if (p >= 1f) {
                drawContent()
                return@drawWithContent
            }
            val dx = travelX * (1f - p) * density
            val dy = travelY * (1f - p) * density
            withTransform({ translate(dx, dy) }) {
                if (axis == WenkuRevealAxis.Vertical) {
                    clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height * p) {
                        this@drawWithContent.drawContent()
                    }
                } else {
                    clipRect(
                        left = if (!reverse) 0f else size.width * (1f - p),
                        top = 0f,
                        right = if (!reverse) size.width * p else size.width,
                        bottom = size.height
                    ) { this@drawWithContent.drawContent() }
                }
            }
        }
}

fun Modifier.wenkuTabTitle(order: Int): Modifier = composed {
    val sequence = LocalWenkuSequence.current ?: return@composed this
    val reduce = LocalWenkuReduceMotion.current || com.example.ui.feedback.LocalReduceMotion.current
    if (reduce) return@composed this
    graphicsLayer {
        val start = .05f + .11f * order
        val end = .80f + .09f * order
        val phase = ((sequence.value - start) / (end - start)).coerceIn(0f, 1f)
        val p = WenkuMotion.ease.transform(phase)
        val landing = 1f - WenkuMotion.settle.transform(phase)
        // Use the source's finite jelly wave for the final contact, keeping the baseline crisp.
        val rebound = WenkuMotion.jelly(((phase - .40f) / .60f).coerceIn(0f, 1f))
        translationY = ((if (order == 0) 12f else 7f) * landing - 2.6f * rebound) * density
        translationX = (1.5f * rebound) * density
        alpha = p
    }
}

private fun revealProgress(raw: Float, start: Float, end: Float): Float {
    if (end <= start) return if (raw >= end) 1f else 0f
    val local = ((raw - start) / (end - start)).coerceIn(0f, 1f)
    return WenkuMotion.ease.transform(local).coerceIn(0f, 1f)
}

/**
  * 详见 docs/wenku8-ui-port-plan.md
  * 详见 docs/wenku8-ui-port-plan.md */
@Composable
fun <T : Any> WenkuPlaneTabSwitcher(
    targetState: T,
    modifier: Modifier = Modifier,
    direction: (from: T, to: T) -> Int,
    content: @Composable (T) -> Unit
) {
    val reduceMotion = rememberWenkuAccessibility().reduceMotion
    var displayed by remember { mutableStateOf(targetState) }
    var outgoing by remember { mutableStateOf<T?>(null) }
    var switchDirection by remember { mutableIntStateOf(1) }
    var visited by remember { mutableStateOf(setOf(targetState)) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(targetState, reduceMotion) {
        if (targetState != displayed) {
            if (!reduceMotion && outgoing != null) {
                // Finish the currently visible cut before using that pane as the next source.
                progress.animateTo(1f, tween(72, easing = LinearEasing))
            }
            outgoing = null
            switchDirection = if (direction(displayed, targetState) >= 0) 1 else -1
            progress.snapTo(if (reduceMotion) 1f else 0f)
            outgoing = displayed.takeUnless { reduceMotion }
            visited = visited + targetState
            displayed = targetState
        }
        if (reduceMotion) progress.snapTo(1f)
        // ArchivePlaneRender owns the bezier. Applying it here too front-loads the cut
        // and makes the labels finish before the incoming pane has settled.
        else if (outgoing != null) {
            progress.animateTo(1f, tween(WenkuMotion.ENTER_MS, easing = LinearEasing))
        }
        outgoing = null
    }

    val tabBackground = if (com.example.ui.LocalAppBackgroundActive.current) Color.Transparent
        else androidx.compose.material3.MaterialTheme.colorScheme.background
    Box(modifier.clipToBounds()) {
        // Like RetainedPane in ui_notifiers.dart: keep the same keyed subtree for
        // each visited tab and give layout/paint to the two transition planes only.
        for (tab in visited) key(tab) {
            val active = tab == displayed
            val leaving = tab == outgoing
            val visible = active || leaving
            val animateContent = rememberUpdatedState(active && outgoing != null && !reduceMotion)
            val sequence = remember(progress) {
                derivedStateOf { if (animateContent.value) progress.value else 1f }
            }
            val plane = when {
                leaving -> Modifier.wenkuPlaneLayer(
                    { progress.value }, incoming = false, dir = switchDirection, occludeOutgoing = true
                )
                active && outgoing != null -> Modifier.wenkuPlaneLayer({ progress.value }, incoming = true, dir = switchDirection)
                else -> Modifier
            }
            RetainedPaneLifecycle(active) {
                Box(
                    Modifier
                        .wenkuRetainedLayout(visible)
                        .fillMaxSize()
                        .zIndex(if (active) 1f else 0f)
                        .then(if (active) Modifier else Modifier
                            .clearAndSetSemantics { }
                            .focusProperties { canFocus = false }
                            .wenkuBlockPointer())
                        .then(plane)
                        .background(tabBackground)
                ) {
                    CompositionLocalProvider(LocalWenkuSequence provides sequence) {
                        content(tab)
                    }
                }
            }
        }
    }
}

/** Stop measuring and drawing a covered pane while preserving its composition and scroll state. */
internal fun Modifier.wenkuRetainedLayout(visible: Boolean): Modifier = layout { measurable, constraints ->
    if (visible) {
        val child = measurable.measure(constraints)
        layout(child.width, child.height) { child.place(0, 0) }
    } else {
        layout(constraints.minWidth, constraints.minHeight) { }
    }
}

/** An offstage pane keeps its UI state while its lifecycle collectors and back handlers pause. */
@Composable
internal fun RetainedPaneLifecycle(active: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current.lifecycle
    val owner = remember(parent) {
        object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
        }
    }
    val currentActive = rememberUpdatedState(active)
    fun sync() {
        val state = parent.currentState
        if (owner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
            owner.lifecycle.currentState = when {
                state == Lifecycle.State.DESTROYED -> state
                currentActive.value && state.isAtLeast(Lifecycle.State.STARTED) -> state
                else -> Lifecycle.State.CREATED
            }
        }
    }
    DisposableEffect(parent, owner) {
        val observer = LifecycleEventObserver { _, _ -> sync() }
        parent.addObserver(observer)
        onDispose {
            parent.removeObserver(observer)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        }
    }
    SideEffect { sync() }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

/**
  * 详见 docs/wenku8-ui-port-plan.md */
fun Modifier.wenkuPlaneLayer(
    progress: () -> Float,
    incoming: Boolean,
    dir: Int = 1,
    occludeOutgoing: Boolean = false
): Modifier = this
    .drawWithCache {
        val path = Path()
        onDrawWithContent {
            if (!incoming && !occludeOutgoing) {
                drawContent()
                return@onDrawWithContent
            }
            val p = WenkuMotion.plane.transform(progress().coerceIn(0f, 1f))
            if (p >= 1f) {
                if (incoming) drawContent()
                return@onDrawWithContent
            }
            if (p <= 0f) {
                if (!incoming) drawContent()
                return@onDrawWithContent
            }
            val width = size.width
            val height = size.height
            val slant = min(180.dp.toPx(), width * 0.46f)
            val edge = (width + slant) * p
            path.rewind()
            if (dir >= 0) {
                path.moveTo(0f, 0f)
                path.lineTo(edge, 0f)
                path.lineTo(edge - slant, height)
                path.lineTo(0f, height)
            } else {
                path.moveTo(width, 0f)
                path.lineTo(width - edge, 0f)
                path.lineTo(width - edge + slant, height)
                path.lineTo(width, height)
            }
            path.close()
            val operation = if (incoming) androidx.compose.ui.graphics.ClipOp.Intersect
                else androidx.compose.ui.graphics.ClipOp.Difference
            clipPath(path, operation) { this@onDrawWithContent.drawContent() }
        }
    }
    .graphicsLayer {
        val p = WenkuMotion.plane.transform(progress().coerceIn(0f, 1f))
        if (incoming) {
            translationX = -24f * dir * (1f - p) * density
            translationY = 12f * (1f - p) * density
        } else {
            translationX = 10f * dir * p * density
            translationY = -4f * p * density
        }
    }

fun Modifier.wenkuBlockPointer(): Modifier = composed {
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}

/** Linear controller shared by the reader header and lower controls (pages.dart). */
@Composable
internal fun rememberWenkuReaderChrome(visible: Boolean): State<Float> {
    val animate = rememberWenkuAccessibility().decorativeMotion && LocalWenkuDecorativeMotion.current
    val target = if (visible) 1f else 0f
    val phase = remember { Animatable(target) }
    LaunchedEffect(target, animate) {
        if (animate) {
            val duration = if (visible) WenkuMotion.ENTER_MS else WenkuMotion.EXIT_MS
            val remaining = (abs(target - phase.value) * duration).roundToInt()
            phase.animateTo(target, tween(remaining, easing = LinearEasing))
        } else phase.snapTo(target)
    }
    return phase.asState()
}

/** pages.dart uses Interval(.10, 1) for progress and Interval(0, .90) for the dock. */
internal enum class WenkuReaderChromePart(
    val start: Float,
    val end: Float,
    val travel: Float,
    val scaleFrom: Float
) {
    Header(0f, 1f, -1f, 1f),
    Progress(.10f, 1f, 1.28f, .90f),
    Dock(0f, .90f, 1f, .96f)
}

/** reader_chrome.dart ReaderChromeLayer translates the live surface without an opacity layer. */
@Composable
internal fun WenkuReaderChromeLayer(
    phase: State<Float>,
    visible: Boolean,
    modifier: Modifier = Modifier,
    part: WenkuReaderChromePart = WenkuReaderChromePart.Header,
    content: @Composable () -> Unit
) {
    val onstage = remember(phase, part) { derivedStateOf { phase.value > part.start } }
    val interactive = visible && onstage.value
    Box(
        modifier
            // Keep both placement passes stable under NavHost lookahead. The hidden clip
            // excludes the translated children from hit testing as well as paint.
            .graphicsLayer { clip = !onstage.value || part == WenkuReaderChromePart.Header }
            .drawWithContent { if (onstage.value) drawContent() }
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    val value = ((phase.value - part.start) / (part.end - part.start)).coerceIn(0f, 1f)
                    val easing = if (part == WenkuReaderChromePart.Header) WenkuMotion.ease else WenkuMotion.settle
                    val progress = easing.transform(value)
                    translationY = size.height * (1f - progress) * part.travel
                    scaleX = part.scaleFrom + (1f - part.scaleFrom) * progress
                    scaleY = scaleX
                }
                .then(if (interactive) Modifier else Modifier
                    .clearAndSetSemantics { }
                    .focusProperties { canFocus = false }
                    .wenkuBlockPointer())
        ) { content() }
    }
}

private val readerLocationEase = CubicBezierEasing(.645f, .045f, .355f, 1f)

/** reader_chrome.dart ReaderLocationLayer shares the chrome clock. */
internal fun Modifier.wenkuReaderLocation(phase: State<Float>): Modifier = graphicsLayer {
    val covered = readerLocationEase.transform(phase.value.coerceIn(0f, 1f))
    compositingStrategy = CompositingStrategy.ModulateAlpha
    alpha = 1f - covered
    translationY = -6.dp.toPx() * covered
}

internal fun Modifier.wenkuReaderCapsule(tint: Color, outline: Color): Modifier {
    val shape = RoundedCornerShape(24.dp)
    return clip(shape).background(tint).border(.5.dp, outline, shape)
}

/** Inline reader_tools.dart scrubber. Keep drag previews separate from the commit. */
@Composable
internal fun WenkuReaderProgressSlider(
    value: Float,
    label: String,
    format: (Float) -> String,
    tint: Color,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    reverse: Boolean = false
) {
    CompositionLocalProvider(LocalLayoutDirection provides if (reverse) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            onValueChangeFinished = onCommit,
            enabled = enabled,
            valueRange = valueRange,
            modifier = modifier.semantics {
                contentDescription = label
                stateDescription = format(value)
            },
            colors = SliderDefaults.colors(
                thumbColor = tint,
                activeTrackColor = tint,
                inactiveTrackColor = tint.copy(alpha = .20f)
            )
        )
    }
}

/** 详见 docs/wenku8-ui-port-plan.md。 */
@Composable
fun WenkuScrollEdgeFade(
    modifier: Modifier = Modifier,
    top: Dp = 24.dp,
    bottom: Dp = 40.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = wenkuColors()
    Box(modifier) {
        content()
        if (top > 0.dp) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(top)
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.paper.copy(alpha = 0.92f), Color.Transparent)
                        )
                    )
            )
        }
        if (bottom > 0.dp) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottom)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, colors.paper.copy(alpha = 0.86f))
                        )
                    )
            )
        }
    }
}

/** 参考 `loading_ui.dart:59-130`：骨架是**纯静态**占位（无 shimmer / 无重复 ticker / 无滤镜），
 *  填充色 = onSurface alpha 暗色 .0825 / 亮色 .0625，圆角 2。 */
@Composable
fun WenkuSkeletonBlock(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(4.dp)
) {
    val colors = wenkuColors()
    val fill = colors.ink.copy(alpha = if (colors.isDark) 0.0825f else 0.0625f)
    Box(modifier.clip(shape).background(fill))
}

/** 详见 docs/wenku8-ui-port-plan.md。 */
@Composable
fun WenkuLoadingHandoff(
    loading: Boolean,
    contentKey: Any?,
    modifier: Modifier = Modifier,
    skeleton: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val a11y = rememberWenkuAccessibility()
    val skeletonAlpha = animateFloatAsState(
        targetValue = if (loading) 1f else 0f,
        animationSpec = tween(if (a11y.reduceMotion) 0 else 120, easing = WenkuMotion.ease),
        label = "wenkuSkeletonOut"
    )
    val skeletonVisible by remember(skeletonAlpha) { derivedStateOf { skeletonAlpha.value > 0.01f } }
    Box(modifier) {
        if (loading || skeletonVisible) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = skeletonAlpha.value }) { skeleton() }
        }
        WenkuLoadedContentSequence(contentKey = contentKey, loading = loading) { content() }
    }
}

