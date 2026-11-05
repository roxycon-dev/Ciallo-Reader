package com.example.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.feedback.AppMotion

/** Compatibility entry point: every loading surface now uses the same radial language. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ChasingDots(
    modifier: Modifier = Modifier,
    durationMillis: Int = AppMotion.PROGRESS_CYCLE_MS,
    delayBetweenDotsMillis: Int = 50,
    size: Dp = 40.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    circleRatio: Float = .25f,
) = AppProgressIndicator(modifier.size(size), color = color, durationMillis = durationMillis)
