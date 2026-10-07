package com.example.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Own blank panel taps without merging child semantics or intercepting their drags. */
internal fun Modifier.overlayTouchShield(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            event.changes.filter { !it.pressed && !it.isConsumed }.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}
