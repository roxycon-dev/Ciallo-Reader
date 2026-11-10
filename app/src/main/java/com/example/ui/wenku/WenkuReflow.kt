package com.example.ui.wenku

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.min

/*
 * `library_reflow.dart` + `library_workspace.dart` 的 Compose 端 1:1 移植。
 *
 * 语义锚点（逐条对源码）：
 * - 时钟：`Cubic(.18,.72,.18,1)`（`library_reflow.dart:17`、`archive_motion.dart:319-320`），
 *   时长 `420ms`（`motion_ui.dart:19` `NovelMotion.enter`，以及 `library_workspace.dart:55`）。
 * - 每个参与重排的子项在内容变化**之前**记录自己的矩形；变化后把「旧矩形 → 新矩形」用**同一个**
 *   420ms 时钟做纯平移插值：`位移 = (source.topLeft - origin) * (1 - progress)`。
 * - 尺寸同样插值：`Size.lerp(source.size, size, progress)`，然后裁剪到该可见尺寸。
 * - 未参与重排（没有旧矩形）的子项：`10dp` 上浮 + 透明度揭示，`0.025` 步长错峰、最多 9 档
 *   （`library_reflow.dart:172` 的 `(order % 9) * .025`）。
 * - 视口裁剪也从「旧视口矩形」插值到新视口（`library_reflow.dart:311-320`）。
 *
 * paint-only 约束（本文件所有动画路径都遵守）：
 * - 子项矩形只写在 `onGloballyPositioned`（布局阶段），落在**普通 HashMap** 里，不写快照状态，
 *   因此布局不会反过来触发重组。
 * - 所有随帧变化的值（`progress`、旧矩形表、旧视口）只在 `graphicsLayer { }` /
 *   `drawWithContent { }` 里读；Compose 只会把这些读登记到 draw/layer 阶段，帧时钟推进时
 *   只失效绘制，不重组、不重新测量。
 * - 唯一在组合阶段读的状态是「面板是否展开 / 是否正在关闭」这种一次性的布尔量，与
 *   `library_workspace.dart` 里 `setState` 的次数一一对应。
 */
private const val REFLOW_DURATION_MS = 420

/** `library_workspace.dart:57` 的 `reverseDuration: 280ms`。 */
private const val WORKSPACE_CLOSE_MS = 280

/** `(order % 9) * .025`：0.025 步长、最多 9 档。 */
private const val REFLOW_STAGGER_STEP = 0.025f
private const val REFLOW_STAGGER_BANDS = 9

/** 未参与重排的子项上浮距离（`library_reflow.dart:175` 的 `10 * (1 - _progress)`，单位 dp）。 */
private const val REFLOW_RISE_DP = 10f

private const val WORKSPACE_WIDE_MIN_WIDTH_DP = 1000f
private const val WORKSPACE_WIDE_MIN_HEIGHT_DP = 500f
private const val WORKSPACE_MAX_WIDTH_DP = 1200f
private const val WORKSPACE_SELECTED_MAX_WIDTH_DP = 1440f
private const val WORKSPACE_GAP_DP = 24f

/** `const Cubic(.18, .72, .18, 1)`。 */
private val ReflowEasing: Easing = CubicBezierEasing(0.18f, 0.72f, 0.18f, 1f)

/**
 * 当前作用域里的重排控制器：`WenkuReflowScope` / `WenkuSplitWorkspace` 会在内容槽里注入它，
 * 于是槽内的子项可以直接 `WenkuReflowItem(key) { ... }` 而不必层层传 state。
 */
val LocalWenkuReflowState = staticCompositionLocalOf<WenkuReflowState?> { null }

/**
 * 保留内容位置的重排控制器（`LibraryReflowController`）。
 *
 * 矩形坐标系：**root 坐标**，即 `LayoutCoordinates.boundsInRoot()`。Flutter 侧存的是
 * `currentGlobalRect`，绘制时用 `localToGlobal` 求位移差；Compose 没有跨窗口的「global」层，
 * `boundsInRoot` 是等价的稳定参照系。只有视口裁剪在 draw 时换算回容器本地坐标
 * （对应 `library_reflow.dart:316` 的 `source.shift(-localToGlobal(Offset.zero))`）。
 */
class WenkuReflowState {

    /** 旧矩形表：只在 [begin] 写入，只在 draw / graphicsLayer 阶段读取。 */
    private val sourceRects = mutableStateMapOf<Any, Rect>()

