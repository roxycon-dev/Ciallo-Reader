package com.example.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppFonts

/** Separate thresholds prevent repeated collapse/expand when a finger hovers near the boundary. */
internal class HeaderCollapseGate(private val closeAt: Float, private val openAt: Float) {
    private var collapsed = false
    fun update(index: Int, offset: Int, forced: Boolean): Boolean {
        collapsed = if (forced || index > 0) true else if (collapsed) offset > openAt else offset > closeAt
        return collapsed
    }
}

/**
 * 滚动联动折叠头部——四个 Tab 页（书库/书架/统计/设置）共享。
 *
 * 根因修复（见《根因诊断报告》）：此前四页头部是复制粘贴的 88~92dp 固定玻璃卡，
 * 与滚动状态零联动，常驻挤压约 11% 屏高。
 *
 * 状态机（代理C）：Expanded（默认，标题24sp+副标题）→ Collapsed（滚过约 20dp 即收起：
 * 标题缩至 19sp、副标题高度与透明度归零、内外 padding 收紧）→ 回到顶部恢复 Expanded。
 * 收起/展开由 [rememberHeaderCollapsed] 的 derivedStateOf 派生——只在状态翻转时重组，
 * 滚动过程中零监听开销；高度动画 220ms 一次性补间，无逐帧常驻计算。
 *
 * 视觉效果：滚动后头部收缩（Statistics 无大槽位约 51dp；Library/Home 因 48dp 最小触控
 * 槽位收起态约 62-74dp），为列表让出可视高度；标题始终保留（位置感不丢失）。
 */

/** 折叠判定：滚过首项顶部约 20dp（密度无关，LocalDensity 换算）即收起，回到顶部恢复。derivedStateOf 只在布尔翻转时通知。 */
@Composable
fun rememberHeaderCollapsed(state: LazyListState, forceCollapsed: Boolean = false): Boolean {
    val thresholdPx = with(LocalDensity.current) { 20.dp.toPx() }
    val scrolled by remember(state, thresholdPx) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > thresholdPx }
    }
    return scrolled || forceCollapsed
}

@Composable
fun rememberHeaderCollapsed(state: LazyGridState, forceCollapsed: Boolean = false): Boolean {
    val thresholdPx = with(LocalDensity.current) { 20.dp.toPx() }
    val scrolled by remember(state, thresholdPx) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > thresholdPx }
    }
    return scrolled || forceCollapsed
}

@Composable
fun rememberHeaderCollapsed(state: LazyStaggeredGridState, forceCollapsed: Boolean = false): Boolean {
    val thresholdPx = with(LocalDensity.current) { 20.dp.toPx() }
    val scrolled by remember(state, thresholdPx) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > thresholdPx }
    }
    return scrolled || forceCollapsed
}

/**
 * 折叠信号的「lambda 版」：返回一个**不在本页订阅滚动状态**的求值函数，
 * 真正的读取发生在 [TabScreenHeader] 内部（它自己用 derivedStateOf 包一层）。
 *
 * 为什么要有这个版本：[rememberHeaderCollapsed] 返回的是 Boolean，页面体一旦解包，
 * 折叠状态每次翻转都会重组**整个页面**composable（书库/书架页动辄 3000+ 行函数体）。
 * 改成把 lambda 传下去之后，页面体零订阅，重组范围只剩头部那一小块。
 * 与 LibraryScreen 的 `headerFractionSource` 同一手法（那边早就是 lambda 了）。
 */
@Composable
fun rememberHeaderCollapsedSource(state: LazyListState, forceCollapsed: () -> Boolean = { false }): () -> Boolean {
    val density = LocalDensity.current
    val gate = remember(state, density) { HeaderCollapseGate(with(density) { 32.dp.toPx() }, with(density) { 8.dp.toPx() }) }
    val force by rememberUpdatedState(forceCollapsed)
    return remember(state, gate) { { gate.update(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, force()) } }
}

/** 网格版折叠信号，语义同 [rememberHeaderCollapsedSource]。 */
@Composable
fun rememberHeaderCollapsedSource(state: LazyGridState, forceCollapsed: () -> Boolean = { false }): () -> Boolean {
    val density = LocalDensity.current
    val gate = remember(state, density) { HeaderCollapseGate(with(density) { 32.dp.toPx() }, with(density) { 8.dp.toPx() }) }
    val force by rememberUpdatedState(forceCollapsed)
    return remember(state, gate) { { gate.update(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, force()) } }
}

/** 瀑布流版折叠信号，语义同 [rememberHeaderCollapsedSource]。 */
@Composable
fun rememberHeaderCollapsedSource(state: LazyStaggeredGridState, forceCollapsed: () -> Boolean = { false }): () -> Boolean {
    val density = LocalDensity.current
    val gate = remember(state, density) { HeaderCollapseGate(with(density) { 32.dp.toPx() }, with(density) { 8.dp.toPx() }) }
    val force by rememberUpdatedState(forceCollapsed)
    return remember(state, gate) { { gate.update(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, force()) } }
}

/**
 * @param collapsed   是否收起（由 [rememberHeaderCollapsed] 派生；传了 [collapsedSource] 时忽略）
 * @param collapsedSource 折叠信号的 lambda 版（[rememberHeaderCollapsedSource]）。传入时本页不再订阅
 *                  滚动状态，折叠态在头部内部 derivedStateOf 求值 —— 重组范围收缩到头部。
 * @param titleColor  标题色（各页传 glassTitleColor()，与背景明暗联动）
 * @param titleVisible false 时不组合标题列（书架页搜索展开态占用整行）
 * @param leading     标题前的槽位（如设置页返回键）
 * @param trailing    标题后的槽位（如书库下载入口、书架搜索框）
 */
