package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

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
    val state = visibility ?: LocalPaperVisibility.current ?: remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = state, modifier = modifier,
        enter = fadeIn(PullOutConfig.spring(true)), exit = fadeOut(PullOutConfig.spring(false))) { content() }
}

@Composable
fun PullOutScrim(visible: Boolean, modifier: Modifier = Modifier,
    enter: EnterTransition = EnterTransition.None, exit: ExitTransition = ExitTransition.None,
    content: @Composable AnimatedVisibilityScope.() -> Unit) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(PullOutConfig.spring(true)),
        exit = fadeOut(PullOutConfig.spring(false)), content = content)
}