    /** 旧视口矩形（root 坐标）。 */
    private val sourceViewportState = mutableStateOf<Rect?>(null)

    /** 内部帧时钟（0..1，未缓动）；由 [pushClock] 每帧写入，只在 draw 阶段读。 */
    private val progressTick = mutableFloatStateOf(1f)

    private val activeState = mutableStateOf(false)
    private val generationState = mutableIntStateOf(0)

    /** 外部时钟；null 表示用内部 [progressTick]。 */
    private var clock: (() -> Float)? = null

    /** 当前布局矩形（root 坐标）。布局阶段写，绘制阶段读，不参与快照观察。 */
    private val liveRects = HashMap<Any, Rect>()
    private var liveViewport: Rect? = null
    private var containerRoot: Offset = Offset.Zero
    private var containerSize: Size = Size.Zero

    /** 减少动效时 [begin] 直接落位。由 `rememberWenkuReflowState()` 注入。 */
    internal var reduceMotion: Boolean = false

    val active: Boolean get() = activeState.value

    val generation: Int get() = generationState.value

    /**
     * 缓动后的进度；未激活恒为 1（等价 Flutter 的 `!controller.active ? 1 : ...`）。
     * 只在 draw / graphicsLayer 阶段读，推进时只失效绘制。
     */
    val progress: Float
        get() {
            if (!activeState.value) return 1f
            val source = clock ?: return 1f
            return ReflowEasing.transform(source().coerceIn(0f, 1f))
        }

    /**
     * 用外部时钟开始一轮重排：先记录所有已知子项的旧矩形与旧视口，再等调用方改变内容。
     * [clock] 返回 0..1 的原始值（未缓动）。
     */
    fun begin(clock: () -> Float) {
        start(clock)
    }

    /** 用内部帧时钟开始一轮重排，配合 [pushClock] 手动推帧。 */
    fun begin() {
        start { progressTick.floatValue }
    }

    /** 立刻落位：清空旧矩形，进度回到 1（`library_reflow.dart:40-47`）。 */
    fun end() {
        sourceRects.clear()
        sourceViewportState.value = null
        clock = null
        progressTick.floatValue = 1f
        activeState.value = false
    }

    /**
     * 记录容器视口矩形（root 坐标）。内置视口容器会自动调用；外部自绘容器可用它托管。
     * 未激活时只更新「当前视口」，激活时同时改写「旧视口」，用于视口插值。
     */
    fun snapshot(bounds: Rect) {
        liveViewport = bounds
        if (activeState.value) sourceViewportState.value = bounds
    }

    /**
     * 手工登记某个 key 在内容变化前的矩形（root 坐标）。用于不走 [WenkuReflowItem] 的自绘子项；
     * 未激活时写入「当前矩形」，让随后的 [begin] 能取到它。
     */
    fun relocate(key: Any, bounds: Rect) {
        if (activeState.value) sourceRects[key] = bounds else liveRects[key] = bounds
    }

    internal fun pushClock(raw: Float) {
        progressTick.floatValue = raw.coerceIn(0f, 1f)
    }

    internal fun observe(key: Any, coordinates: LayoutCoordinates) {
        // 旋转 / 分屏 / Lookahead 的同一帧里，onGloballyPositioned 可能带着尚未附着或已脱离、
        // 或尺寸暂时为 0 的节点进来：对未附着的 LayoutCoordinates 调 boundsInRoot() 会抛
        // IllegalStateException；0 尺寸矩形则会污染下一轮重排的 paintedRect 插值。
        // 这两种情况宁可不采样（沿用上一次有效值），也不猜、不吞异常。
        val bounds = coordinates.attachedBoundsOrNull() ?: return
        liveRects[key] = bounds
    }

    internal fun forget(key: Any) {
        // Only the *live* rect dies with the composition. The frozen source rect must survive
        // a LazyList/LazyGrid slot recycle during the 420ms pass, otherwise every re-ordered
        // card looks like a brand new card and fades in from alpha 0 (library_reflow.dart keeps
        // the captured rects until the pass ends, `:40-47`).
        liveRects.remove(key)
    }

    internal fun sourceOf(key: Any): Rect? = sourceRects[key]

    internal fun liveOf(key: Any): Rect? = liveRects[key]

    internal fun observeContainer(coordinates: LayoutCoordinates) {
        // 容器同理：未附着或退化成 0 时不改写视口，否则 viewport() 会拿 Zero 容器去裁剪整屏。
        val bounds = coordinates.attachedBoundsOrNull() ?: return
        liveViewport = bounds
        containerRoot = bounds.topLeft
        containerSize = bounds.size
    }

