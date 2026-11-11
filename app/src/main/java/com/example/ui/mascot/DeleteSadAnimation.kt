package com.example.ui.mascot

import com.example.i18n.tr

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DeleteSadAnimation(onComplete: () -> Unit) {
    val animState = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        animState.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 850, easing = LinearEasing)
        )
        onComplete()
    }

    val progress = animState.value

    // Pop-up container entrance and exit transition
    val popupScale = when {
        progress < 0.2f -> 0.4f + (progress / 0.2f) * 0.68f
        progress < 0.28f -> 1.08f - ((progress - 0.2f) / 0.08f) * 0.08f
        progress > 0.8f -> 1.0f - ((progress - 0.8f) / 0.2f) * 0.2f
        else -> 1.0f
    }

    val popupAlpha = when {
        progress < 0.15f -> progress / 0.15f
        progress > 0.8f -> 1.0f - ((progress - 0.8f) / 0.2f)
        else -> 1.0f
    }

    // 3-Phase Sad Motion: Anticipation (Shrink) -> Action (Shiver) -> Recovery (Sigh)
    val transform = remember(progress) {
        when {
            progress < 0.18f -> MotionTransform(1.0f, 1.0f, 0f, 0f)
            progress < 0.35f -> {
                val p = (progress - 0.18f) / 0.17f
                MotionTransform(1f - p * 0.15f, 1f - p * 0.10f, 0f, p * 6f) // Shrink
            }
            progress < 0.65f -> {
                val p = (progress - 0.35f) / 0.30f
                val shiver = kotlin.math.sin(p * Math.PI * 6).toFloat() * 10f
                MotionTransform(0.85f + kotlin.math.sin(p * Math.PI * 4).toFloat() * 0.1f, 0.90f, shiver, 6f - p * 12f) // Shiver
            }
            progress < 0.82f -> {
                val p = (progress - 0.65f) / 0.17f
                MotionTransform(0.95f + p * 0.05f, 0.90f + p * 0.10f, 0f, -6f + p * 6f) // Sigh
            }
            else -> MotionTransform(1.0f, 1.0f, 0f, 0f)
        }
    }

    // Keyframe pose switching
    val currentDrawableRes = when {
        progress < 0.25f -> MascotSpriteSheet.idleDrawable
        else -> MascotSpriteSheet.sadDrawable
    }

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = popupScale
                scaleY = popupScale
                alpha = popupAlpha
            }
            .shadow(24.dp, RoundedCornerShape(24.dp), spotColor = Color(0xFFFF6B81))
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xF2281822),
                        Color(0xF21C121A)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFFF6B81).copy(alpha = 0.8f),
                        Color(0xFFE91E63).copy(alpha = 0.3f)
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(horizontal = 28.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .graphicsLayer {
                        scaleX = transform.scaleX
                        scaleY = transform.scaleY
                        translationX = transform.translationOffset
                        rotationZ = transform.rotationDeg
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = currentDrawableRes),
                    contentDescription = "Sad Mascot",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = tr("🌧️ 成功移出书架"),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF6B81),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = tr("别难过，随时可以在新分类重新找到它~"),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}
