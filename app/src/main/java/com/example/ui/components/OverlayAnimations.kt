package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.ui.wenku.WenkuContentClock
import com.example.ui.wenku.WenkuMotion
import com.example.ui.wenku.rememberWenkuAccessibility

@Composable
fun DialogEntrance(modifier: Modifier = Modifier, durationMillis: Int = WenkuMotion.ENTER_MS,
    visibility: MutableTransitionState<Boolean>? = null, content: @Composable () -> Unit) {
    OverlayEntrance(modifier, durationMillis, visibility, fromBottom = false, content = content)
}

@Composable
fun BottomSheetEntrance(modifier: Modifier = Modifier, durationMillis: Int = WenkuMotion.ENTER_MS,
    visibility: MutableTransitionState<Boolean>? = null, content: @Composable () -> Unit) {
    OverlayEntrance(modifier, durationMillis, visibility, fromBottom = true, content = content)
}

@Composable
private fun OverlayEntrance(
    modifier: Modifier,
    durationMillis: Int,
    visibility: MutableTransitionState<Boolean>?,
    fromBottom: Boolean,
    content: @Composable () -> Unit,
) {
    val state = visibility ?: LocalPaperVisibility.current ?: remember { MutableTransitionState(false).apply { targetState = true } }
    val reduced = rememberWenkuAccessibility().reduceMotion
    AnimatedVisibility(state, modifier, enter = EnterTransition.None, exit = ExitTransition.None) {
        val progress = transition.animateFloat(
            transitionSpec = {
                if (reduced) snap() else tween(
                    if (targetState == EnterExitState.Visible) durationMillis else WenkuMotion.EXIT_MS,
                    easing = LinearEasing,
                )
            },
            label = "glassWindowPlane",
        ) { phase -> if (phase == EnterExitState.Visible) 1f else 0f }
        Box(Modifier.graphicsLayer {
            val raw = progress.value.coerceIn(0f, 1f)
            val plane = WenkuMotion.plane.transform(raw)
            val remaining = 1f - plane
            alpha = if (fromBottom) 1f else WenkuMotion.ease.transform(raw)
            translationX = -(if (fromBottom) 18.dp else 8.dp).toPx() * remaining
            translationY = if (fromBottom) (size.height + 24.dp.toPx()) * remaining else {
                20.dp.toPx() * (1f - WenkuMotion.settle.transform(raw))
            }
        }) {
            if (reduced) content() else WenkuContentClock(progress, content)
        }
    }
}

@Composable
fun ScrimEntrance(modifier: Modifier = Modifier, durationMillis: Int = WenkuMotion.ENTER_MS,
    visibility: MutableTransitionState<Boolean>? = null, content: @Composable () -> Unit) {
    val shared = visibility ?: LocalPaperVisibility.current
    var standaloneVisible by remember { mutableStateOf(false) }
    LaunchedEffect(shared) { if (shared == null) standaloneVisible = true }
    val open = shared?.targetState ?: standaloneVisible
    val reduced = rememberWenkuAccessibility().reduceMotion
    val opacity = animateFloatAsState(
        if (open) 1f else 0f,
        if (reduced) snap() else tween(
            if (open) durationMillis else WenkuMotion.EXIT_MS,
            easing = WenkuMotion.plane,
        ),
        label = "glassWindowScrim",
    )
    Box(modifier.graphicsLayer { alpha = opacity.value }) { content() }
}

@Composable
fun PullOutScrim(visible: Boolean, modifier: Modifier = Modifier,
    enter: EnterTransition = EnterTransition.None, exit: ExitTransition = ExitTransition.None,
    content: @Composable AnimatedVisibilityScope.() -> Unit) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(PullOutConfig.spring(true)),
        exit = fadeOut(PullOutConfig.spring(false)), content = content)
}
