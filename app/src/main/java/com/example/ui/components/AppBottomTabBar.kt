package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.LocalRenderQuality
import com.example.ui.theme.onColor
import com.example.ui.wenku.WenkuMotion
import com.example.ui.wenku.WenkuTokens
import com.example.ui.wenku.wenkuGlassBlur
import com.example.ui.wenku.rememberWenkuAccessibility
import com.example.ui.wenku.wenkuColors
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.blur
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

data class AppTabItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector = icon,
    val badgeCount: Int? = null,
    /** Wenku ArchiveSymbol：非空时 Dock 用参考仓库的手绘纸张图标（自带入场致意 + 按压手势）。 */
    val wenkuSymbol: com.example.ui.wenku.WenkuSymbol? = null,
    val wenkuSelectedSymbol: com.example.ui.wenku.WenkuSymbol? = wenkuSymbol
)

/** 滚动收缩状态：由外部列表的 NestedScrollConnection 驱动，多个 Tab 页共享一份。 */
class TabBarCollapseState {
    var collapsed by mutableStateOf(false)
        private set
    fun connection(): NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            if (available.y < -4f) collapsed = true
            if (available.y > 4f) collapsed = false
            return Offset.Zero
        }
    }
}

@Composable
fun rememberTabBarCollapseState(): TabBarCollapseState = remember { TabBarCollapseState() }

/**
 * Wenku8 玻璃 Dock。
 *
 * 文档 §5.3 的交互全部落在这里：
 * - 按下立即响应（120ms）；
 * - 跟手 80%；
 * - 形变 `speed * .028` clamp ±24，lerp .55，100ms 无输入回 0；
 * - 松手投射 `velocity * .055` clamp ±.22；
 * - 胶囊 `translate(flow*.16, -pressure*.6)`，`scaleX = 1 + p*.022 + |flow|*.00125`；
 * - 玻璃厚度 16 + pressure*2。
 *
 * 逐帧值只在 graphicsLayer / draw 阶段读取，不重建 Tab 内容。
 */
