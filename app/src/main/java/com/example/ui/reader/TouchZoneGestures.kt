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
    nativePageDrag: Boolean = legacyPageCurl,
    nativeSwipeAvailable: Boolean = nativePageDrag,
    pageSwipeEnabled: Boolean = true,
    defaultTap: (Offset, Int) -> TouchAction = { _, _ -> TouchAction.NONE },
    prioritizeChildTap: Boolean = false,
    swipesWithChrome: Boolean = false,
): Modifier = composed {
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    val currentAction by rememberUpdatedState(onAction)
    val currentZoom by rememberUpdatedState(zoomed)
    val currentMenu by rememberUpdatedState(menuVisible)
    val currentImage by rememberUpdatedState(imageTap)
    val currentHold by rememberUpdatedState(onLongPress)
    val currentDefaultTap by rememberUpdatedState(defaultTap)
    val currentDistance by rememberUpdatedState(defaultDistance)
    val scope = rememberCoroutineScope()
    if (zones == null || !enabled) this else this.onGloballyPositioned { windowOrigin = it.positionInWindow() }
        .pointerInput(zones, vertical, reverse, taps, nativePageDrag, nativeSwipeAvailable, pageSwipeEnabled,
            prioritizeChildTap, swipesWithChrome) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (currentZoom() || down.isConsumed) return@awaitEachGesture
            val zone = zones.zoneAt(down.position, size)
            val defaultThreshold = currentDistance((if (vertical) size.height else size.width).toFloat())
            val threshold = zone?.distanceDp?.dp?.toPx() ?: defaultThreshold
            val swipe = zone?.swipe ?: SwipeAction.PAGE
            val handToNative = swipe == SwipeAction.PAGE &&
                (nativePageDrag || nativeSwipeAvailable && zone?.distanceDp == null)
            val decision = TouchGestureDecision(viewConfiguration.touchSlop, threshold)
            var up = down
            var owned = false
            val velocityTracker = androidx.compose.ui.input.pointer.util.VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
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
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.position - down.position
                decision.update(delta.x, delta.y, event.changes.count { it.pressed }.coerceAtLeast(1), change.isConsumed && !owned)
                if (decision.cancelled || currentZoom()) break
                val onPagingAxis = if (vertical) kotlin.math.abs(delta.y) > kotlin.math.abs(delta.x)
                    else kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y)
                if (decision.moved && !handToNative && (swipe != SwipeAction.PAGE || onPagingAxis)) {
                    change.consume()
                    owned = true
                }
                if (!change.pressed) {
                    if (prioritizeChildTap && decision.isTap()) {
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        if (final.changes.any { it.id == down.id && it.isConsumed }) return@awaitEachGesture
                    }
                    up = change
                    break
                }
            }
            } finally { holdJob?.cancel() }
            if (decision.cancelled || currentZoom() || up.pressed) return@awaitEachGesture
            if (decision.isTap() && taps) {
                up.consume()
                com.example.ui.components.recordPaperTouch(down.position + windowOrigin, density)
                if (holdFired) Unit
                else if (up.uptimeMillis - down.uptimeMillis >= 1000 && currentHold != null) currentHold?.invoke()
                else if (currentMenu) currentAction(TouchAction.CONTROLS)
                else if (currentImage?.invoke(down.position) != true) currentAction(zone?.tap ?: currentDefaultTap(down.position, size.width))
            } else if (decision.moved && (!currentMenu || swipesWithChrome) && !handToNative && when (swipe) {
                SwipeAction.PAGE -> if (!pageSwipeEnabled || !owned) false else {
                    val velocity = velocityTracker.calculateVelocity()
                    val delta = if (vertical) decision.dy else decision.dx
                    val axisVelocity = if (vertical) velocity.y else velocity.x
                    val cross = if (vertical) decision.dx else decision.dy
                    kotlin.math.abs(delta) > kotlin.math.abs(cross) && kotlin.math.abs(delta) >= threshold &&
                        delta * axisVelocity >= -850f * density * kotlin.math.abs(delta)
                }
                SwipeAction.CONTROLS, SwipeAction.SETTINGS -> decision.isSwipe(vertical)
                SwipeAction.NONE -> false
            }) {
                com.example.ui.components.recordPaperTouch(down.position + windowOrigin, density)
                when (swipe) {
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
