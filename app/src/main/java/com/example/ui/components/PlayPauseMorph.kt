/*
 * Geometry adapted from skydoves/compose-animations (Apache-2.0):
 * https://github.com/skydoves/compose-animations/blob/main/app/src/main/kotlin/com/skydoves/hotreloadanimations/animations/AnimationExample11.kt
 */
package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.example.ui.feedback.AppMotion
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.theme.clickableWithFeedback

@Composable
fun PlayPauseMorph(progress: Float, iconBoxDp: Int) =
    PlayPauseGlyph({ progress }, iconBoxDp, { Color.White })


/** Reuses both paths and reads progress while drawing, not while composing the button. */
@Composable
private fun PlayPauseGlyph(progress: () -> Float, iconBoxDp: Int, color: () -> Color) {
    val left = remember { Path() }
    val right = remember { Path() }
    Canvas(Modifier.size((iconBoxDp / 2).dp)) {
        val p = progress().coerceIn(0f, 1f)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = size.width * .32f
        val bar = size.width * .12f
        val gap = size.width * .18f
        fun mix(a: Float, b: Float) = a + (b - a) * p
        left.reset()
        left.moveTo(mix(cx - half * .65f, cx - gap - bar), cy - half)
        left.lineTo(mix(cx + half, cx - gap + bar), mix(cy, cy - half))
        left.lineTo(mix(cx + half, cx - gap + bar), mix(cy, cy + half))
        left.lineTo(mix(cx - half * .65f, cx - gap - bar), cy + half)
        left.close()
        right.reset()
        right.moveTo(mix(cx + half, cx + gap - bar), mix(cy, cy - half))
        right.lineTo(mix(cx + half, cx + gap + bar), mix(cy, cy - half))
        right.lineTo(mix(cx + half, cx + gap + bar), mix(cy, cy + half))
        right.lineTo(mix(cx + half, cx + gap - bar), mix(cy, cy + half))
        right.close()
        drawPath(left, color())
        drawPath(right, color())
    }
}

@Composable
fun PlayPauseMorphButton(isPlaying: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, sizeDp: Int = 40) {
    val reduce = LocalReduceMotion.current
    val colors = MaterialTheme.colorScheme
    val morph = animateFloatAsState(if (isPlaying) 1f else 0f,
        if (reduce) snap() else AppMotion.icon(), label = "playPauseShape")
    val background = animateColorAsState(if (isPlaying) colors.primary else colors.secondary,
        if (reduce) snap() else AppMotion.icon(), label = "playPauseBackground")
    val ink = if (isPlaying) colors.onPrimary else colors.onSecondary
    Box(modifier.size(sizeDp.dp).semantics {
        role = Role.Button
        contentDescription = if (isPlaying) "暂停" else "播放"
    }.clip(CircleShape).drawBehind { drawCircle(background.value) }
        .clickableWithFeedback(onClick = onClick), contentAlignment = Alignment.Center) {
        PlayPauseGlyph({ morph.value }, sizeDp, { ink })
    }
}
