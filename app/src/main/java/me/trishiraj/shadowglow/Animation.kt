package me.trishiraj.shadowglow

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

@Composable
internal fun rememberAnimatedBreathingValue(
    enabled: Boolean,
    intensity: Dp,
    durationMillis: Int
): State<Float> {
    val density = LocalDensity.current
    val intensityPx = remember(intensity, density) { with(density) { intensity.toPx() } }

    if (!enabled || intensityPx <= 0f || durationMillis <= 0) {
        return remember { mutableFloatStateOf(0f) }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "breathingEffect")
    return infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = intensityPx,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingValue"
    )
}

@Composable
internal fun rememberGlowTrailProgess(
    enabled: Boolean,
    clockwise: Boolean,
    durationMillis: Int
): State<Float> {
    if (!enabled || durationMillis <= 0) {
        return remember { mutableFloatStateOf(0f) }
    }
    val transition = rememberInfiniteTransition(label = "glowTrail")
    return transition.animateFloat(
        initialValue = if (clockwise) 0f else 1f,
        targetValue = if (clockwise) 1f else 0f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "glowTrailAnim"
    )
}
