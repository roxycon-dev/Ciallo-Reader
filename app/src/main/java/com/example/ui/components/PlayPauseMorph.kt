/*
 * Copied from skydoves/compose-animations (Apache-2.0):
 * https://github.com/skydoves/compose-animations/blob/main/app/src/main/kotlin/com/skydoves/hotreloadanimations/animations/AnimationExample11.kt
 * Play / Pause Morph：三角形播放键与暂停双竖条之间随 progress 形变。
 */
package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.ui.theme.clickableWithFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/** 播放/暂停形态动画（skydoves/compose-animations 原版实现）。 */
@Composable
fun PlayPauseMorph(progress: Float, iconBoxDp: Int) {
    Canvas(modifier = Modifier.size((iconBoxDp / 2).dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val triHalf = w * 0.32f
        val barHalfW = w * 0.12f
        val barGap = w * 0.18f

        val triTopX = cx - triHalf * 0.65f
        val triTopY = cy - triHalf
        val triBotX = cx - triHalf * 0.65f
        val triBotY = cy + triHalf
        val triRightX = cx + triHalf
        val triRightY = cy

        val leftBarL = cx - barGap - barHalfW
        val leftBarR = cx - barGap + barHalfW
        val rightBarL = cx + barGap - barHalfW
        val rightBarR = cx + barGap + barHalfW
        val barTop = cy - triHalf
        val barBot = cy + triHalf

        val leftPath = Path().apply {
            moveTo(lerp(triTopX, leftBarL, progress), lerp(triTopY, barTop, progress))
            lineTo(lerp(triRightX, leftBarR, progress), lerp(triRightY, barTop, progress))
            lineTo(lerp(triRightX, leftBarR, progress), lerp(triRightY, barBot, progress))
            lineTo(lerp(triBotX, leftBarL, progress), lerp(triBotY, barBot, progress))
            close()
        }
        val rightPath = Path().apply {
            moveTo(lerp(triRightX, rightBarL, progress), lerp(triRightY, barTop, progress))
            lineTo(lerp(triRightX, rightBarR, progress), lerp(triRightY, barTop, progress))
            lineTo(lerp(triRightX, rightBarR, progress), lerp(triRightY, barBot, progress))
            lineTo(lerp(triRightX, rightBarL, progress), lerp(triRightY, barBot, progress))
            close()
        }
        drawPath(path = leftPath, color = Color.White)
        drawPath(path = rightPath, color = Color.White)
        drawCircle(color = Color.Transparent, radius = 0f, center = Offset(cx, cy))
    }
}

private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

/** 圆形播放/暂停按钮：点击时播放键与暂停键随背景色一起形变。 */
@Composable
fun PlayPauseMorphButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Int = 40
) {
    val morphProgress by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "morph"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isPlaying) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondary
        },
        animationSpec = tween(durationMillis = 500),
        label = "morphBg"
    )
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(color = bgColor, shape = CircleShape)
            .clickableWithFeedback { onClick() },
        contentAlignment = Alignment.Center
    ) {
        PlayPauseMorph(progress = morphProgress, iconBoxDp = sizeDp)
    }
}
