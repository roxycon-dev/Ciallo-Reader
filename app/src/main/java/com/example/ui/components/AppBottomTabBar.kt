package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import com.example.ui.theme.onColor
import com.kashif_e.backdrop.Backdrop
import me.trishiraj.shadowglow.consistentShadow

/**
 * 苹果 Liquid Glass 风格底部标签栏（上一版结构 + 真实验证参数增强）。
 *
 * 背景采用书源选择弹窗同款手法：MainActivity 用 layerBackdrop 捕获页面真实内容，
 * Tab 栏作为兄弟节点用 drawBackdrop(blur) 采样，得到真实 Gaussian Blur 磨砂。
 *
 * ── 本次样式升级（2026-09）────────────────────────────────────────────
 * 旧观感是「一根纯色药丸」：玻璃底之上只平铺了一层 `primary.copy(alpha=0.30f)`
 * 的单色，把玻璃的层次全盖掉了。本次分层为：
 *   ① 竖向渐变（顶部高光 → 主色淡染 → 底部略深）替代单层均色；
 *   ② 顶部 1dp 内高光 + 外圈细描边（与虹彩描边叠加，不冲突）；
 *   ③ 顶部 3dp 指示线按等宽槽位弹簧移动，收缩时保持对齐；
 *   ④ Badge 加与栏体同色的描边、排版更圆润。
 * 功能与弹簧手感（DampingRatioMediumBouncy + StiffnessLow）全部保留。
 */

/* ── 稳定常量 ────────────────────────────────────────────────────────────
 * 提到文件级：避免每次重组重新构造 Color / Shape 实例。
 * 这不仅省分配，更关键的是让 Modifier 元素的 equals 成立 —— Compose 复用
 * 相等元素的绘制节点，等价 ⇒ 不重建绘制缓存（极致档的开销大头就在这）。 */
private val TabBarShape: RoundedCornerShape = RoundedCornerShape(50)
private val TabBarAmbientShadowColor: Color = Color.Black.copy(alpha = 0.16f)
private val TabBarContactShadowColor: Color = Color.Black.copy(alpha = 0.20f)
private val TabBarLowOutlineColor: Color = Color.White.copy(alpha = 0.15f)
/** 中性细描边：替代原来的主色虹彩描边（去绿）。 */
private val TabBarNeutralOutlineColor: Color = Color.White.copy(alpha = 0.22f)

/** 未选中项文字/图标的透明度：0.55 → 0.48，让选中项更突出。 */
private const val TAB_UNSELECTED_ALPHA = 0.48f

/**
 * 滚动收缩弹簧（栏高 68↔52；横向框架固定，保证图标与指示条对齐）。
 *
 * 原来是 `DampingRatioMediumBouncy`(0.55) + `StiffnessLow`(200) —— 阻尼比 0.55 的过冲约
 * **12.6%**，配合最软的刚度，一上下滑整条栏就大幅摆动，设置页那种长列表里尤其明显。
 * 这里只"收紧"、不去掉弹簧感：
 *  · 阻尼比 0.55 → 0.70（过冲 ≈4.6%，仍有回弹，但不会甩出去）
 *  · 刚度 StiffnessLow(200) → StiffnessMediumLow(700)（收敛更快，摆动能被立刻拉住）
 * 想更硬就把阻尼比往 1.0 调、刚度往 StiffnessMedium(1500) 调；想更弹就反向调。
 */
private const val TAB_COLLAPSE_DAMPING = 0.70f
private const val TAB_COLLAPSE_STIFFNESS = Spring.StiffnessMediumLow

data class AppTabItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val badgeCount: Int? = null
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