@Composable
fun AppBottomTabBar(
    items: List<AppTabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    collapseState: TabBarCollapseState = rememberTabBarCollapseState(),
    backdrop: Backdrop? = null
) {
    val a11y = rememberWenkuAccessibility()
    val colors = wenkuColors()
    if (LocalRenderQuality.current.minimal || a11y.highContrast) {
        Row(
            modifier.fillMaxWidth().navigationBarsPadding()
                .background(colors.panel)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(4.dp))
                        .background(if (index == selectedIndex) colors.raised else Color.Transparent)
                        .pullOutClickable { onTabSelected(index) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        if (index == selectedIndex) item.selectedIcon else item.icon,
                        item.label,
                        modifier = Modifier.size(22.dp),
                        tint = colors.ink
                    )
                    Text(item.label, fontSize = 10.sp, color = colors.ink)
                }
            }
        }
        return
    }

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val selected by rememberUpdatedState(selectedIndex)
    val latestSelect by rememberUpdatedState(onTabSelected)
    val targetIndicator = selectedIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)).toFloat()
    val indicator = remember { Animatable(targetIndicator) }
    val dockDragging = remember { mutableStateOf(false) }
    LaunchedEffect(targetIndicator, items.size) {
        if (!dockDragging.value) {
            indicator.animateTo(targetIndicator, WenkuMotion.spring())
        }
    }
    val press = remember { Animatable(0f) }
    val stretch = remember { Animatable(0f) }
    var fingerSlot by remember { mutableFloatStateOf(targetIndicator) }
    var resetJob by remember { mutableStateOf<Job?>(null) }

    val collapseProgress by animateFloatAsState(
        targetValue = if (collapseState.collapsed) 1f else 0f,
        animationSpec = WenkuMotion.spring(),
        label = "wenkuDockCollapse"
    )
    val labelFraction = 1f - collapseProgress.coerceIn(0f, 1f)
    val barHeight = 56.dp - 4.dp * collapseProgress.coerceIn(0f, 1f)
    val selectedColor = colors.ink
    val shape = RoundedCornerShape(28.dp)

    // 安全区 + 6dp 外缩，浮在内容之上。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .graphicsLayer {
                    val pressure = press.value
                    val flow = stretch.value
                    translationX = flow * .16f
                    translationY = -pressure * .6f
                    scaleX = 1f + pressure * .022f + abs(flow) * .00125f
                    scaleY = 1f + pressure * .065f - abs(flow) * .0006f
                }
                .testTag("app_tab_bar_surface")
                .pointerInput(items.size, layoutDirection) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val slotWidth = width / items.size.coerceAtLeast(1)
                        val rawSlot = down.position.x / slotWidth - .5f
                        val touchSlot = if (layoutDirection == LayoutDirection.Rtl) {
                            items.lastIndex - rawSlot
                        } else rawSlot
                        fingerSlot = touchSlot.roundToIntCompat().coerceIn(0, items.lastIndex.coerceAtLeast(0)).toFloat()
                        dockDragging.value = true
                        scope.launch {
                            press.animateTo(1f, tween(WenkuMotion.CONTACT_MS, easing = WenkuMotion.ease))
                        }
                        scope.launch {
                            indicator.animateTo(fingerSlot, WenkuMotion.spring())
                        }
                        var pointerDown = true
                        var moved = false
                        var lastX = down.position.x
                        var lastUptime = down.uptimeMillis
                        while (pointerDown) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (moved) change.consume()
                                pointerDown = false
                                tracker.addPosition(change.uptimeMillis, change.position)
                                break
                            }
                            val dx = change.position.x - lastX
                            val dt = (change.uptimeMillis - lastUptime).coerceAtLeast(1L)
                            val vx = dx / (dt / 1000f)
                            lastX = change.position.x
                            lastUptime = change.uptimeMillis
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (!moved && abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) {
                                moved = true
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            if (!moved) continue
                            change.consume()
                            val target = (vx * WenkuMotion.DOCK_STRETCH_FACTOR)
                                .coerceIn(-WenkuMotion.DOCK_STRETCH_LIMIT, WenkuMotion.DOCK_STRETCH_LIMIT)
                            scope.launch {
                                stretch.snapTo(stretch.value + (target - stretch.value) * WenkuMotion.DOCK_STRETCH_LERP)
                            }
                            val raw = change.position.x / slotWidth - .5f
                            val slot = if (layoutDirection == LayoutDirection.Rtl) items.lastIndex - raw else raw
                            fingerSlot = slot.coerceIn(0f, items.lastIndex.coerceAtLeast(0).toFloat())
                            scope.launch {
                                indicator.snapTo(indicator.value + (fingerSlot - indicator.value) * WenkuMotion.DOCK_FOLLOW)
                            }
                            resetJob?.cancel()
                            resetJob = scope.launch {
                                delay(WenkuMotion.DOCK_IDLE_MS)
                                stretch.animateTo(0f, WenkuMotion.spring())
                            }
                        }
                        val velocity = tracker.calculateVelocity().x
                        val projected = fingerSlot + (velocity * WenkuMotion.DOCK_PROJECT / slotWidth)
                            .coerceIn(-WenkuMotion.DOCK_PROJECT_LIMIT, WenkuMotion.DOCK_PROJECT_LIMIT)
                        val target = projected.roundToIntCompat().coerceIn(0, items.lastIndex.coerceAtLeast(0))
                        resetJob?.cancel()
                        scope.launch { stretch.animateTo(0f, WenkuMotion.spring()) }
                        scope.launch { press.animateTo(0f, WenkuMotion.spring()) }
                        dockDragging.value = false
                        if (moved && target != selected) {
                            latestSelect(target)
                        } else {
                            scope.launch {
                                indicator.animateTo(selected.toFloat(), WenkuMotion.spring())
                            }
                        }
                    }
                }
        ) {
            // 玻璃单独一层 layer：指示条/按压缩放这些逐帧变化只重绘自己的层，
            // 不会把 drawBackdrop 的整屏采样 + 模糊一起重录（点 Tab 卡顿的主因）。
            Box(
                Modifier.matchParentSize().graphicsLayer { }
                    .then(
                        if (backdrop != null && a11y.blurEnabled) {
                            remember(backdrop, shape, colors.isDark, colors.panel, colors.ink) {
                                Modifier.drawBackdrop(
                                    backdrop = backdrop!!,
                                    shape = { shape },
                                    effects = { wenkuGlassBlur(16.dp.toPx()) },
                                    highlight = { null },
                                    shadow = { null },
                                    onDrawSurface = {
                                        drawRect(
                                            if (colors.isDark) colors.panel.copy(alpha = 0.78f)
                                            else colors.panel.copy(alpha = 0.70f)
                                        )
                                    },
                                    onDrawFront = {
                                        drawRoundRect(
                                            color = colors.ink.copy(alpha = 0.12f),
                                            cornerRadius = CornerRadius(size.height / 2f),
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(0.7.dp.toPx())
                                        )
                                    }
                                )
                            }
                        } else {
                            Modifier
                                .clip(shape)
                                .background(colors.panel.copy(alpha = 0.96f))
                                .border(0.7.dp, colors.ink.copy(alpha = 0.12f), shape)
                        }
                    )
            )
            // 指示条 + 手指高亮：同样独占一层，弹簧每帧只重绘这一层。
            Box(
                Modifier.matchParentSize().graphicsLayer { }.drawWithContent {
                    val slotWidth = size.width / items.size.coerceAtLeast(1)
                    val slot = indicator.value.coerceIn(0f, (items.size - 1).coerceAtLeast(0).toFloat())
                    val slotX = if (layoutDirection == LayoutDirection.Rtl) {
                        size.width - (slot + .5f) * slotWidth
                    } else {
                        (slot + .5f) * slotWidth
                    }
                    val height = size.height - 8.dp.toPx()
                    val width = (slotWidth - 8.dp.toPx()).coerceAtMost(52.dp.toPx()).coerceAtLeast(1f)
                    val pressure = press.value.coerceIn(0f, 1f)
                    drawRoundRect(
                        color = colors.ink.copy(alpha = .07f + .045f * pressure),
                        topLeft = Offset(slotX - width / 2f, (size.height - height) / 2f),
                        size = Size(width, height),
                        cornerRadius = CornerRadius(height / 2f)
                    )
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        DockTab(
                            item = item,
                            selected = index == selectedIndex,
                            labelFraction = labelFraction,
                            ink = selectedColor,
                            onClick = { onTabSelected(index) }
                        )
                    }
                }
            }
        }
    }
}

