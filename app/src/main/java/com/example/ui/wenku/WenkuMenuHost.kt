package com.example.ui.wenku

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Wenku8「玻璃菜单 / 玻璃 sheet」弹层动效：Flutter 参考实现的一比一 Compose 移植。
 *
 * 来源（只读参考仓库 `Wenku8_Flutter_analysis/lib`）：
 * - `motion_ui.dart:687-873`   GlassMenuStretch / GlassMenuStretchRender
 * - `motion_ui.dart:875-1136`  GlassMenuContents / GlassMenuContentsRender
 * - `motion_ui.dart:1138-1188` GlassOverlayTransition（四分支）
 * - `motion_ui.dart:17-40`     NovelMotion（enter 420 / exit 320 / contact 120）
 * - `app_ui.dart:1941-1966`    showJellySheet
 * - `app_ui.dart:1968-1993`    GlassMenuAnchor（打开期间触发控件隐身）
 * - `app_ui.dart:2047-2096`    showGlassMenu
 * - `app_ui.dart:2249-2327`    GlassMenuItem（ArchivePart 错峰参数）
 * - `app_ui.dart:2329-2504`    _GlassOverlayRoute（时长 / barrier / 排布）
 * - `app_ui.dart:2536-2570`    _GlassMenuPlacement（菜单最终矩形）
 * - `app_ui.dart:2579-2727`    _GlassSheet（拖拽关闭 / 抓取条）
 * - `archive_motion.dart:31-64, 179-192, 278-436` ArchivePart / ArchiveReveal / ArchivePlane
 *
 * 核心不变量：**布局恒为最终尺寸，只有 paint transform 随进度变化**。
 * `WenkuMenuStretch` 的子内容只测量/布局一次，动画期间仅改
 * `scaleX/scaleY/translationX/translationY`（`transformOrigin = (0,0)`），
 * 与 Flutter `pushTransform` 的 `translate(current.left,current.top) * scale(...)`
 * 同构；绝不因为进度变化重新测量或重新排版菜单内容。
 *
 * 与 Flutter 的已知差异（均为本项目没有 NativeBackdrop / 原生 morph 层所致，
 * 几何、时钟、阈值完全一致）：
 * 1) 材质（玻璃面）直接用 `GlassMenuContents` 的 viewport 圆角公式绘制
 *    （`motion_ui.dart:961-973`），而不是 `NativeGlassMorph`；
 * 2) 参考实现的「来源图标」前景（`GlassMenuContents` 的 lastChild，
 *    `motion_ui.dart:1086-1092`）不在本文件 API 内——`content` 只承载菜单项，
 *    来源图标由调用方在锚点位置继续绘制（或被 `WenkuMenuTrigger` 隐藏）；
 * 3) `paintBounds.inflate(24)`（`motion_ui.dart:836`）在 Compose 里没有图层
 *    裁剪矩形可写，因此不需要它。
 */

// ---------------------------------------------------------------------------
// 曲线与常量（逐条对应 Dart）
// ---------------------------------------------------------------------------

/** `Curves.easeOut` = Cubic(0, 0, .58, 1)：(motion_ui.dart:948) 的 Interval 曲线。 */
private val WenkuEaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** `Curves.easeInOut` = Cubic(.42, 0, .58, 1)：(motion_ui.dart:950) 的来源图标曲线。 */
private val WenkuEaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** 宽曲线 `Cubic(.18, 1.10, .30, 1)`（motion_ui.dart:753-758）。 */
private val WenkuMenuWidthCurve: Easing = CubicBezierEasing(0.18f, 1.10f, 0.30f, 1f)

/** 高曲线 `Cubic(.22, 1.04, .32, 1)`（motion_ui.dart:759-764）。 */
private val WenkuMenuHeightCurve: Easing = CubicBezierEasing(0.22f, 1.04f, 0.32f, 1f)

/** `Cubic(.18, .72, .18, 1)`：斜切平面（archive_motion.dart:319-320）。 */
private val WenkuPlaneCurve: Easing = CubicBezierEasing(0.18f, 0.72f, 0.18f, 1f)

/** GlassMenuStretchRender.hitTest 的命中阈值 `.15`（motion_ui.dart:860），作用于已缓动的 progress。 */
private const val WenkuMenuHitThreshold = 0.15f

/** GlassMenuContentsRender.hitTest / visitChildrenForSemantics 的阈值 `.34`（motion_ui.dart:1107, 1123）。 */
private const val WenkuMenuContentThreshold = 0.34f

/** 菜单项错峰起点/终点基数（app_ui.dart:2272-2273）。 */
private const val WenkuMenuStaggerStart = 0.06f
private const val WenkuMenuStaggerEnd = 0.62f

/** 菜单项错峰步长与封顶项数（app_ui.dart:2272-2273 的 `order.clamp(0, 8) * .025`）。 */
private const val WenkuMenuStaggerStep = 0.025f
private const val WenkuMenuStaggerMaxOrder = 8

/** 菜单 barrier：kind == menu 时透明（app_ui.dart:2385-2388）。 */
private val WenkuMenuBarrier: Color = Color.Transparent

/** sheet barrier：`Colors.black.withValues(alpha: .16)`（app_ui.dart:2385-2388）。 */
private val WenkuSheetBarrier: Color = WenkuTokens.Scrim

/** 静态相位（reduceMotion / 装饰动效关闭时直接显示，motion_ui.dart:1158）。 */
private val WenkuStaticPhase: () -> Float = { 1f }

