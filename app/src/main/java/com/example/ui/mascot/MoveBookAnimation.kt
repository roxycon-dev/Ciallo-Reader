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
fun MoveBookAnimation(onComplete: () -> Unit) {
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

    // 3-Phase Move Motion: Anticipation -> Action -> Recovery
    val transform = remember(progress) {
        when {
            progress < 0.18f -> MotionTransform(1.0f, 1.0f, 0f, 0f)
            progress < 0.35f -> {
                val p = (progress - 0.18f) / 0.17f
                MotionTransform(1.1f, 0.92f, -15f * p, -10f * p) // Lean left anticipation
            }
            progress < 0.65f -> {
                val p = (progress - 0.35f) / 0.30f
                MotionTransform(0.92f + p * 0.16f, 1.08f - p * 0.16f, -15f + p * 30f, -10f + p * 20f) // Carry swing action
            }
            progress < 0.82f -> {
                val p = (progress - 0.65f) / 0.17f
                MotionTransform(1.08f - p * 0.08f, 0.92f + p * 0.08f, 15f - p * 15f, 10f - p * 10f) // Settle nod recovery
            }
            else -> MotionTransform(1.0f, 1.0f, 0f, 0f)
        }
    }

    // Keyframe pose switching
    val currentDrawableRes = when {
        progress < 0.25f -> MascotSpriteSheet.idleDrawable
        else -> MascotSpriteSheet.moveDrawable
    }

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = popupScale
                scaleY = popupScale
                alpha = popupAlpha
            }
            .shadow(24.dp, RoundedCornerShape(24.dp), spotColor = Color(0xFF5CB8E4))
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xF216222A),
                        Color(0xF2101820)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF5CB8E4).copy(alpha = 0.8f),
                        Color(0xFF03A9F4).copy(alpha = 0.3f)
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
                    contentDescription = "Move Mascot",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = tr("📦 已移动到新书架"),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF5CB8E4),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = tr("藏书整理得整整齐齐啦~"),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}
