package com.example.ui.wenku

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** The route lends its push clock to the incoming labels. The outgoing scene stays fully painted. */
@Composable
internal fun WenkuPageEntrance(
    route: Transition<EnterExitState>,
    content: @Composable () -> Unit,
) {
    var presented by rememberSaveable { mutableStateOf(false) }
    val firstPresentation = remember { !presented }
    LaunchedEffect(Unit) { presented = true }
    val animate = firstPresentation &&
        rememberWenkuAccessibility().decorativeMotion && LocalWenkuDecorativeMotion.current
    if (!animate) {
        content()
        return
    }
    val progress = route.animateFloat(
        transitionSpec = {
            if (initialState == EnterExitState.PreEnter && targetState == EnterExitState.Visible) {
                tween(WenkuMotion.ENTER_MS, easing = LinearEasing)
            } else {
                snap()
            }
        },
        label = "routeContentEntrance",
    ) { phase -> if (phase == EnterExitState.PreEnter) 0f else 1f }
    WenkuContentClock(progress, content)
}

fun Modifier.wenkuPagePart(order: Int = 0): Modifier = wenkuReveal(order)