/**
 * `GlassMenuContentsRender.debugSourceOpacity`（motion_ui.dart:949-950）：
 * 来源图标 `1 - Interval(0, .22, easeInOut)`。本文件的 API 不含来源图标前景，
 * 需要时由调用方在锚点位置用它给原控件淡出（`GlassMenuContents` 的 lastChild 语义）。
 */
internal fun wenkuMenuSourceAlpha(phase: Float): Float =
    1f - wenkuInterval(phase, 0f, 0.22f, WenkuEaseInOut)

/**
 * 菜单相位（0..1，未缓动）作用域：等价 `ArchiveMotionScope`（archive_motion.dart:8-25）。
 * 未处于菜单内时为 null。
 */
private val LocalWenkuMenuPhase = staticCompositionLocalOf<(() -> Float)?> { null }

// ---------------------------------------------------------------------------
// 公开 API
// ---------------------------------------------------------------------------

/**
 * 玻璃菜单从锚点长成（`GlassMenuStretch` + `GlassMenuContents`）。
 *
 * 契约：**调用方必须把本组件放在 `target.topLeft`**，布局尺寸恒为 `target.size`；
 * 本组件只在 paint 阶段把 `target` 映射回 `Rect.lerp(anchor, target, ease(progress))`
 * 的当前矩形。`anchor` / `target` 使用同一个坐标空间（通常是宿主 Box 的本地 px）。
 *
 * 对照 `motion_ui.dart:687-873` + `875-1136`：
 * - 宽/高分别走 `Cubic(.18,1.10,.30,1)` / `Cubic(.22,1.04,.32,1)`，用**未缓动**的
 *   `progress`（motion_ui.dart:753-770）；
 * - 位置走 `Rect.lerp(anchor, target, easeOutCubic(progress))`（:749）；
 * - 原点贴右 `rect.right - width`，纵向按 `growsDown` 贴顶/贴底（:771-777）；
 * - viewport 圆角 `min(shortestSide/2, lerp(sourceRadius, 28, easeOutCubic))`（:961-973）；
 * - 内容不随进度重新排版：内部用 `scale(size/current)` 反向抵消，菜单项 1:1 像素呈现
 *   （:952-959），垂直居中 `(viewport.height - size.height)/2`（:938-943）。
 *
 * @param progress 0..1 的**线性**路由时钟（对应 PopupRoute 的 420ms 前进 / 320ms 回退）。
 * @param anchor 触发控件的矩形（菜单从它长出、缩回它）。
 * @param target 菜单最终矩形；本组件布局尺寸取 `target.size`。
 */
@Composable
fun WenkuMenuStretch(
    progress: () -> Float,
    anchor: Rect,
    target: Rect,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    // GlassMenuSource.radius 默认 24（app_ui.dart:2192-2205）；showGlassMenu 传 source?.radius ?? 24。
    val sourceRadiusPx = with(density) { 24.dp.toPx() }
    WenkuMenuStretchCore(
        progress = progress,
        anchor = { anchor },
        target = { target },
        sourceRadiusPx = sourceRadiusPx,
        modifier = modifier,
        content = content
    )
}

/**
 * 纯 Compose 弹层宿主（不依赖 `androidx.compose.ui.window.Dialog`，对齐项目既有 Overlay 体系）。
 *
 * 对照 `_GlassOverlayRoute`（app_ui.dart:2329-2504）：
 * - 前进 420ms / 回退 320ms（`NovelMotion.enter` / `exit`，app_ui.dart:2377-2381、motion_ui.dart:17-23）；
 * - barrier 透明（app_ui.dart:2385-2388），但 `barrierDismissible = true`（:2383）→ 外部点击关闭；
 * - 菜单矩形按 `_GlassMenuPlacement` 计算（app_ui.dart:2536-2570，`preferBelow = false`）；
 * - 菜单内容 `padding: EdgeInsets.all(8)` + 可滚动（app_ui.dart:2434-2442）。
 *
 * 返回键与外部点击都由本宿主接管（见下方 `BackHandler` / scrim）。
 *
 * @param visible 是否展开。`false` 时先把缩回动画播完才真正移除内容（对齐 `await route.completed`，
 *                app_ui.dart:2087-2091、2143-2144）。
 * @param anchorBounds 触发控件矩形（宿主本地 px）。为 null 时退化为居中静态呈现（无 morph）。
 * @param sourceRadius 触发控件圆角，参与 viewport 圆角插值（`GlassMenuSource.radius`）。
 */
