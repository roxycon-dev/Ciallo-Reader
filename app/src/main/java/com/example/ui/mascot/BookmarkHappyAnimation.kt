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
fun BookmarkHappyAnimation(onComplete: () -> Unit) {
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
        progress < 0.2f -> 0.4f + (progress / 0.2f) * 0.68f // 0.4 -> 1.08 overshoot
        progress < 0.28f -> 1.08f - ((progress - 0.2f) / 0.08f) * 0.08f // 1.08 -> 1.00 settle
        progress > 0.8f -> 1.0f - ((progress - 0.8f) / 0.2f) * 0.2f // 1.0 -> 0.8 exit
        else -> 1.0f
    }

    val popupAlpha = when {
        progress < 0.15f -> progress / 0.15f
        progress > 0.8f -> 1.0f - ((progress - 0.8f) / 0.2f)
        else -> 1.0f
    }

    // Mascot 3-Phase Motion: Anticipation (Crouch) -> Action (Leap) -> Recovery (Land)
    val transform = remember(progress) {
        when {
            progress < 0.15f -> MotionTransform(1.0f, 1.0f, 0f, 0f)
            progress < 0.32f -> {
                val p = (progress - 0.15f) / 0.17f
                MotionTransform(1f + p * 0.22f, 1f - p * 0.22f, p * 12f, -p * 4f) // Crouch
            }
            progress < 0.62f -> {
                val p = (progress - 0.32f) / 0.30f
                MotionTransform(1.22f - p * 0.40f, 0.78f + p * 0.45f, 12f - p * 48f, -4f + p * 12f) // Leap
            }
            progress < 0.82f -> {
                val p = (progress - 0.62f) / 0.20f
                MotionTransform(0.82f + p * 0.24f, 1.23f - p * 0.28f, -36f + p * 36f, 8f - p * 8f) // Land
            }
            else -> MotionTransform(1.06f, 0.95f, 0f, 0f)
        }
    }

    // Keyframe pose switching based on animation phase
    val currentDrawableRes = when {
        progress < 0.25f -> MascotSpriteSheet.idleDrawable
        progress < 0.75f -> MascotSpriteSheet.bookmarkDrawable
        else -> MascotSpriteSheet.happyDrawable
    }

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = popupScale
                scaleY = popupScale
                alpha = popupAlpha
            }
            .shadow(24.dp, RoundedCornerShape(24.dp), spotColor = Color(0xFF52D1AC))
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xF21C2230),
                        Color(0xF2131622)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF52D1AC).copy(alpha = 0.8f),
                        Color(0xFF4DB6AC).copy(alpha = 0.3f)
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
                        translationY = transform.translationOffset
                        rotationZ = transform.rotationDeg
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = currentDrawableRes),
                    contentDescription = "Bookmark Mascot",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = tr("✨ 书签已保存"),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF52D1AC),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Ciallo～(∠・ω< )⌒★",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
        }
    }
}