    /** 已附着且为有限正尺寸的 root 矩形；否则返回 null（不抛异常、不写入退化矩形）。 */
    private fun LayoutCoordinates.attachedBoundsOrNull(): Rect? {
        if (!isAttached) return null
        val bounds = boundsInRoot()
        if (!bounds.width.isFinite() || !bounds.height.isFinite()) return null
        if (!bounds.left.isFinite() || !bounds.top.isFinite()) return null
        if (bounds.width <= 0f || bounds.height <= 0f) return null
        return bounds
    }

    /**
     * 视口裁剪矩形（容器本地坐标）：从旧视口插值到当前边界
     * （`library_reflow.dart:311-320` 的 `Rect.lerp(source.shift(-localToGlobal), Offset.zero & size, progress)`）。
     */
    internal fun viewport(size: Size): Rect {
        val bounds = Rect(0f, 0f, size.width, size.height)
        val source = sourceViewportState.value ?: return bounds
        if (!activeState.value) return bounds
        val p = progress
        if (p >= 1f) return bounds
        val shifted = Rect(
            source.left - containerRoot.x,
            source.top - containerRoot.y,
            source.right - containerRoot.x,
            source.bottom - containerRoot.y
        )
        return lerp(shifted, bounds, p)
    }

    /** 可见尺寸：`Size.lerp(source.size, size, progress)`。 */
    internal fun visibleSize(key: Any, layout: Size): Size {
        val source = sourceRects[key] ?: return layout
        val p = progress
        if (p >= 1f) return layout
        // A pure re-order keeps the card size: interpolating two equal sizes clips the item to
        // a sub-pixel sliver whenever the captured rect is off by a rounding unit. Only a real
        // size change (density switch, span change) needs the clip (`library_reflow.dart:242-272`).
        if (kotlin.math.abs(source.width - layout.width) < .5f &&
            kotlin.math.abs(source.height - layout.height) < .5f
        ) return layout
        return lerp(source.size, layout, p)
    }

    private fun start(nextClock: () -> Float) {
        if (reduceMotion) {
            // 关闭动效时直接落位：不留旧矩形，下一次绘制就是终态。
            end()
            return
        }
        val wasActive = activeState.value
        val previous = if (wasActive) progress else 1f
        val viewportNow = currentViewportRoot()
        val next = HashMap<Any, Rect>(liveRects.size * 2)
        for ((key, rect) in liveRects) {
            val rectNow = if (wasActive) paintedRect(rect, sourceRects[key], previous) else rect
            if (viewportNow == null || viewportNow.overlaps(rectNow)) next[key] = rectNow
        }
        sourceRects.clear()
        sourceRects.putAll(next)
        sourceViewportState.value = viewportNow
        clock = nextClock
        progressTick.floatValue = nextClock().coerceIn(0f, 1f)
        if (!wasActive) activeState.value = true
        generationState.intValue = generationState.intValue + 1
    }

    /** 当前真实可见矩形：布局矩形 + 本轮位移 + 插值尺寸（`library_reflow.dart:182` 的 `currentGlobalRect`）。 */
    private fun paintedRect(layout: Rect, source: Rect?, progress: Float): Rect {
        if (source == null) return layout
        val dx = (source.left - layout.left) * (1f - progress)
        val dy = (source.top - layout.top) * (1f - progress)
        val size = lerp(source.size, layout.size, progress)
        return Rect(layout.left + dx, layout.top + dy, layout.left + dx + size.width, layout.top + dy + size.height)
    }

    /** 当前视口在 root 坐标下的矩形：激活时是插值结果，否则是容器当前边界。 */
    private fun currentViewportRoot(): Rect? {
        val source = sourceViewportState.value
        if (!activeState.value || source == null) return liveViewport
        val p = progress
        val target = Rect(
            containerRoot.x,
            containerRoot.y,
            containerRoot.x + containerSize.width,
            containerRoot.y + containerSize.height
        )
        return lerp(source, target, p)
    }
}

/**
 * 创建重排控制器，并把 `rememberWenkuAccessibility().reduceMotion` 接进来：
 * 减少动效时 [WenkuReflowState.begin] 直接落位。
 */
