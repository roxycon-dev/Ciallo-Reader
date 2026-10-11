package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.ui.feedback.AppMotion
import com.example.ui.feedback.LocalReduceMotion

@Composable
fun CompletionGlyph(color: Color, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val reveal = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(reduce) { reveal.animateTo(1f, tween(if (reduce) 0 else 320, easing = AppMotion.easeOutCubic)) }
    Canvas(modifier) {
        val p = reveal.value
        val stroke = size.minDimension * .07f
        drawCircle(color.copy(alpha = .12f), radius = size.minDimension / 2)
        drawArc(color.copy(alpha = .7f), -90f, 360f * p, false, style = Stroke(stroke, cap = StrokeCap.Round))
        val a = Offset(size.width * .25f, size.height * .51f)
        val b = Offset(size.width * .43f, size.height * .68f)
        val c = Offset(size.width * .76f, size.height * .34f)
        val first = (p / .4f).coerceIn(0f, 1f)
        drawLine(color, a, a + (b-a) * first, stroke, StrokeCap.Round)
        if (p > .4f) drawLine(color, b, b + (c-b) * ((p-.4f)/.6f), stroke, StrokeCap.Round)
    }
}