internal val AppRailWidth = 96.dp

/** Wide windows use the same animated tab glyphs and actions as the compact dock. */
@Composable
internal fun AppNavigationRail(
    items: List<AppTabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
) {
    val colors = wenkuColors()
    val reduce = rememberWenkuAccessibility().reduceMotion
    val position = animateDpAsState(
        targetValue = 56.dp * selectedIndex,
        animationSpec = if (reduce) tween(0) else WenkuMotion.spring(),
        label = "railSelection",
    )
    com.example.ui.wenku.WenkuGlassSurface(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            .width(AppRailWidth - 20.dp).testTag("app_tab_rail"),
        shape = RoundedCornerShape(28.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Box(
                Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(0, position.value.roundToPx()) }
                    .height(56.dp).clip(RoundedCornerShape(20.dp))
                    .background(colors.ink.copy(alpha = .09f)),
            )
            Column(Modifier.fillMaxWidth()) {
                items.forEachIndexed { index, item ->
                    DockTab(item, index == selectedIndex, labelFraction = 1f, ink = colors.ink) {
                        onTabSelected(index)
                    }
                }
            }
        }
    }
}

@Composable
private fun DockTab(
    item: AppTabItem,
    selected: Boolean,
    labelFraction: Float,
    ink: Color,
    onClick: () -> Unit
) {
    val colors = wenkuColors()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.10f else 1f,
        animationSpec = WenkuMotion.spring(),
        label = "dockIconScale"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) .97f else 1f,
        animationSpec = if (pressed) tween(120, easing = WenkuMotion.ease) else WenkuMotion.spring(),
        label = "dockPress"
    )
    var activation by remember { mutableIntStateOf(0) }
    LaunchedEffect(pressed) {
        if (pressed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    // 颜色变化改成 alpha 动画，并且在 graphicsLayer 里读：换 Tab 时 DockTab 不再逐帧重组
    // （颜色动画走 composition，一帧一次重组 × 5 个 Tab 是点按卡顿的另一半原因）。
    val iconAlpha = animateFloatAsState(
        targetValue = if (selected) 1f else .46f,
        animationSpec = tween(220, easing = WenkuMotion.ease),
        label = "dockIconAlpha"
    )
    val labelAlpha = animateFloatAsState(
        targetValue = if (selected) .88f else .42f,
        animationSpec = tween(220, easing = WenkuMotion.ease),
        label = "dockLabelAlpha"
    )
    val labelHeight = 14.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = selected
                contentDescription = item.label
            }
            .pullOutClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = { activation++; onClick() }
            )
            .padding(top = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = pressScale * iconScale
                scaleY = scaleX
                alpha = iconAlpha.value
            },
            contentAlignment = Alignment.Center
        ) {
            val wenkuSymbol = if (selected) item.wenkuSelectedSymbol else item.wenkuSymbol
            if (wenkuSymbol != null) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.example.ui.wenku.LocalWenkuIconContact provides com.example.ui.wenku.WenkuIconContact(pressed, activation)
                ) {
                    com.example.ui.wenku.WenkuIcon(
                        symbol = wenkuSymbol,
                        size = 21.dp,
                        tint = ink,
                        active = selected,
                        entrance = false
                    )
                }
            } else {
                Icon(
                    imageVector = if (selected) item.selectedIcon else item.icon,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(21.dp)
                )
            }
            item.badgeCount?.takeIf { it > 0 }?.let { count ->
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(13.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (count > 99) "99+" else count.toString(),
                        color = Color.White,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Box(
            Modifier
                .height(labelHeight * labelFraction)
                .clipToBounds()
                .graphicsLayer { alpha = labelFraction * labelAlpha.value },
            contentAlignment = Alignment.TopCenter
        ) {
            Text(
                text = item.label,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                color = ink,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.wrapContentHeight(unbounded = true).padding(top = 1.dp)
            )
        }
    }
}

private fun Float.roundToIntCompat(): Int = kotlin.math.round(this).toInt()