@Composable
fun rememberWenkuReflowState(): WenkuReflowState {
    val state = remember { WenkuReflowState() }
    val reduceMotion = rememberWenkuAccessibility().reduceMotion
    SideEffect { state.reduceMotion = reduceMotion }
    return state
}

/** `(progress - (order % 9) * .025) / (1 - (order % 9) * .025)`，夹到 0..1（`library_reflow.dart:172`）。 */
private fun staggerProgress(progress: Float, order: Int): Float {
    if (progress <= 0f) return 0f
    if (progress >= 1f) return 1f
    val bandIndex = ((order % REFLOW_STAGGER_BANDS) + REFLOW_STAGGER_BANDS) % REFLOW_STAGGER_BANDS
    if (bandIndex == 0) return progress
    val start = bandIndex * REFLOW_STAGGER_STEP
    return ((progress - start) / (1f - start)).coerceIn(0f, 1f)
}

/**
 * 一个可参与重排的子项（`LibraryReflowPart`）：
 * - 布局阶段记录自己在 root 坐标里的矩形（变化前的位置由 [WenkuReflowState.begin] 冻结）；
 * - 变化后按「旧矩形 → 新矩形」做平移 + 可见尺寸裁剪（`graphicsLayer` + `clipRect`）；
 * - 没有旧矩形的子项（新增 / 未参与重排）做 10dp 上浮 + 透明度揭示，按 [order] 错峰。
 *
 * 全部是 paint-only：不重组、不重新测量。
 */
@Composable
fun WenkuReflowItem(
    key: Any,
    state: WenkuReflowState = LocalWenkuReflowState.current
        ?: error("WenkuReflowItem 需要显式传入 WenkuReflowState，或放在 WenkuReflowScope / WenkuSplitWorkspace 的内容槽里"),
    modifier: Modifier = Modifier,
    order: Int = 0,
    content: @Composable () -> Unit
) {
    DisposableEffect(state, key) {
        onDispose { state.forget(key) }
    }
    Box(modifier.wenkuReflowItemLayer(state, key, order)) {
        content()
    }
}

private fun Modifier.wenkuReflowItemLayer(
    state: WenkuReflowState,
    key: Any,
    order: Int
): Modifier = this
    // 放在 graphicsLayer **之前**：取到的是纯布局矩形，不含自己正在播放的位移。
    .onGloballyPositioned { coordinates -> state.observe(key, coordinates) }
    .graphicsLayer {
        val p = state.progress
        val source = state.sourceOf(key)
        if (!state.active || p >= 1f) {
            translationX = 0f
            translationY = 0f
            alpha = 1f
            return@graphicsLayer
        }
        if (source != null) {
            // 平移：`(source.topLeft - origin) * (1 - progress)`。
            val origin = state.liveOf(key) ?: source
            translationX = (source.left - origin.left) * (1f - p)
            translationY = (source.top - origin.top) * (1f - p)
            alpha = 1f
        } else {
            // 未参与重排：10dp 上浮 + 透明度揭示，按 order 错峰。
            val reveal = staggerProgress(p, order)
            translationX = 0f
            translationY = REFLOW_RISE_DP.dp.toPx() * (1f - reveal)
            alpha = reveal
        }
    }
    .drawWithContent {
        val p = state.progress
        if (!state.active || p >= 1f || state.sourceOf(key) == null) {
            drawContent()
            return@drawWithContent
        }
        // 裁剪到插值尺寸：内容按新约束布局，但只露出「旧尺寸 → 新尺寸」这一段。
        val visible = state.visibleSize(key, size)
        if (visible.width <= 0f || visible.height <= 0f) return@drawWithContent
        clipRect(left = 0f, top = 0f, right = visible.width, bottom = visible.height) {
            this@drawWithContent.drawContent()
        }
    }

/**
 * 重排容器（`LibraryReflowScope` + `LibraryReflowClip`）：裁剪视口随进度从旧视口插值到新视口，
 * 并在 [trigger] 变化时自动跑一轮 420ms 时钟。
 *
 * [trigger] 必须是内容变化的稳定标识（排序键、视图模式、列表 size 等）。
 * 首次组合不播放（对应 `library_reflow.dart` 只有显式 `begin()` 才激活）。
 */
