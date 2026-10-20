@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.ui.components

import com.example.ui.design.DesignTokens
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch

/** Shared exit lifecycle. The action runs only once the paper has returned to its source. */
@Composable
internal fun rememberPaperClose(state: MutableTransitionState<Boolean>, dismiss: () -> Unit): ((() -> Unit) -> Unit) {
    val latest by rememberUpdatedState(dismiss)
    val life = rememberPaperLifecycle(state)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var executing by remember { mutableStateOf(false) }
    var wasOpen by remember { mutableStateOf(state.targetState) }
    SideEffect { if (state.targetState) wasOpen = true }
    LaunchedEffect(state.isIdle, state.currentState, state.targetState, life.closed, life.registered) {
        if (state.targetState) pending = null
        val complete = if (life.registered) life.closed else state.isIdle && !state.currentState
        if (wasOpen && !state.targetState && complete) {
            wasOpen = false
            val action = pending; pending = null
            // Run follow-up only after the old window has actually left composition. Otherwise
            // Compose batches null -> next into one update and reuses the old closed remember state.
            var followedUp = false
            val followUp: () -> Unit = {
                if (!followedUp) {
                    followedUp = true; executing = true
                    try { action?.invoke() } finally { executing = false }
                }
            }
            val awaitWindowDisposal = life.hostAttached
            if (awaitWindowDisposal) life.afterDismiss = followUp
            executing = true
            try { latest() } finally { executing = false }
            if (!awaitWindowDisposal) followUp()
        }
    }
    return { action -> if (executing) action() else if (state.targetState) { pending = action; state.targetState = false } }

}

@Composable
private fun WindowPaperFinalizer(state: MutableTransitionState<Boolean>?) {
    if (state == null) return
    val life = rememberPaperLifecycle(state)
    DisposableEffect(life) {
        life.hostAttached = true
        onDispose {
            life.hostAttached = false
            val action = life.afterDismiss; life.afterDismiss = null
            action?.invoke()
        }
    }
}

/** Native window host, used by every app dialog. Existing composed surfaces may own their visibility. */
@Composable
fun PullOutDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    animateContent: Boolean = true,
    lifecycleState: MutableTransitionState<Boolean>? = null,
    content: @Composable () -> Unit,
) {
    val inheritedOrigin = LocalPaperOrigin.current
    val origin = remember { PullOutSources.claim() ?: inheritedOrigin?.returnSource() }
    if (!animateContent) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
            DisablePlatformWindowMotion(); WindowPaperFinalizer(lifecycleState)
            CompositionLocalProvider(LocalPaperOrigin provides origin, LocalPaperOwner provides null) { content() }
        }
        return
    }
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    val close = rememberPaperClose(state, onDismissRequest)
    androidx.compose.ui.window.Dialog(onDismissRequest = { close {} }, properties = DialogProperties(
        dismissOnBackPress = properties.dismissOnBackPress, dismissOnClickOutside = false,
        securePolicy = properties.securePolicy, usePlatformDefaultWidth = false,
        decorFitsSystemWindows = properties.decorFitsSystemWindows)) {
        DisablePlatformWindowMotion(); WindowPaperFinalizer(state)
        CompositionLocalProvider(LocalOverlayClose provides close, LocalPaperOrigin provides origin, LocalPaperOwner provides null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val life = rememberPaperLifecycle(state)
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = life.progress.coerceIn(0f, 1f) }
                    .background(Color.Black.copy(alpha = PullOutConfig.SCRIM))
                    .clickable { if (properties.dismissOnClickOutside) close {} })
                PullOutVisibility(state, if (properties.usePlatformDefaultWidth) Modifier.widthIn(max = 560.dp).fillMaxWidth(.9f) else Modifier) {
                    Box { content() }
                }
            }
        }
    }
}

@Composable
private fun DisablePlatformWindowMotion() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.setWindowAnimations(0)
            window.setDimAmount(0f)
        }
    }
}