@Composable
fun TabScreenHeader(
    collapsed: Boolean = false,
    collapsedSource: (() -> Boolean)? = null,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    titleVisible: Boolean = true,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    // 折叠态在头部作用域内求值：lambda 版只让头部订阅，页面体零订阅。
    // derivedStateOf 保证滚动过程中仅在布尔翻转时重组一次头部（逐帧零开销）。
    val collapsedNow by remember(collapsedSource, collapsed) {
        derivedStateOf { collapsedSource?.invoke() ?: collapsed }
    }

    CollapsingTabScreenHeader(fractionSource = { if (collapsedNow) 1f else 0f }, focusDriven = true,
        modifier = modifier, title = title.orEmpty(), subtitle = subtitle, titleColor = titleColor,
        titleVisible = titleVisible && title != null, leading = leading, trailing = trailing)
}

/**
 * 书库渐进折叠头部（强化版收缩）——区别于四 Tab 共享的 [TabScreenHeader] 布尔两态：
 * [fraction] 由滚动偏移连续驱动（0=完全展开，1=完全收起零占位），
 * 三段式映射（区间重叠交叉淡化）保证全程连续无跳变：
 * - 0→0.5：内容紧凑化（副标题双通道淡出、标题 24→19sp、内外 padding 收紧）
 * - 0.4→0.8：整卡淡出（先于压高完成，压高裁切时内容已不可见，杜绝"半截字"伪影）
 * - 0.6→1：整卡高度按比例归零（自绘 layout 压缩高度，真正释放布局空间）
 * 滚动中每帧只重排一个 layout 节点，无监听器开销；回顶按同一路径反向恢复。
 */
@Composable
fun CollapsingTabScreenHeader(
    /**
     * 折叠目标值的**来源**（不在页面体里求值）。
     *
     * 传 lambda 而不是 Float：滚动位置、聚焦状态这些 State 的读取发生在
     * **本组件内部**，于是它们的每一帧变化只让头部重组，不会重组整个 LibraryScreen。
     */
    fractionSource: () -> Float,
    /** 目标来自搜索聚焦（状态跳变）：用 260ms 补间，否则用 120ms（滚动驱动）。 */
    focusDriven: Boolean = false,
    modifier: Modifier = Modifier,
    title: String = "书库",
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    titleVisible: Boolean = true,
    leading: (@Composable RowScope.() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val target = fractionSource()
    val reduce = com.example.ui.feedback.LocalReduceMotion.current || LocalRenderQuality.current.minimal
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduce) tween(0) else if (focusDriven || target == 1f) tween(260) else tween(120),
        label = "libHeaderFraction",
    )
    val f0 = fraction.coerceIn(0f, 1f)
    // 三段式：0→0.5 内容紧凑化；0.4→0.8 整卡淡出；0.6→1 压高归零——
    // 淡出先于压高完成，避免压高裁切把标题/图标拦腰截断的"半截字"伪影
    val compact = (f0 / 0.5f).coerceIn(0f, 1f)
    val fade = ((f0 - 0.4f) / 0.4f).coerceIn(0f, 1f)
    val collapse = ((f0 - 0.6f) / 0.4f).coerceIn(0f, 1f)
    val titleSize = 24f - 5f * compact
    val subAlpha = (1f - f0 / 0.3f).coerceIn(0f, 1f)
    // 副标题槽位 20dp：12sp 字体自然行高约 17.6~19sp，17dp 会把英文降部
    // （"LIBRARY & SEARCH" 的 Y / & 下缘）裁掉 —— 厂商字体 descent 越大裁得越狠。
    // 槽位放大后配合 clipToBounds 只在折叠收起时裁切。
    val subH = with(LocalDensity.current) { 12.sp.toDp() * 1.65f }.coerceAtLeast(20.dp) * subAlpha
    val outerV = (4 * (1f - collapse) * (1f - collapse)).dp
    val innerV = (8 - 4 * compact).dp

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = outerV)
            // 高度按 (1-collapse) 等比压缩 + 淡出（淡出已完成，裁切不可见）：
            // 完全收起后高度 0、padding 0，真正零垂直占用。
            // placement 必须真正 place placeable——空 placement 块会让整卡不参与绘制
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val h = (placeable.height * (1f - collapse)).roundToInt()
                layout(constraints.maxWidth, h) {
                    placeable.placeRelative(0, 0)
                }
            }
            .alpha(1f - fade)
            .clipToBounds(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = innerV).heightIn(min = 48.dp * (1f - compact)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading?.invoke(this)
            if (titleVisible) Column(Modifier.weight(1f).then(if (onTitleClick != null) Modifier.pullOutClickable(onClick = onTitleClick) else Modifier)) {
                Text(
                    text = title,
                    fontSize = titleSize.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                    fontFamily = AppFonts.Serif,
                    maxLines = 1
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = titleColor.copy(alpha = 0.75f),
                        letterSpacing = 1.5.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .height(subH)
                            .alpha(subAlpha)
                            .clipToBounds()
                    )
                }
            }
            trailing?.invoke(this)
        }
    }
}