@Composable
fun AppBottomTabBar(
    items: List<AppTabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    collapseState: TabBarCollapseState = rememberTabBarCollapseState(),
    backdrop: Backdrop? = null
) {
    val colors = rememberAppButtonColors()
    // 主题色跟随设置主色调平滑过渡（不固化在 remember 里）
    val animatedPrimary by animateColorAsState(
        targetValue = colors.primary,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "tabBarPrimary"
    )
    val animatedAccent by animateColorAsState(
        targetValue = colors.accent,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "tabBarAccent"
    )
    // 根据 Tab 栏“有效底色”（内容面 + 6% 主题色）自动取对比色，
    // 并用与全软件一致的弹簧动画过渡。
    //
    // 新规范：玻璃保持中性，着色 ≤6%。原先这里是 30% 主题色混色，
    // 整条栏读起来是“绿药丸”，且绿色面积远超 10% 上限。
    // 对比色**算法本身一行未改**，只是把混色比例压到 6%。
    val barBase = lerp(MaterialTheme.colorScheme.surface, animatedPrimary, 0.06f)
    val contrast by animateColorAsState(
        targetValue = barBase.onColor(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "tabBarContrast"
    )

    val barHeight by animateDpAsState(
        targetValue = if (collapseState.collapsed) 52.dp else 68.dp,
        animationSpec = spring(
            dampingRatio = TAB_COLLAPSE_DAMPING,
            stiffness = TAB_COLLAPSE_STIFFNESS
        ),
        label = "tabBarHeight"
    )
    // Keep the bar's horizontal frame fixed while a list scrolls. Animating the
    // outer margin changed the Row width and made the selected black indicator
    // visibly drift left/right on every vertical gesture.
    val horizontalMargin = 16.dp

    val shape = TabBarShape
    val quality = LocalRenderQuality.current

    /* ── ① 玻璃底：中性分层 + 顶部内高光 + 外圈细描边 ──────────────────
     *
     * 新规范：玻璃是「容器」不是「装饰」——保持中性白 / 中性深，**着色 ≤6%**，
     * 绿色只留给开关开启态 / 选中态 / 主按钮 / 小标签文字 / 对勾。
     * 原先的 22%~28% 主色渐变会把整条栏染成“绿药丸”，这里压到 5%，
     * 主色只通过【选中项图标+文字】表达。
     *
     * 流畅档（LOW）**不引入**任何新渐变层：维持单层近实心底 + 中性描边。 */
    val glassTintModifier = if (quality == RenderQuality.LOW) {
        Modifier.background(animatedPrimary.copy(alpha = 0.06f), shape)
    } else {
        Modifier.tabBarGlassTint(primary = animatedPrimary)
    }

    // Animate in slot units rather than measured pixels: a resize or collapse
    // cannot detach the line from its equal-width icon slot. Read only in drawing.
    val indicatorSlot = remember { Animatable(selectedIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)).toFloat()) }
    LaunchedEffect(selectedIndex, items.size) {
        indicatorSlot.animateTo(selectedIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)).toFloat(),
            spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
    }
    val selectionIndicator = Modifier.drawWithCache {
        val barTopPx = 2.dp.toPx()
        val barHeightPx = 3.dp.toPx()
        val barRadius = CornerRadius(barHeightPx / 2f)
        val slotWidth = size.width / items.size.coerceAtLeast(1)
        val barWidth = slotWidth * 0.40f
        onDrawWithContent {
            if (items.isNotEmpty()) {
                val slot = if (layoutDirection == LayoutDirection.Rtl) items.lastIndex - indicatorSlot.value else indicatorSlot.value
                val barLeft = slot * slotWidth + (slotWidth - barWidth) / 2f
                drawRoundRect(
                    color = contrast,
                    topLeft = Offset(barLeft, barTopPx),
                    size = Size(barWidth, barHeightPx),
                    cornerRadius = barRadius
                )
            }
            drawContent()
        }
    }
    // 虹彩描边配色：remember 住，避免每次重组生成新 List 导致
    // iridescentBorder 元素不等、绘制缓存被重建（主题切换时每帧一次）
    val iridescentColors = remember(animatedPrimary, animatedAccent) {
        listOf(animatedPrimary, animatedAccent, animatedPrimary)
    }

    // 主题 key：主题色变化时强制重建玻璃样式/着色器。
    // 修复：原先 key 用的是 animatedPrimary/animatedAccent（弹簧动画的**当前帧值**），
    // 主题切换动画期间每帧都在变 → 每帧销毁重建整条 Tab 栏，掉帧并丢失
    // tabPositions（指示条位置）与按压状态。改用动画的**目标值** colors.primary/accent，
    // 只在用户真正换主题时重建一次。
    key(colors.primary, colors.accent) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalMargin, vertical = 12.dp)
                // A2：让开系统导航栏。手势导航高度为 0（无影响），
                // 三键导航约 48dp——此前没有这行，Tab 栏会被压在导航栏后面截掉一截。
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .then(
                        if (quality == RenderQuality.LOW) {
                            // 流畅档：单层阴影
                            Modifier.consistentShadow(
                                elevation = 8.dp,
                                shape = shape,
                                ambientColor = TabBarAmbientShadowColor,
                                spotColor = TabBarAmbientShadowColor
                            )
                        } else {
                            Modifier
                                // 环境层：品牌色宽域柔光
                                .consistentShadow(
                                    elevation = 24.dp,
                                    shape = shape,
                                    ambientColor = animatedPrimary.copy(alpha = 0.18f),
                                    spotColor = animatedPrimary.copy(alpha = 0.18f)
                                )
                                // 接触层：近距离暗部
                                .consistentShadow(
                                    elevation = 8.dp,
                                    shape = shape,
                                    ambientColor = TabBarContactShadowColor,
                                    spotColor = TabBarContactShadowColor
                                )
                        }
                    )
                    .clip(shape)
                    .then(
                        when {
                            // 高/极致：实时毛玻璃（真实内容采样）；极致档底更透（不加厚模糊——
                            // 同样避免任何链式着色器/大采样风险，稳定性优先）
                            backdrop != null && quality.realtimeGlass -> Modifier.liquidGlass(
                                backdrop = backdrop,
                                shape = shape,
                                surfaceColor = MaterialTheme.colorScheme.surface.copy(
                                    alpha = if (quality == RenderQuality.MAX) 0.36f else 0.45f
                                ),
                                blurRadius = 8.dp,
                                refraction = false
                            )
                            // 均衡：半透明底替代毛玻璃
                            quality == RenderQuality.MID ->
                                Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), shape)
                            // 流畅：近实心底
                            else ->
                                Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), shape)
                        }
                    )
                    // ① 玻璃底分层（渐变 / 顶部高光 / 外圈细描边）
                    .then(glassTintModifier)
                    .then(
                        if (quality != RenderQuality.LOW) {
                            // 主色虹彩描边加回（与 tint 的主色混色配套）
                            Modifier.iridescentBorder(
                                shape = shape,
                                colors = iridescentColors,
                                width = if (quality == RenderQuality.MAX) 2.dp else 1.5.dp,
                                alpha = if (quality == RenderQuality.MAX) 0.60f else 0.45f
                            )
                        } else {
                            Modifier.border(1.dp, TabBarLowOutlineColor, shape)
                        }
                    )
                    .then(selectionIndicator)
                    .testTag("app_tab_bar_surface"),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    // Keep every tab in an equal-width slot. The indicator is
                    // drawn from the same slot geometry, so it stays centered
                    // over the icon while the list scrolls or labels collapse.
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        TabIcon(
                            item = item,
                            selected = index == selectedIndex,
                            collapsed = collapseState.collapsed,
                            contrast = contrast,
                            ringColor = barBase,
                            onClick = { onTabSelected(index) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 栏体的玻璃质感分层（替代原先平铺的单层主色）。
 *
 * 三段：顶部高光 → 主色淡染 → 底部略深，再叠顶部 1dp 内高光与外圈细描边。
 * 调用处已 `.clip(shape)`，这里直接按整块矩形绘制即可（会被裁到胶囊形）。
 */
private fun Modifier.tabBarGlassTint(primary: Color): Modifier = this.drawWithCache {
    // 主色混色（用户要求加回）：顶部高光 → 主色淡染 → 底部略深。
    // 2026-09-24 曾压到 5%/4% 做"中性化"，用户明确要求把原来的强调色混色加回来。
    val fill = Brush.verticalGradient(
        colorStops = arrayOf(
            0.00f to Color.White.copy(alpha = 0.20f),
            0.22f to primary.copy(alpha = 0.28f),
            0.62f to primary.copy(alpha = 0.22f),
            1.00f to Color.Black.copy(alpha = 0.10f)
        )
    )
    val radius = CornerRadius(size.height / 2f, size.height / 2f)
    val outline = Stroke(width = 1.dp.toPx())
    val outlineBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.34f),
            Color.White.copy(alpha = 0.10f)
        )
    )
    val glint = Stroke(width = 1.dp.toPx())
    val glintBrush = Brush.horizontalGradient(
        colors = listOf(
            Color.Transparent,
            Color.White.copy(alpha = 0.38f),
            Color.Transparent
        )
    )
    val glintY = 1.5.dp.toPx()
    onDrawBehind {
        // 主渐变
        drawRect(brush = fill)
        // 顶部 1dp 内高光
        drawLine(
            brush = glintBrush,
            start = Offset(size.width * 0.14f, glintY),
            end = Offset(size.width * 0.86f, glintY),
            strokeWidth = glint.width
        )
        // 外圈细描边（与虹彩描边叠加，提供清晰的边缘）
        drawRoundRect(
            brush = outlineBrush,
            cornerRadius = radius,
            style = outline
        )
    }
}

