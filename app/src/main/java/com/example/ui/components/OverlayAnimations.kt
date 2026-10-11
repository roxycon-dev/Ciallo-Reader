package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.feedback.AppMotion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.FastOutSlowInEasing
import com.example.ui.theme.IosMotion

/*
 * ══════════════ 弹层入场动画（第十一轮第 4 条）══════════════
 * 长按菜单 / 隐私弹窗 / 底部面板此前都是"瞬间出现"（无过渡，观感跳变）。
 * 这里提供与 App 其它弹窗转场（Nav 转场 tween 200-320ms fade+scale，
 * 见 MainActivity NavHost / reader 路由）同风格的两套入场：
 *  - 弹窗（居中卡）：fade + 轻微放大（0.96 → 1.0）
 *  - 底部面板：fade + 从底部上滑（1/6 屏高）
 * 实现注：弹层多为条件组合（状态非空即挂载），退出瞬间即离场，因此
 * 入场动画用 MutableTransitionState(true) 模式在挂载首帧起播。
 */

/** 居中弹窗入场：淡入 + 0.96→1 缩放（与 Nav 弹窗转场同款节奏）。 */
@Composable
fun DialogEntrance(
    modifier: Modifier = Modifier,
    durationMillis: Int = 240,
    visibility: MutableTransitionState<Boolean>? = null,
    content: @Composable () -> Unit,
) {
    val entrance = visibility ?: remember { MutableTransitionState(false).apply { targetState = true } }
    val reduce = LocalReduceMotion.current
    // 2026-09-21：改用 iOS 弹簧（缩放走 spring、淡入走短 easeOut）。
    // 原来是 Material 的 FastOutSlowInEasing 补间，收尾偏“钝”；
    // iOS 的弹窗是 0.92 起跳 + 临界阻尼弹簧，落位干脆且有一点点“吸附”手感。
    AnimatedVisibility(
        visibleState = entrance,
        modifier = modifier,
        enter = if (reduce) fadeIn(tween(0)) else fadeIn(tween(180, easing = IosMotion.EaseOut)) +
            scaleIn(
                initialScale = 0.96f,
                animationSpec = IosMotion.spring()
            ),
        exit = if (reduce) fadeOut(tween(0)) else fadeOut(tween(AppMotion.EXIT_MS)) + scaleOut(tween(AppMotion.EXIT_MS), targetScale = .97f),
    ) {
        content()
    }
}

/** 底部面板入场：淡入 + 从底部上滑（底部弹层/长按操作菜单）。 */
@Composable
fun BottomSheetEntrance(
    modifier: Modifier = Modifier,
    durationMillis: Int = 260,
    visibility: MutableTransitionState<Boolean>? = null,
    content: @Composable () -> Unit,
) {
    val entrance = visibility ?: remember { MutableTransitionState(false).apply { targetState = true } }
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visibleState = entrance,
        modifier = modifier,
        // 2026-09-21：底部面板改为 iOS 弹簧上推（位移大，用偏软的 spring 更自然），
        // 淡入同步缩短，避免遮罩比面板“先到位”的割裂感。
        enter = if (reduce) fadeIn(tween(0)) else fadeIn(tween(160, easing = IosMotion.EaseOut)) +
            slideInVertically(
                initialOffsetY = { it / 6 },
                animationSpec = IosMotion.springSoft()
            ),
        exit = if (reduce) fadeOut(tween(0)) else fadeOut(tween(AppMotion.EXIT_MS)) + slideOutVertically(tween(AppMotion.EXIT_MS)) { it / 6 },
    ) {
        content()
    }
}

/** 遮罩层入场：纯淡入（不抢面板主体动画）。 */
@Composable
fun ScrimEntrance(
    modifier: Modifier = Modifier,
    durationMillis: Int = 220,
    visibility: MutableTransitionState<Boolean>? = null,
    content: @Composable () -> Unit,
) {
    val entrance = visibility ?: remember { MutableTransitionState(false).apply { targetState = true } }
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visibleState = entrance,
        modifier = modifier,
        enter = fadeIn(tween(if (reduce) 0 else durationMillis)),
        exit = fadeOut(tween(if (reduce) 0 else AppMotion.EXIT_MS)),
    ) {
        content()
    }
}
