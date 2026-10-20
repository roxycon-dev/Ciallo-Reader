package com.example.ui.favorite

import com.example.ui.components.pullOutCombinedClickable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.feedback.AppMotion
import com.example.ui.feedback.HapticKind
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.feedback.rememberAppHaptics
import com.example.ui.theme.MintPrimary
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「喜欢」按钮（漫画主页面 / 顶部栏复用同一个组件）。
 *
 * 点击反馈分两种语义（设计稿 §1.1）：
 * - 喜欢：心形弹簧 0.6 → 1.25 → 1.0（≈350ms 轻微过冲）+ 填充色从中心扩散
 *   + 6~8 个小心形/圆点粒子飘散（≈600ms）+ light 触觉；
 * - 取消：心形收缩变灰，**无粒子**，selection tick 触觉。
 *
 * ⚠️ 反馈**只发生在按钮自己身上**。曾经有过一版"迷你封面/爱心沿抛物线飞向底部
 * 「书架」图标"的动效，用户判定为多余（视觉噪音大、和书架的因果关系是编出来的），
 * 已整体删除（ShelfFly 控制器 + 全屏覆盖层 + 落点弹跳）。这里不要再往回加。
 *
 * 长按 = 直接弹出分类选择 Sheet；无来源的书置灰并给出原因。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoriteHeartButton(
    favorite: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** 无来源信息（如手动导入的本地文件）→ 置灰，点击说明原因 */
    enabled: Boolean = true,
    disabledHint: String = "这本书没有来源信息，无法加入「我喜欢的」",
    showLabel: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    onDisabledClick: (() -> Unit)? = null,
) {
    val haptics = rememberAppHaptics()
    val reduceMotion = LocalReduceMotion.current

    val scale = remember { Animatable(1f) }
    val fill = remember { Animatable(if (favorite) 1f else 0f) }
    var burstId by remember { mutableIntStateOf(0) }
    val burst = remember { Animatable(0f) }

    LaunchedEffect(favorite) {
        if (favorite) {
            haptics.perform(HapticKind.LIGHT)
            fill.snapTo(0f)
            fill.animateTo(1f, tween(if (reduceMotion) 100 else 260))
            if (!reduceMotion) {
                scale.snapTo(0.6f)
                scale.animateTo(1.25f, AppMotion.springDefault)
                scale.animateTo(1f, tween(120))
                burstId++
            }
        } else {
            haptics.perform(HapticKind.SELECTION)
            fill.animateTo(0f, tween(if (reduceMotion) 100 else 180))
            if (!reduceMotion) {
                scale.animateTo(0.86f, tween(110))
                scale.animateTo(1f, AppMotion.springDefault)
            }
        }
    }

    LaunchedEffect(burstId) {
        if (burstId > 0 && !reduceMotion) {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(AppMotion.PARTICLE_MS, easing = AppMotion.easeOutCubic))
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(110),
        label = "fav_press",
    )

    val tint = if (favorite) MintPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    val containerAlpha = if (enabled) 1f else 0.4f

    Box(
        modifier = modifier
            .size(height = 48.dp, width = if (showLabel) 108.dp else 48.dp)
            .graphicsLayer {
                alpha = containerAlpha
                scaleX = pressScale
                scaleY = pressScale
            }
            .then(
                if (showLabel) Modifier
                    .background(
                        if (favorite) MintPrimary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                        RoundedCornerShape(24.dp)
                    )
                    .border(
                        1.dp,
                        if (favorite) MintPrimary.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
                        RoundedCornerShape(24.dp)
                    )
                else Modifier
            )
            .semantics {
                role = Role.Button
                contentDescription = if (!enabled) disabledHint
                else if (favorite) "已喜欢，点击取消喜欢"
                else "喜欢，加入我喜欢的"
            }
            .pullOutCombinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = true,
                onClick = {
                    if (!enabled) {
                        onDisabledClick?.invoke()
                        return@pullOutCombinedClickable
                    }
                    onToggle(!favorite)
                },
                onLongClick = {
                    if (enabled) onLongPress?.invoke() else onDisabledClick?.invoke()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (showLabel) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeartGlyph(
                favorite = favorite,
                fill = fill.value,
                scale = scale.value,
                tint = tint,
            )
            if (showLabel) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (favorite) "已喜欢" else "喜欢",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
        }
        if (!reduceMotion && burstId > 0 && burst.value < 1f) {
            // 粒子与本体同色：原来是薄荷绿，和正红的心摆在一起像两套语言
            HeartBurst(progress = burst.value, color = HeartMid)
        }
    }
}

/** 心形本体：描边 → 实心从中心扩散（两个图标叠加按 fill 交叉淡化）。 */
@Composable
private fun HeartGlyph(
    favorite: Boolean,
    fill: Float,
    scale: Float,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        // 空心态：Material 的描边心足够干净，保留。
        // 已喜欢时描边改成心形的正红，淡出过程才不会出现"薄荷绿→正红"的脏交叉过渡。
        Icon(
            imageVector = Icons.Outlined.FavoriteBorder,
            contentDescription = null,
            tint = if (favorite) HeartMid else tint,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { alpha = 1f - fill },
        )
        // 实心态：**必须是 [HeartArt]** —— Material 自带的实心心形图标是纯色扁平剪影，
        // 和空心态那颗渐变心不是同一个东西，点亮瞬间会"换个材质"。
        // 渐变心从中心长出来（0.6 → 1.0），外发光随 fill 淡入。
        HeartArt(
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    alpha = fill
                    scaleX = 0.6f + 0.4f * fill
                    scaleY = 0.6f + 0.4f * fill
                },
            glow = fill * 1.1f,
        )
    }
}

/** 6~8 个小心形/圆点粒子：从中心向外飘散并淡出（≈600ms）。 */
@Composable
private fun HeartBurst(progress: Float, color: Color) {
    val count = 7
    Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        repeat(count) { i ->
            val angle = (Math.PI * 2.0 * i / count) - Math.PI / 2.0
            val distance = (14f + 26f * progress)
            val alpha = (1f - progress).coerceIn(0f, 1f)
            val size = (7f - 3f * progress).dp
            val isHeart = i % 2 == 0
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = (cos(angle) * distance).toFloat()
                        translationY = (sin(angle) * distance).toFloat()
                        this.alpha = alpha
                        scaleX = 1f - 0.3f * progress
                        scaleY = 1f - 0.3f * progress
                    }
                    .size(size)
                    .then(
                        if (isHeart) Modifier else Modifier.background(color, CircleShape)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isHeart) {
                    // 粒子的心也用同一颗渐变心（4~7dp 下渐变几乎看不出来，
                    // 但外轮廓是同一套贝塞尔，缩放时不会变形）
                    HeartArt(modifier = Modifier.size(size), glow = 0f)
                }
            }
        }
    }
}

/** 顶部栏/卡片右下角的纯图标小心形（热区仍保证 ≥48dp）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoriteHeartIcon(
    favorite: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 24.dp,
) {
    FavoriteHeartButton(
        favorite = favorite,
        onToggle = onToggle,
        modifier = modifier,
        enabled = enabled,
        showLabel = false,
    )
}