@Composable
fun WenkuMenuHost(
    visible: Boolean,
    anchorBounds: Rect?,
    sourceRadius: Dp = 24.dp,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissRequested: Boolean = false,
    onHidden: () -> Unit = {},
    borderlessSurface: Color? = null,
    content: @Composable () -> Unit
) {
    val a11y = rememberWenkuAccessibility()
    val decorative = LocalWenkuDecorativeMotion.current
    val animated = !a11y.reduceMotion && decorative

    // 路由时钟：线性 0..1，缓动全部在 stretch 内部完成（motion_ui.dart:721、753-764）。
    val clock = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(visible) }
    var dismissing by remember { mutableStateOf(false) }
    var followUp by remember { mutableStateOf<(() -> Unit)?>(null) }
    val open = visible && !dismissing && !dismissRequested
    val dismiss by rememberUpdatedState(onDismiss)
    val hidden by rememberUpdatedState(onHidden)
    val close: (() -> Unit) -> Unit = { action ->
        if (visible && !dismissing && !dismissRequested) {
            followUp = action
            dismissing = true
        }
    }
    val currentClose by rememberUpdatedState(close)
    LaunchedEffect(visible) {
        if (!visible && !mounted) dismissing = false
    }
    LaunchedEffect(open, animated) {
        if (open) {
            mounted = true
            if (animated) clock.animateTo(1f, tween(WenkuMotion.ENTER_MS, easing = LinearEasing))
            else clock.snapTo(1f)
        } else {
            if (mounted && animated) clock.animateTo(0f, tween(WenkuMotion.EXIT_MS, easing = LinearEasing))
            else clock.snapTo(0f)
            mounted = false
            val action = followUp
            followUp = null
            if (dismissing || dismissRequested) dismiss()
            hidden()
            if (action !== dismiss) action?.invoke()
        }
    }
    if (!mounted && !open) return

    BackHandler { close {} }

    val density = LocalDensity.current
    val sourceRadiusPx = with(density) { sourceRadius.toPx() }
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    // 目标矩形由测量结果决定（菜单高度 = 内容高度）。用 holder 传递：measure 写入 →
    // draw 读取，动画期间零重组、零重排（对应 Flutter 的 RenderProxyBox 重绘）。
    val geometry = remember { WenkuMenuGeometry() }
    val scroll = rememberScrollState()

    Box(modifier = modifier.fillMaxSize()) {
        // scrim：透明但可点（barrierColor = transparent, barrierDismissible = true）。
        Box(
            Modifier
                .fillMaxSize()
                .background(WenkuMenuBarrier)
                .pointerInput(Unit) { detectTapGestures { currentClose {} } }
        )
        Layout(
            content = {
                WenkuMenuStretchCore(
                    progress = { clock.value },
                    anchor = { anchorBounds ?: geometry.target },
                    target = { geometry.target },
                    sourceRadiusPx = sourceRadiusPx,
                    borderlessSurface = borderlessSurface,
                    modifier = Modifier.fillMaxWidth()
                        .then(if (open) Modifier else Modifier.clearAndSetSemantics { }.wenkuBlockPointer())
                ) {
                    // app_ui.dart:2434-2442：SingleChildScrollView(padding: EdgeInsets.all(8))。
                    Box(
                        Modifier
                            .verticalScroll(scroll)
                            .padding(8.dp)
                    ) {
                        CompositionLocalProvider(
                            com.example.ui.components.LocalOverlayClose provides close,
                            com.example.ui.components.LocalPaperHandoff provides close
                        ) { if (animated) WenkuContentClock(clock.asState(), content) else content() }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { measurables, constraints ->
            val safe = wenkuSafeRect(constraints, insets, this, layoutDirection)
            val anchor = anchorBounds
            val edgePx = 24.dp.toPx()
            val area = if (anchor == null) safe else wenkuMenuArea(anchor, safe, 12.dp.toPx(), edgePx)
            val widthPx = min(WenkuTokens.MenuWidth.toPx(), max(0f, area.width))
            val placeable = measurables.first().measure(
                Constraints(
                    minWidth = widthPx.roundToInt(),
                    maxWidth = widthPx.roundToInt(),
                    minHeight = 0,
                    maxHeight = max(0f, area.height).roundToInt()
                )
            )
            val size = Size(placeable.width.toFloat(), placeable.height.toFloat())
            val target = if (anchor == null) {
                // 没有锚点：居中静态呈现（无 morph 来源）。
                Rect(
                    safe.center.x - size.width / 2f,
                    safe.center.y - size.height / 2f,
                    safe.center.x + size.width / 2f,
                    safe.center.y + size.height / 2f
                )
            } else {
                wenkuMenuTargetRect(anchor, size, area)
            }
            geometry.target = target
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(target.left.roundToInt(), target.top.roundToInt())
            }
        }
    }
}

/**
 * 玻璃 sheet 宿主：入场（有锚点走 SizeTransition，无锚点走斜切平面）+ 拖拽关闭。
 *
 * 对照 `GlassOverlayTransition`（motion_ui.dart:1139-1188）与 `_GlassSheet`
 * （app_ui.dart:2579-2727）：
 * - `sheet + anchor` → `SizeTransition(sizeFactor: ease, axisAlignment: 1)`（motion_ui.dart:1171-1176）；
 * - 否则 → `ArchivePlaneTransition(kind: sheet, bottomClearance: viewPadding.bottom + 24)`
 *   （motion_ui.dart:1178-1185、archive_motion.dart:331-334）；
 * - 垂直拖拽 `_drag.value += delta.dy`（app_ui.dart:2627-2628）；
 * - 关闭条件 `primaryVelocity > 550` 或 `位移 > 面板高度 * .24`（:2636-2638）；
 * - 回弹 `animateTo(0, 420ms, easeOutCubic)`（:2613-2619、2617）；
 * - 顶部 32×3 抓取条（:2695-2710）；
 * - 位移 `Transform.translate(0, drag)`（:2682-2686）。
 *
 * 「关闭动画结束才真正移除」：`visible` 变 false 后先跑 320ms 反向转场，转场结束才卸载内容
 * （app_ui.dart:2143-2144）。
 */
@Composable
fun WenkuSheetHost(
    visible: Boolean,
    anchorBounds: Rect?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    grabber: Boolean = true,
    dismissRequested: Boolean = false,
    viewportContent: Boolean = false,
    dismissGesturesEnabled: Boolean = true,
    additionalBottomInsetPx: Int = 0,
    content: @Composable () -> Unit
) {
    val a11y = rememberWenkuAccessibility()
    val decorative = LocalWenkuDecorativeMotion.current
    val animated = !a11y.reduceMotion && decorative

    val clock = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(visible) }
    // 拖拽过阈值 = Navigator.pop：宿主自己播完反向转场再移除（不依赖调用方回写 visible）。
    var dismissing by remember { mutableStateOf(false) }
    var afterClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    val open = visible && !dismissing && !dismissRequested
    var panelHeight by remember { mutableFloatStateOf(0f) }
    val tracker = remember { VelocityTracker() }
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentPanelHeight by rememberUpdatedState(panelHeight)

    val close: (() -> Unit) -> Unit = { action ->
        if (visible && !dismissing && !dismissRequested) {
            afterClose = action
            dismissing = true
        }
    }
    val currentClose by rememberUpdatedState(close)
    val gesturesAllowed by rememberUpdatedState(dismissGesturesEnabled)
    val present = mounted || open
    DisposableEffect(present) {
        if (present) WenkuSheetRegistry.opened()
        onDispose { if (present) WenkuSheetRegistry.closed() }
    }

    LaunchedEffect(visible) {
        if (!visible && !mounted) dismissing = false
    }
    LaunchedEffect(open, animated) {
        if (open) {
            mounted = true
            drag.snapTo(0f)
            if (animated) {
                clock.animateTo(1f, tween(durationMillis = WenkuMotion.ENTER_MS, easing = LinearEasing))
            } else {
                clock.snapTo(1f)
            }
        } else if (mounted) {
            if (animated) {
                clock.animateTo(0f, tween(durationMillis = WenkuMotion.EXIT_MS, easing = LinearEasing))
            } else {
                clock.snapTo(0f)
            }
            drag.snapTo(0f)
            mounted = false
            if (dismissing || dismissRequested) {
                val action = afterClose
                afterClose = null
                val dismiss = currentOnDismiss
                dismiss()
                if (action !== dismiss) action?.invoke()
            }
        }
    }
    if (!mounted && !open) return

    BackHandler(enabled = present) { if (gesturesAllowed) close {} }

    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing.union(WindowInsets(bottom = additionalBottomInsetPx)).asPaddingValues()
    // motion_ui.dart:1182：bottomClearance = MediaQuery.viewPadding.bottom + 24。
    val bottomClearancePx = with(density) { (insets.calculateBottomPadding() + 24.dp).toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    val progress = WenkuPlaneCurve.transform(clock.value.coerceIn(0f, 1f))
                    drawRect(WenkuSheetBarrier.copy(alpha = WenkuSheetBarrier.alpha * progress))
                }
                .pointerInput(Unit) { detectTapGestures { if (gesturesAllowed) currentClose {} } }
        )
        if (viewportContent) {
            // The comic reader owns its panel dimensions and places a side rail on wide screens.
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        val remaining = if (animated) 1f - WenkuPlaneCurve.transform(clock.value) else 0f
                        val sideRail = size.width >= 600.dp.toPx() || size.width > size.height
                        translationX = if (sideRail) size.width * remaining else 0f
                        translationY = if (sideRail) 0f else size.height * remaining
                    }
                    .then(if (open) Modifier else Modifier.clearAndSetSemantics { }.wenkuBlockPointer())
            ) {
                CompositionLocalProvider(
                    com.example.ui.components.LocalOverlayClose provides close,
                    com.example.ui.components.LocalPaperHandoff provides close
                ) { if (animated) WenkuContentClock(clock.asState(), content) else content() }
            }
            return@Box
        }
        Layout(
            content = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // app_ui.dart:2682-2686：Transform.translate(offset: Offset(0, _drag.value))。
                        // 无锚点分支再叠加 archive_motion.dart:331-334 的平面位移。
                        .graphicsLayer {
                            val planeProgress = if (animated) {
                                WenkuPlaneCurve.transform(clock.value.coerceIn(0f, 1f))
                            } else 1f
                            val planeOffset = if (anchorBounds == null) {
                                (size.height + bottomClearancePx) * (1f - planeProgress)
                            } else 0f
                            if (anchorBounds == null) {
                                translationX = -18.dp.toPx() * (1f - planeProgress)
                            }
                            translationY = drag.value + planeOffset
                        }
                        // motion_ui.dart:1171-1176：SizeTransition(sizeFactor = ease, axisAlignment = 1)。
                        .drawWithContent {
                            val raw = if (animated) clock.value.coerceIn(0f, 1f) else 1f
                            if (raw <= 0f) return@drawWithContent
                            if (anchorBounds != null && animated && raw < 1f) {
                                val factor = WenkuMotion.ease.transform(raw)
                                val visibleHeight = size.height * factor
                                clipRect(0f, size.height - visibleHeight, size.width, size.height) {
                                    this@drawWithContent.drawContent()
                                }
                            } else {
                                this@drawWithContent.drawContent()
                            }
                        }
                        .then(if (open) Modifier else Modifier.clearAndSetSemantics { }.wenkuBlockPointer())
                        .onSizeChanged { panelHeight = it.height.toFloat() }
                        .then(
                            if (dismissGesturesEnabled) sheetDragModifier(
                                drag = drag,
                                tracker = tracker,
                                scope = scope,
                                onDismiss = { currentClose {} },
                                velocityThresholdPx = WenkuTokens.SheetDismissVelocity * density.density,
                                panelHeight = { currentPanelHeight }
                            ) else Modifier
                        )
                ) {
                    CompositionLocalProvider(
                        com.example.ui.components.LocalOverlayClose provides close,
                        com.example.ui.components.LocalPaperHandoff provides close
                    ) {
                        WenkuSheetSurface(grabber = grabber) { if (animated) WenkuContentClock(clock.asState(), content) else content() }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { measurables, constraints ->
            // app_ui.dart:2465-2494：宽度 min(440, safe.width - 24)，下沿贴锚点底边。
            val safe = wenkuSafeRect(constraints, insets, this, layoutDirection)
            val cap = if (safe.width >= 600.dp.toPx()) 560.dp else 440.dp
            val widthPx = min(cap.toPx(), max(0f, safe.width - 24.dp.toPx()))
            val bottomEdge = if (anchorBounds != null) min(anchorBounds.bottom, safe.bottom) else safe.bottom - 8.dp.toPx()
            val maxHeightPx = max(0f, bottomEdge - safe.top - 22.dp.toPx())
            val placeable = measurables.first().measure(
                Constraints(
                    minWidth = widthPx.roundToInt(),
                    maxWidth = widthPx.roundToInt(),
                    minHeight = 0,
                    maxHeight = maxHeightPx.roundToInt()
                )
            )
            val left = safe.center.x - widthPx / 2f
            val top = bottomEdge - placeable.height
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(left.roundToInt(), top.roundToInt())
            }
        }
    }
}

