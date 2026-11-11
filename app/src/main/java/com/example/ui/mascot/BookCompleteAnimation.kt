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

/**
 * 读完一本书的庆祝动画：Roxy 欢呼（celebrate 素材）+ 金色庆祝主题。
 * 与书签动画完全独立 —— 无任何"书签"文案/素材。
 */
@Composable
fun BookCompleteAnimation(onComplete: () -> Unit) {
    val animState = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        animState.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1400, easing = LinearEasing)
        )
        onComplete()
    }

    val progress = animState.value

    val popupScale = when {
        progress < 0.15f -> 0.4f + (progress / 0.15f) * 0.68f
        progress < 0.24f -> 1.08f - ((progress - 0.15f) / 0.09f) * 0.08f
        progress > 0.85f -> 1.0f - ((progress - 0.85f) / 0.15f) * 0.2f
        else -> 1.0f
    }

    val popupAlpha = when {
        progress < 0.12f -> progress / 0.12f
        progress > 0.85f -> 1.0f - ((progress - 0.85f) / 0.15f)
        else -> 1.0f
    }

    /* Roxy 三段动作：蓄力 → 跳起欢呼（双跳）→ 落地 */
    val transform = remember(progress) {
        when {
            progress < 0.12f -> MotionTransform(1f, 1f, 0f, 0f)
            progress < 0.28f -> {
                val p = (progress - 0.12f) / 0.16f
                MotionTransform(1f + p * 0.20f, 1f - p * 0.20f, p * 10f, -p * 3f)          // 蓄力下蹲
            }
            progress < 0.50f -> {
                val p = (progress - 0.28f) / 0.22f
                MotionTransform(1.20f - p * 0.38f, 0.80f + p * 0.42f, 10f - p * 44f, -3f + p * 10f) // 第一跳
            }
            progress < 0.68f -> {
                val p = (progress - 0.50f) / 0.18f
                MotionTransform(0.84f + p * 0.30f, 1.16f - p * 0.30f, -34f + p * 30f, 7f - p * 6f)  // 落地
            }
            progress < 0.84f -> {
                val p = (progress - 0.68f) / 0.16f
                MotionTransform(1.16f - p * 0.30f, 0.88f + p * 0.34f, -4f - p * 40f, 1f - p * 5f)   // 二连跳
            }
            else -> {
                val p = ((progress - 0.84f) / 0.16f).coerceIn(0f, 1f)
                MotionTransform(0.86f + p * 0.20f, 1.22f - p * 0.27f, -44f + p * 44f, 2f - p * 2f)  // 收势
            }
        }
    }

    /* 素材：庆祝姿态全程 */
    val currentDrawableRes = MascotSpriteSheet.celebrateDrawable

    /* 金色庆祝彩带粒子（简单圆点，按进度向外飘散） */
    val confetti = remember { List(10) { androidx.compose.ui.geometry.Offset(Math.random().toFloat(), Math.random().toFloat()) } }

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = popupScale
                scaleY = popupScale
                alpha = popupAlpha
            }
            .shadow(24.dp, RoundedCornerShape(24.dp), spotColor = Color(0xFFF0B95E))
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xF22A2418),
                        Color(0xF21E1A10)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFF0B95E).copy(alpha = 0.9f),
                        Color(0xFFD9A441).copy(alpha = 0.35f)
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(horizontal = 28.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        /* 彩带粒子层 */
        confetti.forEachIndexed { i, seed ->
            val t = ((progress * 1.4f + seed.x) % 1f)
            val dir = if (i % 2 == 0) -1f else 1f
            Box(
                modifier = Modifier
                    .offset(x = (dir * (30 + seed.y * 60) * t).dp, y = (-t * 90 + seed.x * 40).dp)
                    .size(if (i % 3 == 0) 5.dp else 3.dp)
                    .graphicsLayer { alpha = (1f - t) * 0.9f }
                    .background(
                        when (i % 4) {
                            0 -> Color(0xFFF0B95E)
                            1 -> Color(0xFF52D1AC)
                            2 -> Color.White
                            else -> Color(0xFFE17055)
                        },
                        androidx.compose.foundation.shape.CircleShape
                    )
            )
        }

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
                    contentDescription = tr("Roxy 庆祝"),
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = tr("🎉 恭喜读完这本书！"),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF0B95E),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = tr("Roxy 为你欢呼 ～✨"),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
        }
    }
}
