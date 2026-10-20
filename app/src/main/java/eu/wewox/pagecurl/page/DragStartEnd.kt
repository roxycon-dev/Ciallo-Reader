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
internal fun Modifier.dragStartEnd(
    dragInteraction: PageCurlConfig.StartEndDragInteraction,
    state: PageCurlState.InternalState,
    enabledForward: Boolean,
    enabledBackward: Boolean,
    scope: CoroutineScope,
    startAllowed: ((androidx.compose.ui.geometry.Offset, androidx.compose.ui.unit.IntSize) -> Boolean)? = null,
    release: ((androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset, androidx.compose.ui.unit.IntSize, Boolean) -> Boolean)? = null,
    onChange: (Int) -> Unit,
): Modifier = this.composed {
    val isEnabledForward = rememberUpdatedState(enabledForward)
    val isEnabledBackward = rememberUpdatedState(enabledBackward)
    val allowed = rememberUpdatedState(startAllowed)
    val releasePolicy = rememberUpdatedState(release)

    pointerInput(state) {
        val forwardStartRect by lazy { dragInteraction.forward.start.multiply(size) }
        val forwardEndRect by lazy { dragInteraction.forward.end.multiply(size) }
        val backwardStartRect by lazy { dragInteraction.backward.start.multiply(size) }
        val backwardEndRect by lazy { dragInteraction.backward.end.multiply(size) }

        val forwardConfig = DragConfig(
            edge = state.forward,
            start = state.rightEdge,
            end = state.leftEdge,
            isEnabled = { isEnabledForward.value },
            isDragSucceed = { _, end -> forwardEndRect.contains(end) },
            onChange = { onChange(+1) },
            release = { start, end, velocity, native -> releasePolicy.value?.invoke(start, end, velocity, size, native) ?: native }
        )
        val backwardConfig = DragConfig(
            edge = state.backward,
            start = state.leftEdge,
            end = state.rightEdge,
            isEnabled = { isEnabledBackward.value },
            isDragSucceed = { _, end -> backwardEndRect.contains(end) },
            onChange = { onChange(-1) },
            release = { start, end, velocity, native -> releasePolicy.value?.invoke(start, end, velocity, size, native) ?: native }
        )

        detectCurlGestures(
            scope = scope,
            newEdgeCreator = when (dragInteraction.pointerBehavior) {
                PointerBehavior.Default -> NewEdgeCreator.Default()
                PointerBehavior.PageEdge -> NewEdgeCreator.PageEdge()
            },
            getConfig = getConfig@ { start, _ ->
                if (allowed.value?.invoke(start, size) == false) return@getConfig null
                val config = if (forwardStartRect.contains(start)) {
                    forwardConfig
                } else if (backwardStartRect.contains(start)) {
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
