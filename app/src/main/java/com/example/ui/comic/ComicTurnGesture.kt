package com.example.ui.comic

import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
internal fun comicPagerThreshold(vertical: Boolean): Float {
    val configuration = LocalConfiguration.current
    val span = (if (vertical) configuration.screenHeightDp else configuration.screenWidthDp).toFloat().coerceAtLeast(1f)
    return (comicTurnTravel(span, 1f) / span).coerceIn(0.04f, 0.35f)
}

/** Physical travel stays bounded on tablets; velocity only helps intentional swipes. */
internal fun comicTurnTravel(spanPx: Float, density: Float): Float =
    (spanPx * 0.18f).coerceIn(64f * density, 104f * density)

internal fun comicTurnIntent(distancePx: Float, velocityPx: Float, spanPx: Float, density: Float): Int {
    if (!distancePx.isFinite() || !velocityPx.isFinite() || spanPx <= 0f || density <= 0f) return 0
    val distance = abs(distancePx)
    val reversed = velocityPx * distancePx < 0f && abs(velocityPx) >= 850f * density
    if (reversed) return 0
    val far = distance >= comicTurnTravel(spanPx, density)
    val flick = distance >= 18f * density && abs(velocityPx) >= 850f * density && velocityPx * distancePx > 0f
    return if (far || flick) distancePx.sign.toInt() else 0
}
