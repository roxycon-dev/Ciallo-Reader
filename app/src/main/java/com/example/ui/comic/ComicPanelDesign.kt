package com.example.ui.comic

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import com.example.ui.theme.MintPrimary
import kotlin.math.abs
import kotlin.math.roundToInt

/* ══════════════ 漫画面板设计系统（第七轮第 3 条） ══════════════
 *
 * 苹果式精致/克制路线的阅读设置面板组件族：模块化玻璃卡片、图标 Tab、
 * 带明确背景色块的选中态、统一的自绘滑条与开关。全部组件只依赖既有的
 * Panel* 色彩令牌与品牌薄荷绿，无霓虹/发光/重投影——高级感来自留白、
 * 层级、控件比例与材质，而不是堆装饰。书架（第 5 条）与隐私窗口（第 6 条）
 * 沿用同一套语言。
 */

/** 玻璃卡片内层：极淡的白雾填充 + 细描边（模块化区块分隔的"玻璃描边"） */
internal val PanelCardBg = Color(0x12FFFFFF)
internal val PanelCardStroke = Color(0x26FFFFFF)

/** 选中背景（品牌薄荷绿 18% 透明度填充）——明确但克制的选中反馈。
 *  MintPrimary 是 @Composable getter（MaterialTheme 动态取色），故此二函数同样标注。 */
@Composable
private fun selectedBg(): Color = MintPrimary.copy(alpha = 0.18f)

@Composable
private fun selectedStroke(): Color = MintPrimary.copy(alpha = 0.55f)

/**
 * 模块化玻璃卡片：面板内每个功能区块的容器。
 * 独立圆角卡片形成清晰的视觉分组（替代"标题文字 + 留白"的旧分隔方式）。
 */