/**
 * 菜单项错峰揭示：`ArchivePart`（archive_motion.dart:31-64）的 paint-only 等价物。
 *
 * 数值：`start = .06 + min(order, 8) * .025`、`end = .62 + min(order, 8) * .025`
 * （app_ui.dart:2272-2273 覆盖了 ArchivePart 默认的 `.08 / .72`），
 * 区间内走 `Curves.easeOutCubic`（archive_motion.dart:179-192），
 * 轴向 horizontal、reverse = false → 从左到右的矩形擦除（:187-192, 213-242）。
 * 无位移（`ArchivePart.travel` 默认 `Offset.zero`，:38）。
 *
 * 只在 `WenkuMenuStretch` / `WenkuMenuHost` 内部生效；无菜单相位、reduceMotion
 * 或装饰动效关闭时直接返回内容（archive_motion.dart:48-55）。
 */
@Composable
fun WenkuStaggerPart(
    order: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val phase = LocalWenkuMenuPhase.current
    val a11y = rememberWenkuAccessibility()
    val decorative = LocalWenkuDecorativeMotion.current
    if (phase == null || a11y.reduceMotion || !decorative) {
        Box(modifier) { content() }
        return
    }
    val band = order.coerceIn(0, WenkuMenuStaggerMaxOrder)
    val start = WenkuMenuStaggerStart + band * WenkuMenuStaggerStep
    val end = WenkuMenuStaggerEnd + band * WenkuMenuStaggerStep
    Box(
        modifier = modifier.drawWithContent {
            val p = wenkuRevealProgress(phase(), start, end)
            if (p <= 0f) return@drawWithContent
            if (p >= 1f) {
                drawContent()
                return@drawWithContent
            }
            clipRect(0f, 0f, size.width * p, size.height) { this@drawWithContent.drawContent() }
        }
    ) {
        content()
    }
}

