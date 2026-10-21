package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect

/**
 * Deliberate stability fallback for the paper effect. One normal Compose layer,
 * no bitmap readback, borrowed RenderNodes, extra renderer or source visibility.
 * Transform properties do not remeasure/recompose text during motion. Once flat,
 * all properties are exactly identity and glass draws through its usual pipeline.
 */
@Composable
internal fun StablePaperSurface(life: PaperLifecycle, content: @Composable () -> Unit) {
    val view = LocalView.current
    val geometry = remember { PaperSurfaceGeometry() }
    Box(Modifier.onGloballyPositioned {
        geometry.bounds = it.boundsInWindow().translate(view.paperWindowOffset())
    }.graphicsLayer {
        val p = life.progress.coerceIn(0f, 1f)
        val bounds = geometry.bounds
        val source = life.origin?.bounds
        val pivotX = if (source != null && bounds.width > 0f)
            ((source.center.x - bounds.left) / bounds.width).coerceIn(0f, 1f) else .5f
        val pivotY = if (source != null && bounds.height > 0f)
            ((source.center.y - bounds.top) / bounds.height).coerceIn(0f, 1f) else .5f
        transformOrigin = TransformOrigin(pivotX, pivotY)
        alpha = p
        scaleX = PullOutConfig.SURFACE_SCALE + (1f - PullOutConfig.SURFACE_SCALE) * p
        scaleY = scaleX
        // Only a small approach to the source; never shrink a window into a button.
        translationY = (1f - p) * PullOutConfig.SURFACE_TRAVEL_DP * density * (pivotY * 2f - 1f)
    }.pointerInput(life) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (life.inputBlocked) event.changes.forEach { it.consume() }
            }
        }
    }) { content() }
}

private class PaperSurfaceGeometry { var bounds = Rect.Zero }
