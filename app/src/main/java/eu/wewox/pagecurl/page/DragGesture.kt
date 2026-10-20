package eu.wewox.pagecurl.page

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.config.PageCurlConfig.DragInteraction.PointerBehavior
import eu.wewox.pagecurl.utils.multiply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@ExperimentalPageCurlApi
internal fun Modifier.dragGesture(
    dragInteraction: PageCurlConfig.GestureDragInteraction,
    state: PageCurlState.InternalState,
    enabledForward: Boolean,
    enabledBackward: Boolean,
    scope: CoroutineScope,
    startAllowed: ((androidx.compose.ui.geometry.Offset, androidx.compose.ui.unit.IntSize) -> Boolean)? = null,
    release: ((androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset, androidx.compose.ui.unit.IntSize, Boolean) -> Boolean)? = null,
    onChange: (Int) -> Unit
): Modifier = this.composed {
    val isEnabledForward = rememberUpdatedState(enabledForward)
    val isEnabledBackward = rememberUpdatedState(enabledBackward)
    val allowed = rememberUpdatedState(startAllowed)
    val releasePolicy = rememberUpdatedState(release)

    pointerInput(state) {
        val forwardTargetRect by lazy { dragInteraction.forward.target.multiply(size) }
        val backwardTargetRect by lazy { dragInteraction.backward.target.multiply(size) }

        val forwardConfig = DragConfig(
            edge = state.forward,
            start = state.rightEdge,
            end = state.leftEdge,
            isEnabled = { isEnabledForward.value },
            isDragSucceed = { start, end -> end.x < start.x },
            onChange = { onChange(+1) },
            release = { start, end, velocity, native -> releasePolicy.value?.invoke(start, end, velocity, size, native) ?: native }
        )
        val backwardConfig = DragConfig(
            edge = state.backward,
            start = state.leftEdge,
            end = state.rightEdge,
            isEnabled = { isEnabledBackward.value },
            isDragSucceed = { start, end -> end.x > start.x },
            onChange = { onChange(-1) },
            release = { start, end, velocity, native -> releasePolicy.value?.invoke(start, end, velocity, size, native) ?: native }
        )

        detectCurlGestures(
            scope = scope,
            newEdgeCreator = when (dragInteraction.pointerBehavior) {
                PointerBehavior.Default -> NewEdgeCreator.Default()
                PointerBehavior.PageEdge -> NewEdgeCreator.PageEdge()
            },
            getConfig = getConfig@ { start, end ->
                if (allowed.value?.invoke(start, size) == false) return@getConfig null
                val config = if (forwardTargetRect.contains(start) && end.x < start.x) {
                    forwardConfig
                } else if (backwardTargetRect.contains(start) && end.x > start.x) {
                    backwardConfig
                } else {
                    null
                }

                if (config != null && !config.isEnabled()) return@getConfig null
                if (config != null) {
                    scope.launch {
                        state.animateJob?.cancel()
                        state.reset()
                    }
                }

                config
            },
        )
    }
}
