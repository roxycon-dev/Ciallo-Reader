package com.ramotion.fluidslider

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

@Composable
fun FluidSlider(
    position: Float,
    onPositionChange: (Float) -> Unit,
    /** 一次触摸手势结束（正常抬手，或被外层手势守卫中途认领）时回调一次 */
    onPositionChangeFinished: (() -> Unit)? = null,
    /** 拖动开始/结束（含点按跳转的瞬时拖动）时上报，供外部联动 UI（如隐藏文字标签） */
    onDraggingChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    barHeightDp: Int = 48,
    bubbleText: String? = null,
    startText: String? = "0",
    endText: String? = "100",
    colorBar: Color = Color(0xFF6168E7),
    colorBubble: Color = Color.White,
    colorBubbleText: Color = Color.Black,
    colorBarText: Color = Color.White.copy(alpha = 0.7f),
    durationMillis: Int = 400
) {
    com.example.ui.components.LiquidSlider(
        value = position, onValueChange = onPositionChange, modifier = modifier,
        onValueChangeFinished = onPositionChangeFinished, onDraggingChange = onDraggingChange,
        formatLabel = { bubbleText ?: "${(it * 100).roundToInt()}%" },
    )
}