@Composable
fun WenkuReflowScope(
    state: WenkuReflowState,
    modifier: Modifier = Modifier,
    trigger: Any,
    content: @Composable () -> Unit
) {
    val initialised = remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (!initialised.value) {
            initialised.value = true
            return@LaunchedEffect
        }
        if (state.reduceMotion) {
            state.end()
            return@LaunchedEffect
        }
        state.begin()
        // 这一轮的身份；取消/收尾时只回收自己开的那一轮。
        val ownedGeneration = state.generation
        state.pushClock(0f)
        try {
            // 等新约束 measure/layout 落位后再起跑（对应 library_workspace.dart:97 的 addPostFrameCallback）。
            val startNanos = withFrameNanos { it }
            var elapsedMs = 0f
            while (elapsedMs < REFLOW_DURATION_MS) {
                val now = withFrameNanos { it }
                elapsedMs = (now - startNanos) / 1_000_000f
                state.pushClock(elapsedMs / REFLOW_DURATION_MS)
            }
            state.pushClock(1f)
            state.end()
        } finally {
            // 快速换排序键 / 离开组合 / 旋转同一帧换约束时这一轮会被取消，循环不会走到 end()；
            // 若不清掉，state 会永远停在 active=true + 半程进度，之后每帧都按旧矩形裁剪。
            // 只在「仍是当前生效的一轮」时收尾：新一轮已经 begin() 过的话 generation 不同，
            // 这里绝不碰它（否则会把新一轮的旧矩形和时钟一起清空）。正常跑完时 end() 已把
            // active 置 false，这里自然 no-op，不产生多余的状态写入。
            if (state.active && state.generation == ownedGeneration) state.end()
        }
    }
    WenkuReflowViewport(state, modifier, content)
}

/** 只负责视口裁剪的容器；开合动效由调用方自己的时钟驱动（见 [WenkuSplitWorkspace]）。 */
@Composable
private fun WenkuReflowViewport(
    state: WenkuReflowState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalWenkuReflowState provides state) {
        Box(
            modifier
                .onGloballyPositioned { coordinates -> state.observeContainer(coordinates) }
                .drawWithContent {
                    val viewport = state.viewport(size)
                    clipRect(
                        left = viewport.left,
                        top = viewport.top,
                        right = viewport.right,
                        bottom = viewport.bottom
                    ) {
                        this@drawWithContent.drawContent()
                    }
                }
        ) {
            content()
        }
    }
}

/**
 * 双栏工作台（`library_workspace.dart:53-119, 130-243`）：
 * - 宽屏判据 `maxWidth >= 1000dp && maxHeight >= 500dp`；
 * - `total = min(maxWidth, selected && wide ? 1440 : 1200)`、`split = (total - 24) / 2`；
 * - 打开：先 `begin(420ms 正向时钟)`、`value = 0`、下一帧 `forward()`；
 * - 关闭：`begin(反向时钟)`、下一帧 `reverse()`（280ms），详情列按 `progress` 左滑；
 * - 收起后主列 `min(total, 1200)`，因为整体居中，视觉上不会跳。
 */
