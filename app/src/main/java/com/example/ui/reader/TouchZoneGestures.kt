package com.example.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

data class ReaderTouchBinding(
    val zones: List<TouchZone>? = null,
    val onViewport: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    val onAction: (TouchAction) -> Unit = {},
)
val LocalReaderTouchBinding = compositionLocalOf { ReaderTouchBinding() }

/** Owns a single-finger gesture from DOWN to UP. The start rectangle remains locked throughout. */
fun Modifier.touchZoneGestures(
    zones: List<TouchZone>?,
    defaultDistance: (Float) -> Float,
    onAction: (TouchAction) -> Unit,
    vertical: Boolean = false,
    reverse: Boolean = false,
    enabled: Boolean = true,
    zoomed: () -> Boolean = { false },
    taps: Boolean = true,
    menuVisible: Boolean = false,
    imageTap: ((Offset) -> Boolean)? = null,
    onLongPress: (() -> Unit)? = null,
    legacyPageCurl: Boolean = false,
): Modifier = composed {
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    val currentAction by rememberUpdatedState(onAction)
    val currentZoom by rememberUpdatedState(zoomed)
    val currentMenu by rememberUpdatedState(menuVisible)
    val currentImage by rememberUpdatedState(imageTap)
    val currentHold by rememberUpdatedState(onLongPress)
    val scope = rememberCoroutineScope()
    if (zones == null || !enabled) this else this.onGloballyPositioned { windowOrigin = it.positionInWindow() }.pointerInput(zones, vertical, reverse, taps) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (currentZoom() || down.isConsumed) return@awaitEachGesture
            val zone = TouchZone.hit(zones, down.position.x / size.width.coerceAtLeast(1), down.position.y / size.height.coerceAtLeast(1))
            val threshold = zone?.distanceDp?.dp?.toPx() ?: if (legacyPageCurl)
                kotlin.math.abs(down.position.x - size.width / 2f).coerceAtLeast(viewConfiguration.touchSlop)
                else defaultDistance(if (vertical) size.height.toFloat() else size.width.toFloat())
            val decision = TouchGestureDecision(viewConfiguration.touchSlop, threshold)
            var up = down
            var owned = false
            var lastPosition = down.position
            var holdFired = false
            val holdJob = if (taps && currentHold != null) scope.launch {
                delay(1000)
                if (!decision.moved && !decision.cancelled && !currentMenu) {
                    holdFired = true; currentHold?.invoke()
                }
            } else null
            try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                lastPosition = change.position
                val delta = change.position - down.position
                decision.update(delta.x, delta.y, event.changes.count { it.pressed }.coerceAtLeast(1), change.isConsumed && !owned)
                if (decision.cancelled || currentZoom()) break
                if (decision.moved) {
                    // Consume even short/disabled swipes: underlying pager must not bypass the configured threshold.
                    if (taps || if (vertical) kotlin.math.abs(delta.y) > kotlin.math.abs(delta.x) else kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y)) {
                        change.consume(); owned = true
                    }
                }
                if (!change.pressed) { up = change; break }
            }
            } finally { holdJob?.cancel() }
            if (decision.cancelled || currentZoom() || up.pressed) return@awaitEachGesture
            if (decision.isTap() && taps) {
                up.consume()
                com.example.ui.components.recordPaperTouch(down.position + windowOrigin, density)
                if (holdFired) Unit
                else if (up.uptimeMillis - down.uptimeMillis >= 1000 && currentHold != null) currentHold?.invoke()
                else if (currentMenu) currentAction(TouchAction.CONTROLS)
                else if (currentImage?.invoke(down.position) != true) currentAction(zone?.tap ?: TouchAction.NONE)
            } else if (decision.isSwipe(vertical) && !currentMenu && (!legacyPageCurl || zone?.distanceDp != null ||
                down.position.x < size.width / 2f && lastPosition.x > size.width / 2f ||
                down.position.x > size.width / 2f && lastPosition.x < size.width / 2f)) {
                com.example.ui.components.recordPaperTouch(down.position + windowOrigin, density)
                when (zone?.swipe) {
                    SwipeAction.PAGE -> {
                        val negative = (if (vertical) decision.dy else decision.dx) < 0
                        currentAction(if (negative.xor(reverse)) TouchAction.NEXT else TouchAction.PREVIOUS)
                    }
                    SwipeAction.CONTROLS -> currentAction(TouchAction.CONTROLS)
                    SwipeAction.SETTINGS -> currentAction(TouchAction.SETTINGS)
                    else -> Unit
                }
            }
        }
    }
}