/**
 * 触发控件在菜单打开期间隐身：`IgnorePointer + ExcludeSemantics + Opacity(0)`
 * （app_ui.dart:1978-1993），布局槽位保持不变（菜单因此能从原位置长出）。
 * 打开期间同时屏蔽语义（:1988-1989）与指针（:1986-1987）。
 */
@Composable
fun WenkuMenuTrigger(
    open: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .alpha(if (open) 0f else 1f)
            .then(if (open) Modifier.clearAndSetSemantics { } else Modifier)
            // 复用同一个指针闸门：phase(0) < 1 → 吞掉全部指针事件。
            .then(if (open) Modifier.wenkuPointerGate(1f) { 0f } else Modifier)
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// 核心：GlassMenuStretch + GlassMenuContents
// ---------------------------------------------------------------------------

/**
 * 目标矩形持有者：measure 阶段写入（宿主算出的最终矩形），paint 阶段读取。
 * 刻意不用 `mutableStateOf`——写它不该触发重组，只需要下一帧绘制时取到新值。
 */
private class WenkuMenuGeometry {
    var target: Rect = Rect.Zero
}

/**
 * `GlassMenuStretchRender`（motion_ui.dart:711-873）+ `GlassMenuContentsRender`
 * （motion_ui.dart:912-1136）的合成实现。
 *
 * 绘制分两层（与 Flutter 的 layer 栈同构）：
 * 1. 外层 `graphicsLayer`：`translate(current.topLeft - target.topLeft) * scale(current/target)`，
 *    原点 (0,0)，把「最终尺寸的布局盒」压回 anchor→target 的当前矩形；
 * 2. 内层 `drawWithContent`：先 `scale(size/current)` 反向抵消（对应 `_unscale`，
 *    motion_ui.dart:952-959），再按 viewport 尺寸裁剪出圆角材质并画内容，
 *    内容垂直居中 `(viewport.height - size.height)/2`（:938-943）——
 *    因此菜单项始终按 1:1 像素绘制，不会被进度挤压变形。
 */
@Composable
private fun WenkuMenuStretchCore(
    progress: () -> Float,
    anchor: () -> Rect,
    target: () -> Rect,
    sourceRadiusPx: Float,
    modifier: Modifier = Modifier,
    borderlessSurface: Color? = null,
    content: @Composable () -> Unit
) {
    val a11y = rememberWenkuAccessibility()
    val decorative = LocalWenkuDecorativeMotion.current
    val animated = !a11y.reduceMotion && decorative
    val colors = wenkuColors()
    val density = LocalDensity.current
    val finalRadiusPx = with(density) { WenkuTokens.GlassRadius.toPx() }
    val hairlinePx = with(density) { 0.7.dp.toPx() }

    // reduceMotion / 装饰动效关闭：直接以最终形态显示（motion_ui.dart:1158）。
    val phase: () -> Float = if (animated) progress else WenkuStaticPhase
    val resolvedAnchor: () -> Rect = if (animated) anchor else target

    Box(
        modifier = modifier
            .graphicsLayer {
                val goal = target()
                if (goal.width <= 0f || goal.height <= 0f) return@graphicsLayer
                val current = wenkuMenuCurrentRect(phase(), resolvedAnchor(), goal)
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = current.width / goal.width
                scaleY = current.height / goal.height
                translationX = current.left - goal.left
                translationY = current.top - goal.top
            }
            .drawWithContent {
                val goal = target()
                if (goal.width <= 0f || goal.height <= 0f) return@drawWithContent
                val current = wenkuMenuCurrentRect(phase(), resolvedAnchor(), goal)
                val viewportWidth = current.width
                val viewportHeight = current.height
                if (viewportWidth <= 0f || viewportHeight <= 0f) return@drawWithContent
                val radius = wenkuMenuViewportRadius(
                    phase = phase(),
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    sourceRadiusPx = sourceRadiusPx,
                    finalRadiusPx = finalRadiusPx
                )
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            0f,
                            0f,
                            viewportWidth,
                            viewportHeight,
                            CornerRadius(radius, radius)
                        )
                    )
                }
                // _unscale = diag(size.width / viewport.width, size.height / viewport.height)
                // （motion_ui.dart:952-956）；translate 在 scale 之后调用 = 子空间位移，
                // 与 Matrix4(_unscale)..translate(offset) 的乘法顺序一致（:958-959）。
                withTransform({
                    scale(size.width / viewportWidth, size.height / viewportHeight, pivot = Offset.Zero)
                    translate(0f, (viewportHeight - size.height) / 2f)
                }) {
                    clipPath(path) {
                        drawRect(borderlessSurface ?: colors.panel, topLeft = Offset.Zero, size = Size(viewportWidth, viewportHeight))
                        // 嵌套在 DrawScope(lambda) 里必须显式取回 ContentDrawScope 接收者。
                        this@drawWithContent.drawContent()
                    }
                    // 材质描边（LiquidGlass hairline；不使用 blur/backdrop）。
                    if (borderlessSurface == null) {
                        drawPath(
                            path,
                            colors.outline.copy(alpha = if (colors.isDark) 0.55f else 0.42f),
                            style = Stroke(hairlinePx)
                        )
                    }
                }
            }
            // GlassMenuStretchRender.hitTest：progress < .15 时不参与命中（motion_ui.dart:860）。
            // 这里只挡指针，不改布局。
            .then(
                if (animated) {
                    Modifier.wenkuPointerGate(WenkuMenuHitThreshold) { wenkuEaseOutCubic(phase()) }
                } else {
                    Modifier
                }
            )
    ) {
        CompositionLocalProvider(LocalWenkuMenuPhase provides phase) {
            // 菜单不透明度 Interval(.06, .34, easeOut)（motion_ui.dart:947-948）。
            // 来源图标透明度 (motion_ui.dart:949-950) 由 wenkuMenuSourceAlpha 提供。
            Box(Modifier.graphicsLayer { alpha = wenkuMenuContentAlpha(phase()) }) {
                // 内容命中/语义阈值 .34（motion_ui.dart:1107-1124）。
                Box(
                    Modifier.then(
                        if (animated) {
                            Modifier.wenkuPointerGate(WenkuMenuContentThreshold) { phase() }
                        } else {
                            Modifier
                        }
                    )
                ) {
                    content()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 几何与曲线（Dart 公式逐条移植）
// ---------------------------------------------------------------------------

private fun wenkuLerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

private fun wenkuLerpRect(start: Rect, stop: Rect, fraction: Float): Rect = Rect(
    wenkuLerp(start.left, stop.left, fraction),
    wenkuLerp(start.top, stop.top, fraction),
    wenkuLerp(start.right, stop.right, fraction),
    wenkuLerp(start.bottom, stop.bottom, fraction)
)

private fun wenkuEaseOutCubic(raw: Float): Float = WenkuMotion.ease.transform(raw.coerceIn(0f, 1f))

/** Flutter `Interval(begin, end, curve)` 的等价实现。 */
private fun wenkuInterval(raw: Float, start: Float, end: Float, curve: Easing): Float {
    if (end <= start) return if (raw >= end) 1f else 0f
    val local = ((raw - start) / (end - start)).coerceIn(0f, 1f)
    return curve.transform(local).coerceIn(0f, 1f)
}

/** `ArchiveReveal.progress`：`Interval(start, end, easeOutCubic)`（archive_motion.dart:179-183）。 */
private fun wenkuRevealProgress(raw: Float, start: Float, end: Float): Float =
    wenkuInterval(raw, start, end, WenkuMotion.ease)

/**
 * `GlassMenuStretchRender.currentRect`（motion_ui.dart:747-778）。
 *
 * 位置用 `Rect.lerp(anchor, target, easeOutCubic(raw))`，宽高各自用独立的宽/高曲线，
 * 全部取**未缓动**的原始时钟；`growsDown` 决定纵向贴顶还是贴底，横向永远贴右
 * （`rect.right - width`）。
 */
private fun wenkuMenuCurrentRect(raw: Float, anchor: Rect, target: Rect): Rect {
    val phase = raw.coerceIn(0f, 1f)
    val eased = WenkuMotion.ease.transform(phase)                  // motion_ui.dart:721
    val lerped = wenkuLerpRect(anchor, target, eased)              // motion_ui.dart:749
    val widthProgress = WenkuMenuWidthCurve.transform(phase)       // motion_ui.dart:753-758
    val heightProgress = WenkuMenuHeightCurve.transform(phase)     // motion_ui.dart:759-764
    val width = wenkuLerp(anchor.width, target.width, widthProgress)     // :765-770
    val height = wenkuLerp(anchor.height, target.height, heightProgress) // :766-770
    val growsDown = target.center.y >= anchor.center.y             // motion_ui.dart:771
    val left = lerped.right - width                                // motion_ui.dart:773
    val top = if (growsDown) lerped.top else lerped.bottom - height // motion_ui.dart:774
    return Rect(left, top, left + width, top + height)
}

/** `GlassMenuContentsRender.debugMenuOpacity`（motion_ui.dart:947-948）。 */
private fun wenkuMenuContentAlpha(phase: Float): Float =
    wenkuInterval(phase, 0.06f, 0.34f, WenkuEaseOut)

/** `GlassMenuContentsRender._viewportClip` 的圆角（motion_ui.dart:961-973）。 */
private fun wenkuMenuViewportRadius(
    phase: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    sourceRadiusPx: Float,
    finalRadiusPx: Float
): Float {
    val shortestSide = min(viewportWidth, viewportHeight)
    val grown = wenkuLerp(sourceRadiusPx, finalRadiusPx, wenkuEaseOutCubic(phase))
    return min(shortestSide / 2f, grown)
}

/** `_popupSafeRect`（app_ui.dart:2508-2534）的 Compose 等价物：安全区内缩后的可用区。 */
private fun wenkuSafeRect(
    constraints: Constraints,
    insets: PaddingValues,
    density: Density,
    layoutDirection: LayoutDirection
): Rect {
    val left = with(density) { insets.calculateLeftPadding(layoutDirection).toPx() }
    val top = with(density) { insets.calculateTopPadding().toPx() }
    val right = constraints.maxWidth - with(density) { insets.calculateRightPadding(layoutDirection).toPx() }
    val bottom = constraints.maxHeight - with(density) { insets.calculateBottomPadding().toPx() }
    return Rect(left, top, max(left, right), max(top, bottom))
}

/** `_GlassMenuPlacement.available`（app_ui.dart:2541-2546）。 */
private fun wenkuMenuArea(anchor: Rect, safe: Rect, insetPx: Float, edgePx: Float): Rect {
    val left = safe.left + min(insetPx, safe.width / 2f)
    val top = min(safe.top + min(insetPx, safe.height / 2f), max(0f, anchor.top))
    return Rect(left, top, left + max(0f, safe.width - edgePx), top + max(0f, safe.height - edgePx))
}

/**
 * `_GlassMenuPlacement.getPositionForChild`（app_ui.dart:2556-2570），
 * `preferBelow = false`（showGlassMenu 默认值，app_ui.dart:2050-2051、2411）：
 * 横向贴锚点右边，纵向优先从 anchor.top 向下，放不下再翻到 anchor.bottom 上方。
 */
private fun wenkuMenuTargetRect(anchor: Rect, child: Size, area: Rect): Rect {
    val maxLeft = max(area.left, area.right - child.width)
    val x = (anchor.right - child.width).coerceIn(area.left, maxLeft)
    val below = anchor.top
    val rawY = if (below + child.height <= area.bottom) below else anchor.bottom - child.height
    val y = rawY.coerceIn(area.top, max(area.top, area.bottom - child.height))
    return Rect(x, y, x + child.width, y + child.height)
}

// ---------------------------------------------------------------------------
// 指针闸门 / 拖拽
// ---------------------------------------------------------------------------

/**
 * 命中闸门：`phase() < threshold` 时在 Initial pass 吞掉整段手势，
 * 对齐 Flutter `hitTest` 返回 false（motion_ui.dart:860、1107）。
 * 不消费时不拦截，子节点照常收到事件。
 */
private fun Modifier.wenkuPointerGate(
    threshold: Float,
    phase: () -> Float
): Modifier = this.pointerInput(threshold, phase) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (phase() < threshold) {
            down.consume()
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                pressed = event.changes.any { it.pressed }
            }
        }
    }
}

/**
 * `_GlassSheetState` 的拖拽（app_ui.dart:2622-2689）：
 * - `onVerticalDragStart → _drag.stop()`（:2626）；
 * - `onVerticalDragUpdate → _drag.value = max(0, _drag.value + delta.dy)`（:2627-2628）；
 * - `onVerticalDragEnd`：`primaryVelocity > 550` 或 `value > 面板高度 * .24` → 关闭（:2636-2648）；
 *   否则 `_restore()`（:2649-2651）；
 * - `onVerticalDragCancel → _restore()`（:2653）；
 * - `_restore = animateTo(0, 420ms, ease)`（:2613-2619）。
 *
 * velocity 必须来自 `VelocityTracker`（Dart 用 `details.primaryVelocity`）：同一段手势的
 * 采样点喂 tracker，`calculateVelocity().y` 即 primaryVelocity。
 */
private fun sheetDragModifier(
    drag: Animatable<Float, AnimationVector1D>,
    tracker: VelocityTracker,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    velocityThresholdPx: Float,
    panelHeight: () -> Float
): Modifier = Modifier
    .pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            tracker.resetTracking()
            tracker.addPosition(down.uptimeMillis, down.position)
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change != null) tracker.addPosition(change.uptimeMillis, change.position)
                pressed = event.changes.any { it.pressed }
            }
        }
    }
    .pointerInput(velocityThresholdPx) {
        // 手势内的累计位移与 Dart `_drag.value += delta.dy` 同步（app_ui.dart:2627-2628）。
        var dragged = 0f
        detectVerticalDragGestures(
            onDragStart = {
                dragged = drag.value
                scope.launch { drag.stop() }
            },
            onVerticalDrag = { _, delta ->
                dragged = max(0f, dragged + delta)
                scope.launch { drag.snapTo(dragged) }
            },
            onDragEnd = {
                val primaryVelocity = tracker.calculateVelocity().y
                val height = panelHeight()
                val passed = primaryVelocity > velocityThresholdPx ||
                    dragged > height * WenkuTokens.SheetDismissFraction
                if (passed) {
                    // Navigator.pop()：反向转场由宿主播完再移除（app_ui.dart:2639-2648）。
                    onDismiss()
                } else {
                    scope.launch {
                        drag.animateTo(
                            0f,
                            tween(durationMillis = WenkuMotion.ENTER_MS, easing = WenkuMotion.ease)
                        )
                    }
                }
            },
            onDragCancel = {
                scope.launch {
                    drag.animateTo(
                        0f,
                        tween(durationMillis = WenkuMotion.ENTER_MS, easing = WenkuMotion.ease)
                    )
                }
            }
        )
    }