@Composable
fun WenkuSplitWorkspace(
    showDetail: Boolean,
    modifier: Modifier = Modifier,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit
) {
    val a11y = rememberWenkuAccessibility()
    val reflow = rememberWenkuReflowState()
    // 等价 _motion：正向 420ms、反向 280ms，两端都是 0..1。
    val motion = remember { Animatable(1f) }
    var expanded by remember { mutableStateOf(showDetail) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(showDetail) {
        if (showDetail) {
            if (expanded && !closing) return@LaunchedEffect
            if (!a11y.reduceMotion && (!expanded || closing)) reflow.begin { motion.value }
            expanded = true
            closing = false
            if (a11y.reduceMotion) {
                motion.snapTo(1f)
                return@LaunchedEffect
            }
            motion.snapTo(0f)
            // 新约束先落位，冷目录/冷布局不吃掉第一段动画（library_workspace.dart:95-100）。
            withFrameNanos { }
            motion.animateTo(1f, tween(REFLOW_DURATION_MS, easing = LinearEasing))
        } else {
            if (!expanded) return@LaunchedEffect
            if (a11y.reduceMotion) {
                reflow.end()
                expanded = false
                closing = false
                motion.snapTo(1f)
                return@LaunchedEffect
            }
            if (closing) return@LaunchedEffect
            // 反向时钟：ReverseAnimation(_motion) 即 1 - value，progress 仍是 0 → 1。
            reflow.begin { 1f - motion.value }
            closing = true
            withFrameNanos { }
            motion.animateTo(0f, tween(WORKSPACE_CLOSE_MS, easing = LinearEasing))
            reflow.end()
            expanded = false
            closing = false
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(Modifier.fillMaxHeight()) {
            val wide = maxWidth >= WORKSPACE_WIDE_MIN_WIDTH_DP.dp &&
                maxHeight >= WORKSPACE_WIDE_MIN_HEIGHT_DP.dp
            val selected = expanded
            val cap = if (selected && wide) {
                WORKSPACE_SELECTED_MAX_WIDTH_DP.dp
            } else {
                WORKSPACE_MAX_WIDTH_DP.dp
            }
            val total = if (maxWidth < cap) maxWidth else cap
            val split = (total - WORKSPACE_GAP_DP.dp) / 2
            val master = when {
                selected && wide && !closing -> split
                total < WORKSPACE_MAX_WIDTH_DP.dp -> total
                else -> WORKSPACE_MAX_WIDTH_DP.dp
            }
            val masterLeft = if (closing && wide) (total - master) / 2 else 0.dp
            val detailLeft = if (wide) split + WORKSPACE_GAP_DP.dp else 0.dp
            val listHidden = selected && !wide

            Box(Modifier.width(total).fillMaxHeight().clipToBounds()) {
                // 主列：只在真的收放时才换约束，开合过程中不再动布局。
                Box(
                    Modifier
                        .offset(x = masterLeft)
                        .width(master)
                        .fillMaxHeight()
                        .focusProperties { canFocus = !listHidden }
                        .wenkuHiddenDraw(listHidden)
                        .wenkuBlockWhile { reflow.active }
                ) {
                    WenkuReflowViewport(reflow, Modifier.fillMaxSize()) {
                        list()
                    }
                }

                if (selected) {
                    Box(
                        Modifier
                            .offset(x = detailLeft)
                            .width(total - detailLeft)
                            .fillMaxHeight()
                            // 外层：FractionalTranslation((progress, 0))，关闭时整列向右滑出。
                            .graphicsLayer {
                                val p = if (closing) reflow.progress else 0f
                                translationX = p * size.width
                            }
                            // 内层：ArchivePlaneTransition(kind: incoming) 的斜切揭示 + 层内位移。
                            .wenkuDetailPlane { if (closing) 1f else motion.value }
                            .wenkuBlockWhile { closing }
                    ) {
                        detail()
                    }
                }
            }
        }
    }
}

/** 关闭中的详情列不绘制、不占绘制时间（Flutter 的 `IgnorePointer` 之外的外观不变量）。 */
private fun Modifier.wenkuHiddenDraw(hidden: Boolean): Modifier =
    if (!hidden) this else drawWithContent { /* 隐藏：保留布局，不绘制 */ }

/**
 * `ArchivePlaneTransition.kind == incoming`（`archive_motion.dart:322-347`）：
 * 外层是**静止**的斜切揭示窗口，内层做 `(-24d(1-p), 12(1-p))` 位移，
 * 即「窗口不动、内容在窗口下滑入」。
 */
private fun Modifier.wenkuDetailPlane(
    direction: Float = 1f,
    progress: () -> Float
): Modifier = this
    .drawWithContent {
        val p = progress()
        if (p >= 1f) {
            drawContent()
            return@drawWithContent
        }
        if (p <= 0f) return@drawWithContent
        val slant = min(180.dp.toPx(), size.width * 0.46f)
        val edge = (size.width + slant) * p
        val path = Path()
        if (direction >= 0f) {
            path.moveTo(0f, 0f)
            path.lineTo(edge, 0f)
            path.lineTo(edge - slant, size.height)
            path.lineTo(0f, size.height)
        } else {
            path.moveTo(size.width, 0f)
            path.lineTo(size.width - edge, 0f)
            path.lineTo(size.width - edge + slant, size.height)
            path.lineTo(size.width, size.height)
        }
        path.close()
        clipPath(path) { this@drawWithContent.drawContent() }
    }
    .graphicsLayer {
        val p = progress()
        translationX = -24f * direction * (1f - p) * density
        translationY = 12f * (1f - p) * density
    }

/**
 * 动效期间的指针下沉；等价 Flutter 的 `IgnorePointer(ignoring: ...)`，
 * 但判定在指针事件里做（读 [blocked]），组合阶段不读状态。
 */
private fun Modifier.wenkuBlockWhile(blocked: () -> Boolean): Modifier = composed {
    val current = rememberUpdatedState(blocked)
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!current.value()) return@awaitEachGesture
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { change -> change.consume() }
                pressed = event.changes.any { change -> change.pressed }
            }
        }
    }
}
