package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.pageturn.PageTurnType
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 设置页统一控件：弹簧分段选择器、翻页动画选择列表、自定义分钟弹窗、
 * JunoSlider 护眼强度滑块（展开交互 + 内外阴影 + 主题色渐变 + 拖动气泡）。
 */

/** 通用水滴分段选择器；调用方的布局、选项 id 与回调保持不变。 */
@Composable
fun SegmentedPillSelector(
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fontSp: Float = 12f,
) {
    val items = remember(options) { options.map { (id, label) -> LiquidSegmentOption(id, label) } }
    LiquidSegmentedSelector(items, selected, onSelect, modifier, fontSp = fontSp)
}

/** 翻页动画选择行：选中态渐变胶囊 + 弹簧勾选反馈。 */
@Composable
fun PageTurnSelectorRow(
    title: String,
    description: String,
    type: PageTurnType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "rowScale"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "rowCheck"
    )
    // 选中瞬间触发一次迷你试翻；初始选中的行进入页面即演示一遍
    var playTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(selected) {
        if (selected) playTick++
    }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(
                if (selected) Brush.horizontalGradient(listOf(primary.copy(alpha = 0.16f), secondary.copy(alpha = 0.10f)))
                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
            )
            .then(
                if (selected) {
                    Modifier.border(1.5.dp, primary.copy(alpha = 0.55f), shape)
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), shape)
                }
            )
            .pullOutClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PageTurnPreview(
                type = type,
                playTick = playTick,
                dimmed = !selected,
                modifier = Modifier.padding(end = 14.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (selected) primary else MaterialTheme.colorScheme.onSurface
                )
                if (description.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                }
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            Brush.horizontalGradient(listOf(primary, secondary))
                        } else {
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        }
                    )
                    .graphicsLayer {
                        scaleX = checkScale
                        scaleY = checkScale
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 自定义分钟弹窗：+/- 步进器，数字弹簧切换。 */
@Composable
fun CustomMinutesDialog(
    current: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(current) { mutableStateOf(current.coerceIn(1, 180)) }
    var text by remember(current) { mutableStateOf(current.coerceIn(1, 180).toString()) }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    AcrylicDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义休息时间", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(3)
                        text = digits
                        digits.toIntOrNull()?.let {
                            if (it in 1..180) value = it
                        }
                    },
                    label = { Text("输入分钟数") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primary,
                        unfocusedBorderColor = primary.copy(alpha = 0.35f),
                        focusedLabelColor = primary
                    )
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    AppActionButton(
                        text = "",
                        onClick = { value = (value - 1).coerceAtLeast(1) },
                        variant = AppButtonVariant.Secondary,
                        buttonSize = AppButtonSize.Small,
                        icon = Icons.Filled.Remove
                    )
                    AnimatedContent(
                        targetState = value,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.7f) + fadeIn()).togetherWith(scaleOut(targetScale = 0.7f) + fadeOut())
                        },
                        label = "minutesValue"
                    ) { v ->
                        Text(
                            text = "$v 分钟",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(primary.copy(alpha = 0.08f))
                                .padding(horizontal = 18.dp, vertical = 8.dp)
                        )
                    }
                    AppActionButton(
                        text = "",
                        onClick = { value = (value + 1).coerceAtMost(180) },
                        variant = AppButtonVariant.Secondary,
                        buttonSize = AppButtonSize.Small,
                        icon = Icons.Filled.Add
                    )
                }
            }
        },
        confirmButton = {
            val closeOverlay = com.example.ui.components.LocalOverlayClose.current
            AppActionButton(
                text = "确定",
                onClick = { closeOverlay {
                    val finalValue = text.toIntOrNull()?.coerceIn(1, 180) ?: value.coerceIn(1, 180)
                    onConfirm(finalValue)
                } },
                variant = AppButtonVariant.Primary,
                buttonSize = AppButtonSize.Small
            )
        },
        dismissButton = {
            val closeOverlay = com.example.ui.components.LocalOverlayClose.current
            PullOutTextButton(onClick = { closeOverlay {  } }) {
                Text("取消")
            }
        }
    )
}

/**
 * JunoSlider 交互移植（护眼滤镜强度）：
 * 静止 10dp 细胶囊，按下展开到 22dp，内外阴影用主题色，拖动时显示百分比气泡。
 */
/**
 * JunoSlider 交互移植（christianselig/JunoSlider 严格照搬）：
 * 静止 10dp / 展开 22dp（tween 对称展开/收回）、深色半透明轨道、
 * 内阴影（primary 0.3, r3, y2 上沿暗带）+ 外阴影（primary 0.2, r1, y1 顶部亮线）、
 * 进度条 primary→accent 渐变、thumb 仅拖动时出现、点按跳转、拖动基于起始值计算。
 */
@Composable
fun JunoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    LiquidSlider(value, onValueChange, modifier, formatLabel = { "${(it * 100).roundToInt()}%" })
}

/**
 * 迷你试翻预览页（40×54dp）：用每种翻页效果自己的缓动曲线，
 * 把一张"纸"从起始帧翻到结束帧。未选中的行静止在起始帧。
 */