@Composable
internal fun PanelSectionCard(
    title: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(PanelCardBg)
            .border(0.5.dp, PanelCardStroke, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let {
                    Icon(it, null, tint = MintPrimary.copy(alpha = 0.9f), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

/** Tab 定义（图标 + 文字） */
internal data class PanelTabData(val label: String, val icon: ImageVector)

/**
 * 图标 Tab 导航行：作为完整导航区域设计——图标在上文字在下，统一高度与间距，
 * 选中 = 薄荷半透明填充 + 细边框（不再只靠文字变色），未选中弱化。
 */
@Composable
internal fun PanelTabRow(
    tabs: List<PanelTabData>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PanelChipBg)
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEachIndexed { i, tab ->
            val active = i == selected
            val tint by animateColorAsState(if (active) selectedBg() else Color.Transparent, label = "panelTabTint")
            Column(
                Modifier
                    .width(58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tint)
                    .border(
                        0.5.dp,
                        if (active) selectedStroke() else Color.Transparent,
                        RoundedCornerShape(14.dp)
                    )
                    .selectable(active, role = Role.Tab) { onSelect(i) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    tab.icon,
                    contentDescription = tab.label,
                    tint = if (active) MintPrimary else TextSecondary,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    tab.label,
                    color = if (active) MintPrimary else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

/** A scrollable rail gives tablets enough room for all seven categories. */
@Composable
internal fun PanelTabRail(tabs: List<PanelTabData>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(92.dp).verticalScroll(rememberScrollState()).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEachIndexed { index, tab ->
            val active = index == selected
            val tint by animateColorAsState(if (active) selectedBg() else Color.Transparent, label = "railTint")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tint)
                .selectable(active, role = Role.Tab) { onSelect(index) }.padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(tab.icon, null, tint = if (active) MintPrimary else TextSecondary, modifier = Modifier.size(21.dp))
                Spacer(Modifier.height(6.dp))
                Text(tab.label, color = if (active) MintPrimary else TextSecondary, fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/**
 * 面板统一滑条（自绘）：细玻璃轨道 + 薄荷填充 + 白色圆钮（轻按压光环）。
 * 全面板唯一的滑条样式（纸纹/间距/滤镜/增强强度等共用），与 FluidSlider 的
 * 液态语言同源但为紧凑面板做了收敛——不放大尺寸、不加气泡。
 * 水平位移超过 slop 且水平分量占优才接管（与外层垂直滚动正确仲裁）。
 */
@Composable
internal fun PanelSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: () -> Unit = {},
    steps: Int = 0,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    reverse: Boolean = false,
) {
    val fraction = if (valueRange.endInclusive > valueRange.start) {
        ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    } else 0f
    val density = LocalDensity.current
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(if (pressed) 1.25f else 1f, spring(stiffness = 600f), label = "sliderPress")

    fun snap(f: Float): Float = if (steps > 0) {
        val st = 1f / (steps + 1)
        (kotlin.math.round(f / st) * st).coerceIn(0f, 1f)
    } else f

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                setProgress { target ->
                    if (!enabled) false else {
                        latestOnValueChange(target.coerceIn(valueRange)); latestOnValueChangeFinished(); true
                    }
                }
            }
            .pointerInput(valueRange, steps, enabled, reverse) {
                if (!enabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                val inset = 10.dp.toPx()
                fun posToValue(x: Float): Float {
                    val physical = ((x - inset) / (size.width - 2f * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val f = snap(if (reverse) 1f - physical else physical)
                    return valueRange.start + f * (valueRange.endInclusive - valueRange.start)
                }
                awaitEachGesture {
                    try {
                    val down = awaitFirstDown()
                    var totalX = 0f
                    var totalY = 0f
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed || event.changes.count { it.pressed } > 1 ||
                            !dragging && abs(totalY) > slop && abs(totalY) >= abs(totalX)) {
                            pressed = false
                            break
                        }
                        if (!change.pressed) {
                            pressed = false
                            if (!dragging && abs(totalX) < slop && abs(totalY) < slop) {
                                latestOnValueChange(posToValue(down.position.x))
                                latestOnValueChangeFinished()
                            } else if (dragging) {
                                latestOnValueChangeFinished()
                            }
                            break
                        }
                        val dx = change.positionChange().x
                        val dy = change.positionChange().y
                        totalX += dx
                        totalY += dy
                        if (!dragging && abs(totalX) > slop && abs(totalX) > abs(totalY)) {
                            dragging = true
                            pressed = true
                        }
                        if (dragging) {
                            latestOnValueChange(posToValue(change.position.x))
                            change.consume()
                        }
                    }
                    } finally { pressed = false }
                }
            }
    ) {
        val inset = with(density) { 10.dp.toPx() }
        val trackWidth = (constraints.maxWidth.toFloat() - inset * 2).coerceAtLeast(1f)
        val thumbR = with(density) { 8.dp.toPx() }
        val pressRingR = with(density) { (if (enabled) 12.dp else 10.dp).toPx() }
        val trackH = with(density) { 5.dp.toPx() }
        // DrawScope 不是组合作用域：@Composable 的 MintPrimary 需在组合期取值
        val fillEnabled = MintPrimary.copy(alpha = 0.9f)
        val fillDisabled = MintPrimary.copy(alpha = 0.35f)
        val ringColor = MintPrimary.copy(alpha = 0.18f)
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val cx = inset + trackWidth * (if (reverse) 1f - fraction else fraction)
            // 轨道
            drawRoundRect(
                color = Color(0x2EFFFFFF),
                cornerRadius = CornerRadius(trackH / 2),
                topLeft = Offset(inset, cy - trackH / 2),
                size = Size(trackWidth, trackH),
            )
            // 填充
            drawRoundRect(
                color = if (enabled) fillEnabled else fillDisabled,
                cornerRadius = CornerRadius(trackH / 2),
                topLeft = Offset(if (reverse) cx else inset, cy - trackH / 2),
                size = Size(trackWidth * fraction, trackH),
            )
            // 光环（克制：低透明度描边圈，不做发光；禁用时收起）
            if (enabled) {
                drawCircle(
                    color = ringColor,
                    radius = pressRingR * pressScale,
                    center = Offset(cx, cy),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )
            }
            // 圆钮
            drawCircle(
                if (enabled) Color(0xFFFFFFFF) else Color(0xAAFFFFFF),
                radius = thumbR * pressScale,
                center = Offset(cx, cy),
            )
        }
    }
}

/**
 * 面板统一开关（自绘玻璃胶囊）：品牌绿轨道 + 白色圆钮弹性滑动，
 * 与 M3 Switch 相比更轻薄、与玻璃面板同材质。全面板唯一开关样式。
 */
@Composable
internal fun PanelSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 1400f),
        label = "panelSwitch"
    )
    val trackColor = lerpColor(Color(0x33FFFFFF), MintPrimary.copy(alpha = 0.85f), t)
    val travel = with(LocalDensity.current) { 18.dp.toPx() }
    Box(
        modifier
            .width(46.dp)
            .height(28.dp)
            .clip(CircleShape)
            .background(trackColor)
            .border(
                0.5.dp,
                lerpColor(Color(0x2EFFFFFF), MintPrimary.copy(alpha = 0.6f), t),
                CircleShape
            )
            .clickableNoRipple { onChange(!checked) }
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((3.dp.toPx() + t * travel).roundToInt(), 0) }
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

/** 选中态背景色（供 SegmentRow 等复用） */
@Composable
internal fun panelSelectedBg(): Color = selectedBg()

/** 选中态描边色 */
@Composable
internal fun panelSelectedStroke(): Color = selectedStroke()

/** 背景类型色卡（阅读背景的可视化选择） */
internal data class PanelBgSwatch(val label: String, val fill: Color, val gradient: Brush? = null)