// ---------------------------------------------------------------------------
// sheet 材质
// ---------------------------------------------------------------------------

/** 玻璃 sheet 表面：28dp 圆角面板 + 顶部 32×3 抓取条（app_ui.dart:2691-2711）。 */
@Composable
private fun WenkuSheetSurface(
    grabber: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = wenkuColors()
    val density = LocalDensity.current
    val radiusPx = with(density) { WenkuTokens.SheetRadius.toPx() }
    val hairlinePx = with(density) { 0.7.dp.toPx() }
    Column(
        modifier = modifier
            .drawWithCache {
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(0f, 0f, size.width, size.height, CornerRadius(radiusPx, radiusPx))
                    )
                }
                onDrawWithContent {
                    clipPath(path) {
                        drawRect(colors.panel, topLeft = Offset.Zero, size = size)
                        this@onDrawWithContent.drawContent()
                    }
                    drawPath(
                        path,
                        colors.outline.copy(alpha = if (colors.isDark) 0.55f else 0.42f),
                        style = Stroke(hairlinePx)
                    )
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (grabber) {
            Spacer(Modifier.height(12.dp))                                      // app_ui.dart:2695
            Box(
                Modifier
                    .width(WenkuTokens.SheetHandleWidth)                        // 32
                    .height(WenkuTokens.SheetHandleHeight)                      // 3
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.ink.copy(alpha = 0.18f))                 // app_ui.dart:2701-2710
            )
        }
        content()
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 窗口版 [WenkuSheetHost]：给仍然以「弹窗」语义被调用的 sheet 用。
 *
 * `PullOutBottomSheet` 的 12 个调用点散在阅读器/书库/缓存/帮助等页面，直接换成路由内的
 * 宿主需要每个调用点都改成「常驻 + 翻 visible」；这里用一个全屏透明 Dialog 承载同一个
 * 宿主，调用点不用动，视觉/手势却已经是 Wenku 的玻璃面板。
 */
@Composable
fun WenkuSheetDialog(
    onDismissRequest: () -> Unit,
    grabber: Boolean = true,
    dismissGesturesEnabled: Boolean = true,
    fitKeyboard: Boolean = false,
    requestDismiss: Boolean = false,
    content: @Composable () -> Unit
) {
    var dismissRequested by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (dismissGesturesEnabled) dismissRequested = true },
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false
        )
    ) {
        val window = (androidx.compose.ui.platform.LocalView.current.parent
            as? androidx.compose.ui.window.DialogWindowProvider)?.window
        // 遮罩由宿主的 WenkuSheetBarrier 负责，窗口自己不要再压一层灰。
        var bottomInset by remember { mutableIntStateOf(0) }
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        DisposableEffect(window, fitKeyboard) {
            val decor = window?.decorView
            if (window != null && decor != null) {
                val types = androidx.core.view.WindowInsetsCompat.Type.navigationBars() or
                    (if (fitKeyboard) androidx.core.view.WindowInsetsCompat.Type.ime() else 0)
                window.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    window.attributes = window.attributes.apply { fitInsetsTypes = 0 }
                    if (fitKeyboard) window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                } else if (fitKeyboard) {
                    window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(decor) { view, insets ->
                    bottomInset = insets.getInsets(types).bottom
                    androidx.core.view.ViewCompat.onApplyWindowInsets(view, insets)
                }
                androidx.core.view.ViewCompat.requestApplyInsets(decor)
            }
            onDispose { decor?.let { androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(it, null) } }
        }
        WenkuSheetHost(
            visible = true,
            anchorBounds = null,
            onDismiss = onDismissRequest,
            grabber = grabber,
            dismissRequested = dismissRequested || requestDismiss,
            dismissGesturesEnabled = dismissGesturesEnabled,
            additionalBottomInsetPx = bottomInset,
            modifier = Modifier.fillMaxSize()
        ) {
            content()
        }
    }
}

/**
 * 「当前打开的玻璃 sheet 数量」登记表。
 *
 * [WenkuSheetHost] 组合在路由内部，而底部 Tab 栏组合在 NavHost 之后的根 Box 里，
 * z 序天然高于 sheet；不登记的话面板底部（尤其「重试」按钮）会被 Tab 栏压住。
 */
object WenkuSheetRegistry {

    var openCount by mutableStateOf(0)
        private set

    val anyOpen: Boolean get() = openCount > 0

    fun opened() { openCount++ }

    fun closed() { openCount = (openCount - 1).coerceAtLeast(0) }
}
