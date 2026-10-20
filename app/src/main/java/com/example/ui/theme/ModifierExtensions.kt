package com.example.ui.theme

import com.example.ui.components.pullOutClickable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * A highly polished custom clickable modifier that combines:
 * 1) No default ripple/highlight
 * 2) Smooth scale feedback (shrinks to 0.96 on press, eases back to 1.0 on release)
 * 3) Subtle glow overlay
 * 4) Haptic tick on press (tactile confirmation)
 */
fun Modifier.clickableWithFeedback(
    enabled: Boolean = true,
    bounded: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current

    val scaleState = animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        animationSpec = tween(
            durationMillis = if (isPressed) 100 else 150,
            easing = EaseOut
        ),
        label = "click_scale"
    )

    // 2026-09-21 修复：按压高亮原本是 drawWithContent 叠一层 **白色矩形**（alpha 0.25）。
    // 在深色卡片 / 毛玻璃上按下会闪出一块白斑，在彩色内容上则整体泛白 —— 廉价且脏，
    // 是全局最不像 iOS 的一处交互。iOS 的按压反馈是「整体变淡」（dim 到 0.7），
    // 只改透明度、不改变任何颜色关系，对任意底色都成立。
    val dimState = animateFloatAsState(
        targetValue = if (isPressed) 0.7f else 1f,
        animationSpec = tween(
            durationMillis = if (isPressed) 100 else 180,
            easing = EaseOut
        ),
        label = "click_dim"
    )

    // Haptic tick on press。
    // 修复：原先直接在组合期调用，重组会重复触发（同一按住状态被振多次）。
    // 放进 LaunchedEffect 后只在「从未按下 -> 按下」这一跳变时执行一次。
    LaunchedEffect(isPressed) {
        if (isPressed) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    this
        .graphicsLayer {
            scaleX = scaleState.value
            scaleY = scaleState.value
            alpha = dimState.value
        }
        .pullOutClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * iOS 列表行的按压反馈（2026-09-21 新增）。
 *
 * 与 [clickableWithFeedback] 的区别：列表行（整行宽、44dp 高以上）按下时
 * **只铺一层次级背景色，不改变尺寸**。iOS 的 UITableViewCell 就是这么做的 ——
 * 整行缩放到 0.96 会显得晃眼，而整个 App 一起变淡也太重。
 * 手感：按下瞬间出现浅灰底（80ms），松手后缓慢淡出（220ms），并给一次轻震动。
 */
fun Modifier.clickableRowFeedback(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current

    // 按下要“立刻”有反应（80ms），松手则缓慢淡出（220ms），这个不对称是 iOS 手感的关键
    val highlightAlpha by animateFloatAsState(
        targetValue = if (isPressed) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isPressed) 80 else 220,
            easing = EaseOut
        ),
        label = "row_highlight"
    )

    LaunchedEffect(isPressed) {
        if (isPressed) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    this
        .drawBehind {
            if (highlightAlpha > 0f) {
                // 中性灰，深浅主题下都成立；不用白色（深色卡片上会发白）
                drawRect(
                    color = Color.Gray.copy(alpha = 0.16f * highlightAlpha),
                    size = size
                )
            }
        }
        .pullOutClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * 透明度过渡：把 `graphicsLayer { alpha = if (x) 0f else 1f }` 这种**瞬间闪没**
 * 改成平滑淡入淡出。
 *
 * 只叠 graphicsLayer，不会引入 clickable / pointerInput，
 * 因此用于「禁止命中测试」的装饰链（如阅读页页眉页脚）是安全的。
 */
fun Modifier.animateAlpha(
    target: Float,
    durationMillis: Int = 220,
    label: String = "animateAlpha"
): Modifier = composed {
    val alpha by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = durationMillis),
        label = label
    )
    this.graphicsLayer { this.alpha = alpha }
}