/** A real modal host, without a second platform slide competing with the paper mesh. */
@Composable
fun PullOutBottomSheet(
    onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = RoundedCornerShape(topStart = DesignTokens.RadiusOverlay, topEnd = DesignTokens.RadiusOverlay),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = DesignTokens.Zero,
    scrimColor: Color = Color.Black.copy(alpha = PullOutConfig.SCRIM),
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    val close = rememberPaperClose(state, onDismissRequest)
    var dragTarget by remember { mutableFloatStateOf(1f) }
    var dragged by remember { mutableFloatStateOf(0f) }
    PullOutDialog(onDismissRequest = { close {} }, animateContent = false, lifecycleState = state,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalOverlayClose provides close) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val life = rememberPaperLifecycle(state)
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = life.progress.coerceIn(0f, 1f) }.background(scrimColor)
                    .clickable { close {} })
                CompositionLocalProvider(LocalPaperVisibleTarget provides dragTarget) {
                PullOutVisibility(state, Modifier.align(Alignment.BottomCenter)) {
                    Surface(modifier.widthIn(max = 840.dp).fillMaxWidth().heightIn(max = maxHeight * .94f),
                        shape = shape, color = containerColor, contentColor = contentColor, tonalElevation = tonalElevation) {
                        Column(Modifier.navigationBarsPadding().imePadding()) {
                            Row(Modifier.fillMaxWidth().pointerInput(Unit) {
                                detectVerticalDragGestures(onDragStart = { dragged = 0f },
                                    onDragCancel = { dragTarget = 1f },
                                    onDragEnd = { if (dragged > PullOutConfig.DISMISS_DISTANCE.dp.toPx()) close {} else dragTarget = 1f }) { change, amount ->
                                    change.consume(); dragged = (dragged + amount).coerceAtLeast(0f)
                                    dragTarget = 1f - (dragged / PullOutConfig.DRAG_TRAVEL.dp.toPx()).coerceIn(0f, .65f)
                                }
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { dragHandle?.invoke() }
                                IconButton(onClick = { close {} }) { Icon(Icons.Default.Close, "关闭面板") }
                            }
                            content()
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
fun PullOutAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(DesignTokens.RadiusOverlay),
    containerColor: Color = MaterialTheme.colorScheme.surface,
) {
    AcrylicDialog(onDismissRequest, modifier, shape,
        title = { icon?.invoke(); title?.invoke() }, text = text,
        confirmButton = { val close = LocalOverlayClose.current; CompositionLocalProvider(LocalPaperButtonAction provides close) { confirmButton() } },
        dismissButton = dismissButton?.let { slot -> { val close = LocalOverlayClose.current; CompositionLocalProvider(LocalPaperButtonAction provides close) { slot() } } })
}

/** Menus use the same spring and mesh, with an explicit focusable modal boundary. */
@Composable
fun PullOutDropdownMenu(
    expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    var source by remember { mutableStateOf(PullOutSources.latest) }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (expanded) { source = PullOutSources.latest; dismissed = false; state.targetState = true } else state.targetState = false }
    val close = rememberPaperClose(state) { if (expanded && !dismissed) { dismissed = true; onDismissRequest() } }
    val retainedLife = rememberPaperLifecycle(state)
    if (expanded || retainedLife.retained || state.currentState || state.targetState) {
        PullOutDialog(onDismissRequest = { close {} }, animateContent = false, lifecycleState = state,
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
            val menuView = LocalView.current
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val margin = with(density) { DesignTokens.SpaceMd.roundToPx() }
                val screenWidth = constraints.maxWidth
                val screenHeight = constraints.maxHeight
                val bounds = source?.bounds?.translate(-menuView.paperWindowOffset())
                val life = rememberPaperLifecycle(state)
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = life.progress.coerceIn(0f, 1f) }
                    .background(Color.Black.copy(alpha = PullOutConfig.SCRIM)).clickable { close {} })
                PullOutVisibility(state, Modifier.offset {
                    val x = ((bounds?.right?.toInt() ?: screenWidth / 2) - menuSize.width + with(density) { offset.x.roundToPx() })
                        .coerceIn(margin, (screenWidth - menuSize.width - margin).coerceAtLeast(margin))
                    val below = (bounds?.bottom?.toInt() ?: screenHeight / 2) + margin
                    val y = (if (below + menuSize.height <= screenHeight - margin) below else (bounds?.top?.toInt() ?: screenHeight / 2) - menuSize.height - margin)
                        .coerceIn(margin, (screenHeight - menuSize.height - margin).coerceAtLeast(margin))
                    IntOffset(x, y)
                }.onSizeChanged { menuSize = it }) {
                    Column(modifier.widthIn(min = 180.dp, max = 360.dp).clip(RoundedCornerShape(DesignTokens.SpaceXl))
                        .background(containerColor).verticalScroll(rememberScrollState()).padding(vertical = DesignTokens.SpaceSm)) {
                        CompositionLocalProvider(LocalPaperButtonAction provides close) { content() }
                    }
                }
            }
        }
    }
}

/** Only action slots opt into closing; toggles and editor buttons retain their normal behavior. */
internal val LocalPaperButtonAction = compositionLocalOf<((() -> Unit) -> Unit)?> { null }
internal val LocalPaperVisibility = compositionLocalOf<MutableTransitionState<Boolean>?> { null }
internal val LocalPaperVisibleTarget = compositionLocalOf { 1f }

/** Lifecycle for overlays drawn inside the screen rather than in a separate window. */
@Composable
fun PullOutInlineOverlay(onDismiss: () -> Unit, content: @Composable (dismiss: () -> Unit) -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    val close = rememberPaperClose(state, onDismiss)
    PullOutDialog(onDismissRequest = { close {} }, animateContent = false, lifecycleState = state,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalOverlayClose provides close, LocalPaperVisibility provides state) {
            content { close {} }
        }
    }
}

@Composable
fun paperClick(action: () -> Unit): () -> Unit = paperClick(action, null)

@Composable
internal fun paperClick(action: () -> Unit, source: PullOutSource?): () -> Unit {
    val intercept = LocalPaperButtonAction.current
    return {
        source?.let { PullOutSources.activate(it) }
        if (intercept != null) {
            val trigger = source ?: PullOutSources.latest
            val returnOrigin = trigger?.parent?.returnSource() ?: trigger?.returnSource()
            intercept { returnOrigin?.let { PullOutSources.activate(it) }; action() }
        } else action()
    }
}

@Composable
fun paperDismiss(action: () -> Unit): () -> Unit {
    val close = LocalOverlayClose.current
    return { close(action) }
}
