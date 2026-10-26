package com.example.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.feedback.LocalReduceMotion

internal data class LiquidSegmentOption(val id: Int, val label: String, val badge: Int = 0, val favorite: Boolean = false)

/** Native optics below real, focusable Compose radio buttons (including TalkBack actions). */
@Composable
internal fun LiquidSegmentedSelector(
    options: List<LiquidSegmentOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    fontSp: Float = 12f,
) {
    if (options.isEmpty()) return
    if (!LocalRenderQuality.current.enhancedMotion) {
        OrdinarySegmentedSelector(options, selected, onSelect, modifier, height, fontSp)
        return
    }
    val colors = MaterialTheme.colorScheme
    val reduceMotion = LocalReduceMotion.current
    val quality = LocalRenderQuality.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val visualOptions = remember(options, rtl) { if (rtl) options.reversed() else options }
    val holder = remember { arrayOfNulls<LiquidSegmentView>(1) }
    val select = rememberUpdatedState(onSelect)
    val currentSelected = rememberUpdatedState(selected)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focus = remember(options.map { it.id }) { options.map { FocusRequester() } }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            holder[0]?.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); holder[0]?.setActive(false) }
    }

    Box(modifier.fillMaxWidth().height(height).selectableGroup()
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                holder[0]?.setDropPressed(true)
                try { waitForUpOrCancellation(pass = PointerEventPass.Initial) }
                finally { holder[0]?.setDropPressed(false) }
            }
        }
        .pointerInput(visualOptions.map { it.id }) {
            var lastTime = 0L
            var speed = 0f
            detectHorizontalDragGestures(
                onDragStart = { holder[0]?.beginDrag(); lastTime = 0L; speed = 0f },
                onDragEnd = {
                    holder[0]?.endDrag(cancelled = false)?.let { id ->
                        if (id != currentSelected.value) select.value(id)
                    }
                },
                onDragCancel = { holder[0]?.endDrag(cancelled = true) },
                onHorizontalDrag = { change, amount ->
                    val dt = if (lastTime == 0L) 16L else (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                    lastTime = change.uptimeMillis
                    speed = speed * .6f + amount * 1000f / dt * .4f
                    holder[0]?.dragBy(amount, speed)
                    change.consume()
                },
            )
        }) {
        AndroidView(
            factory = { context -> LiquidSegmentView(context).also { holder[0] = it } },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view -> view.release(); if (holder[0] === view) holder[0] = null },
            update = { view ->
                view.configure(visualOptions, selected, colors.surface.toArgb(), colors.primary.toArgb(),
                    colors.onSurfaceVariant.toArgb(), colors.secondary.toArgb(), colors.surface.luminance() < .5f,
                    fontSp, reduceMotion, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), quality.decorativeEffects)
            },
        )
        // Transparent text retains the same labels and accessibility without painting them twice.
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { index, option ->
                Box(Modifier.weight(1f).fillMaxHeight()
                    .focusRequester(focus[index])
                    .onPreviewKeyEvent { event ->
                        val delta = when (event.key) {
                            Key.DirectionRight -> if (rtl) -1 else 1
                            Key.DirectionLeft -> if (rtl) 1 else -1
                            Key.DirectionDown -> 1
                            Key.DirectionUp -> -1
                            Key.MoveHome -> -options.size
                            Key.MoveEnd -> options.size
                            else -> 0
                        }
                        if (event.type != KeyEventType.KeyDown || delta == 0) false else {
                            val next = (index + delta).coerceIn(options.indices)
                            if (options[next].id != currentSelected.value) select.value(options[next].id)
                            focus[next].requestFocus()
                            true
                        }
                    }
                    .selectable(selected = selected == option.id, role = Role.RadioButton, indication = null,
                        interactionSource = null, onClick = { if (option.id != currentSelected.value) select.value(option.id) })
                    .semantics { if (option.badge > 0) stateDescription = "${option.badge} 项" },
                    contentAlignment = Alignment.Center,
                ) { Text(option.label, color = Color.Transparent, fontSize = fontSp.sp) }
            }
        }
    }
}
