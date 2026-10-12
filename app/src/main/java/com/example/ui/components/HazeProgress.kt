package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun FlowingGradientProgressBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (LocalRenderQuality.current.minimal) {
        Canvas(modifier.fillMaxWidth().height(8.dp)) {
            drawRect(color.copy(alpha = .15f))
            drawRect(color, size = Size(size.width * progress.coerceIn(0f, 1f), size.height))
        }
        return
    }
    val infiniteTransition = rememberInfiniteTransition(label = "flow")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flow_offset"
    )

    Canvas(modifier = modifier.fillMaxWidth().height(12.dp)) {
        val width = size.width
        val height = size.height
        val progressWidth = width * progress.coerceIn(0f, 1f)
        val cornerRadius = CornerRadius(height / 2, height / 2)
        
        // Track
        drawRoundRect(
            color = color.copy(alpha = 0.15f),
            size = Size(width, height),
            cornerRadius = cornerRadius
        )

        if (progressWidth > 0) {
            val brush = Brush.linearGradient(
                colors = listOf(
                    color.copy(alpha = 0.7f),
                    color,
                    color.copy(alpha = 0.7f)
                ),
                start = Offset(offset - 1000f, 0f),
                end = Offset(offset, 0f)
            )
            drawRoundRect(
                brush = brush,
                size = Size(progressWidth, height),
                cornerRadius = cornerRadius
            )
        }
    }
}