@Composable
private fun PageTurnPreview(
    type: PageTurnType,
    playTick: Int,
    dimmed: Boolean,
    modifier: Modifier = Modifier
) {
    val pageBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val nextLineColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.38f)
    val edgeColor = MaterialTheme.colorScheme.outlineVariant

    // 每种效果使用自己真实的缓动曲线：卷曲是纸张阻尼的减速曲线
    val easing = when (type) {
        PageTurnType.SIMULATE -> CubicBezierEasing(0.2f, 0.7f, 0.25f, 1f)
        PageTurnType.COVER -> FastOutSlowInEasing
        PageTurnType.SLIDE -> LinearEasing
        PageTurnType.FADE -> FastOutSlowInEasing
        PageTurnType.SCROLL -> LinearEasing
    }

    val progress = remember { Animatable(0f) }
    // 未选中的行定格在该效果最具辨识度的中间相位，扫视即可区分五种效果
    LaunchedEffect(Unit) {
        if (dimmed) progress.snapTo(0.55f)
    }
    LaunchedEffect(playTick) {
        if (playTick == 0 && dimmed) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(520, easing = easing))
    }

    Canvas(modifier = modifier.size(width = 40.dp, height = 54.dp)) {
        val w = size.width
        val h = size.height
        val p = progress.value

        // 纸面：实色圆角矩形 + 细描边（暗色主题下也能与卡片底分离）
        drawRoundRect(color = pageBg, cornerRadius = CornerRadius(6f, 6f))
        drawRoundRect(
            color = edgeColor,
            cornerRadius = CornerRadius(6f, 6f),
            style = Stroke(width = 1.dp.toPx())
        )

        fun drawLines(alpha: Float, xShift: Float, color: Color) {
            for (i in 0..3) {
                val lineW = w - 12f - (if (i == 3) w * 0.28f else 0f)
                drawRoundRect(
                    color = color.copy(alpha = color.alpha * alpha.coerceIn(0f, 1f)),
                    topLeft = Offset(6f + xShift, 8f + i * (h - 16f) / 4f),
                    size = Size(lineW, 3f),
                    cornerRadius = CornerRadius(1.5f, 1.5f)
                )
            }
        }

        when (type) {
            PageTurnType.SIMULATE -> {
                translate(left = -3f * p) { drawLines(1f, 0f, lineColor) }
                // 右下角折起：背面纸 + 折痕暗带 + 纸缘高光
                val fold = (w * 0.62f) * p
                if (fold > 2f) {
                    val path = Path().apply {
                        moveTo(w, h)
                        lineTo(w - fold, h)
                        lineTo(w, h - fold)
                        close()
                    }
                    drawPath(path, color = pageBg)
                    drawPath(
                        path,
                        brush = Brush.linearGradient(
                            listOf(Color.Black.copy(alpha = 0.16f * p), Color.Transparent),
                            start = Offset(w - fold, h),
                            end = Offset(w, h - fold)
                        )
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.55f * p),
                        start = Offset(w - fold, h),
                        end = Offset(w, h - fold),
                        strokeWidth = 1.5f
                    )
                }
            }
            PageTurnType.COVER -> {
                drawLines(1f, 0f, lineColor)
                val sheetX = w * (1f - p)
                drawRect(color = pageBg, topLeft = Offset(sheetX, 0f), size = Size(w - sheetX, h))
                if (sheetX > 1f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent),
                            startX = sheetX - 5f,
                            endX = sheetX + 2f
                        ),
                        topLeft = Offset(sheetX - 5f, 0f),
                        size = Size(7f, h)
                    )
                }
            }
            PageTurnType.SLIDE -> {
                // 裁剪到纸面内，平移的行不允许溢出到"书外"
                clipRect(0f, 0f, w, h) {
                    translate(left = -w * 0.85f * p) { drawLines(1f, 0f, lineColor) }
                    translate(left = w * (1f - p) * 0.9f) { drawLines(0.45f, 0f, nextLineColor) }
                }
            }
            PageTurnType.FADE -> {
                drawLines(1f - p, -2f * p, lineColor)
                drawLines(p, 2f * p, nextLineColor)
            }
            PageTurnType.SCROLL -> {
                clipRect(0f, 0f, w, h) {
                    val gap = (h - 16f) / 4f
                    translate(top = -p * gap * 2.4f) {
                        for (i in 0..5) {
                            val lineW = w - 12f - (if (i % 4 == 3) w * 0.28f else 0f)
                            drawRoundRect(
                                color = lineColor,
                                topLeft = Offset(6f, 8f + i * gap),
                                size = Size(lineW, 3f),
                                cornerRadius = CornerRadius(1.5f, 1.5f)
                            )
                        }
                    }
                }
                drawRoundRect(
                    color = edgeColor,
                    topLeft = Offset(w - 3f, 6f + p * (h - 18f)),
                    size = Size(2.5f, 10f),
                    cornerRadius = CornerRadius(1.2f, 1.2f)
                )
            }
        }
    }
}
