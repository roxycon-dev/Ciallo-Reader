package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.feedback.AppMotion
import com.example.ui.feedback.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.sin

/** One small draw-only clock; reduced motion keeps an accurate stationary indicator. */
@Composable
internal fun rememberProgressPhase(active: Boolean, durationMillis: Int = AppMotion.PROGRESS_CYCLE_MS): State<Float> =
    if (active) rememberInfiniteTransition(label = "progressClock").animateFloat(0f, 1f,
        infiniteRepeatable(tween(durationMillis.coerceAtLeast(600), easing = LinearEasing)), label = "progressPhase")
    else rememberUpdatedState(0f)

internal fun DrawScope.drawAppProgressRing(
    progress: Float?, phase: Float, color: Color, trackColor: Color,
    center: Offset, radius: Float, stroke: Stroke,
) {
    drawCircle(trackColor, radius, center, style = stroke)
    val start = -90f + if (progress == null) phase * 360f else 0f
    val sweep = if (progress == null) 90f + 60f * sin(phase * PI.toFloat()) else 360f * progress.coerceIn(0f, 1f)
    drawArc(color, start, sweep, false, center - Offset(radius, radius), Size(radius * 2f, radius * 2f), style = stroke)
}

@Composable
fun AppProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 2.dp,
    trackColor: Color = color.copy(alpha = .13f),
    progress: (() -> Float)? = null,
    durationMillis: Int = AppMotion.PROGRESS_CYCLE_MS,
) {
    val reduce = LocalReduceMotion.current || LocalRenderQuality.current.minimal
    val target = progress?.invoke()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val displayed = animateFloatAsState(target ?: 0f,
        if (reduce) snap() else tween(AppMotion.PROGRESS_MS, easing = AppMotion.easeOutCubic), label = "ringProgress")
    val phase = rememberProgressPhase(target == null && !reduce, durationMillis)
    val density = LocalDensity.current
    val stroke = remember(density, strokeWidth) { Stroke(with(density) { strokeWidth.toPx() }, cap = StrokeCap.Round) }
    Canvas(modifier.size(40.dp).semantics {
        progressBarRangeInfo = target?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
    }) {
        val radius = ((size.minDimension - stroke.width) / 2f).coerceAtLeast(0f)
        drawAppProgressRing(if (target == null) null else displayed.value, phase.value, color, trackColor, center, radius, stroke)
    }
}