/**
 * ④ 角标：与栏体同色的描边 + 更圆润的排版。
 * 数字格式化逻辑（`count > 99 → "99+"`）保持原样不动。
 */
@Composable
private fun TabBadge(count: Int, ringColor: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(if (count > 0) 18.dp else 9.dp)
            .background(Color(0xFFE5484D), shape = CircleShape)
            .border(1.5.dp, ringColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (count > 0) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                fontSize = 9.sp,
                color = Color.White,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                letterSpacing = 0.sp
            )
        }
    }
}

@Composable
private fun TabIcon(
    item: AppTabItem,
    selected: Boolean,
    collapsed: Boolean,
    contrast: Color,
    ringColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    // B7：Tab 此前 indication = null 且无任何按压缩放/触觉，点下去完全没有手感。
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "tabPressScale"
    )
    val haptics = LocalHapticFeedback.current
    // 只在「未按下 -> 按下」跳变时振一次；写在组合期会随重组重复触发
    LaunchedEffect(isPressed) {
        if (isPressed) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tabIconScale"
    )
    // ③ 未选中项压到 0.48（原 0.55），让选中项更突出；
    //    barBase / contrast 的自动对比色算法完全不变。
    val iconColor = if (selected) contrast else contrast.copy(alpha = TAB_UNSELECTED_ALPHA)

    Column(
        modifier = Modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Icon(
                imageVector = if (selected) item.selectedIcon else item.icon,
                contentDescription = item.label,
                tint = iconColor,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    }
            )
            item.badgeCount?.let { count ->
                TabBadge(
                    count = count,
                    ringColor = ringColor,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }

        AnimatedVisibility(
            visible = !collapsed,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Text(
                text = item.label,
                fontSize = 10.sp,
                color = iconColor,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
