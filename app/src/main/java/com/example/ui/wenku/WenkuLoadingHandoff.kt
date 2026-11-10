package com.example.ui.wenku

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect

/**
 * 骨架 → 内容的「擦除交接」（参考 `loading_handoff.dart:6-98`）。
 *
 * 与普通 crossfade 的区别：**骨架始终留在未揭示的那一段下面**。
 * 内容按 320ms 从顶部向下揭示（`clipRect(bottom = h·p)`），骨架同时只画
 * `top = h·p` 以下的区域——所以即使内容晚一帧到，也不会出现空白页。
 *
 * - [loaded] 从 false 变 true 时起跑；变回 false 直接回到骨架；
 * - `reduceMotion` 直接落终点；
 * - 组件按 [content] 的尺寸撑开，骨架用 `matchParentSize` 拉伸到同一高度。
 */
@Composable
fun WenkuLoadingHandoff(
    loaded: Boolean,
    modifier: Modifier = Modifier,
    skeleton: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val animate = rememberWenkuAccessibility().decorativeMotion && LocalWenkuDecorativeMotion.current
    val progress = remember { Animatable(if (loaded && !animate) 1f else 0f) }
    val showSkeleton by remember(progress) { derivedStateOf { progress.value < 1f } }
    LaunchedEffect(loaded, animate) {
        if (!loaded) {
            progress.snapTo(0f)
        } else if (!animate) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = 320, easing = LinearEasing))
        }
    }

    Box(modifier.fillMaxWidth()) {
        // 内容：按揭示进度从顶部向下裁切（它决定整体高度）
        Box(
            Modifier.drawWithContent {
                val p = WenkuMotion.ease.transform(progress.value.coerceIn(0f, 1f))
                clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height * p) {
                    this@drawWithContent.drawContent()
                }
            }
        ) {
            content()
        }
        // 骨架：只画尚未揭示的部分，保证「内容晚到也不露白」
        if (showSkeleton) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithContent {
                        val p = WenkuMotion.ease.transform(progress.value.coerceIn(0f, 1f))
                        clipRect(top = size.height * p, bottom = size.height) {
                            this@drawWithContent.drawContent()
                        }
                    }
            ) {
                skeleton()
            }
        }
    }
}
