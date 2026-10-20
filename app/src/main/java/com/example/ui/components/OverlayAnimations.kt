package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun DialogEntrance(modifier: Modifier = Modifier, durationMillis: Int = 240,
    visibility: MutableTransitionState<Boolean>? = null, content: @Composable () -> Unit) {
    val state = visibility ?: LocalPaperVisibility.current ?: remember { MutableTransitionState(false).apply { targetState = true } }
    PullOutVisibility(state, modifier) { content() }
}

@Composable
fun BottomSheetEntrance(modifier: Modifier = Modifier, durationMillis: Int = 260,
    visibility: MutableTransitionState<Boolean>? = null, content: @Composable () -> Unit) {
    DialogEntrance(modifier, durationMillis, visibility, content)
}

@Composable
fun ScrimEntrance(modifier: Modifier = Modifier, durationMillis: Int = 220,
    visibility: MutableTransitionState<Boolean>? = null, content: @Composable () -> Unit) {
    val shared = visibility ?: LocalPaperVisibility.current
    if (shared != null) {
        val life = rememberPaperLifecycle(shared)
        if (shared.targetState || life.retained || !life.closed) {
            Box(modifier.graphicsLayer { alpha = life.progress.coerceIn(0f, 1f) }) { content() }
        }
    } else {
        val state = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(state, modifier, enter = fadeIn(PullOutConfig.spring(true)), exit = fadeOut(PullOutConfig.spring(false))) { content() }
    }

}

@Composable
fun PullOutScrim(visible: Boolean, modifier: Modifier = Modifier,
    enter: EnterTransition = EnterTransition.None, exit: ExitTransition = ExitTransition.None,
    content: @Composable AnimatedVisibilityScope.() -> Unit) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(PullOutConfig.spring(true)),
        exit = fadeOut(PullOutConfig.spring(false)), content = content)
}
