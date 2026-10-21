@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.ui.components

import androidx.activity.compose.BackHandler
import com.kashif_e.backdrop.backdrops.layerBackdrop
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.dialog
import androidx.navigation.compose.composable
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
import androidx.compose.ui.graphics.luminance
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
        if (wasOpen && !life.handingOff && !state.targetState && complete) {
            wasOpen = false
            val action = pending; pending = null
            // Run follow-up only after the old window has actually left composition. Otherwise
            // Compose batches null -> next into one update and reuses the old closed remember state.
            var followedUp = false
            val followUp: () -> Unit = {
                if (!followedUp) {
                    followedUp = true; executing = true
                    try {
                        // The exit can outlast the click-source TTL. A chained panel
                        // starts at the living return trigger after this window retires.
                        if (action != null) life.origin?.returnSource()?.let(PullOutSources::activate)
                        action?.invoke()
                    } finally { executing = false }
                }
            }
            val awaitWindowDisposal = life.hostAttached
            if (awaitWindowDisposal) life.afterDismiss = followUp
            executing = true
            try { latest() } finally { executing = false }
            if (!awaitWindowDisposal) followUp()
        }
    }
    return { action -> if (executing) action() else if (state.targetState && !life.handingOff) { pending = action.takeUnless { it === latest }; state.targetState = false } }

}

/** Replacing a chooser is a forward action, not a dismissal followed by a new opening.
 * Retire only its window, then start the destination at the actual clicked button.
 * A frozen screen rect survives disposal; closing the destination uses the living parent.
 */
@Composable
internal fun rememberPaperHandoff(state: MutableTransitionState<Boolean>, dismiss: () -> Unit): ((() -> Unit) -> Unit) {
    val life = rememberPaperLifecycle(state)
    val latest by rememberUpdatedState(dismiss)
    return { action ->
        if (state.targetState && !life.handingOff) {
            life.handingOff = true; life.inputBlocked = true
            val source = PullOutSources.claim()?.freezeForHandoff() ?: life.origin?.freezeForHandoff()
            var done = false
            val forward: () -> Unit = {
                if (!done) {
                    done = true
                    source?.let(PullOutSources::activate)
                    action()
                }
            }
            val waitForWindow = life.hostAttached
            if (waitForWindow) life.afterDismiss = forward
            // Controlled menus stay composed after expanded=false until retention ends.
            // End that retention immediately for a forward handoff; never run an exit warp.
            state.targetState = false; life.retained = false; life.closed = true
            latest()
            if (!waitForWindow) forward()
        }
    }
}

/** Use for navigation/replacement; ordinary confirmation and dismissal keep their exit. */
internal val LocalPaperHandoff = compositionLocalOf<((() -> Unit) -> Unit)?> { null }
@Composable
fun paperOpen(action: () -> Unit): () -> Unit {
    val forward = LocalPaperHandoff.current
    return PaperOpenAction { if (forward == null) action() else forward(action) }
}
private class PaperOpenAction(private val action: () -> Unit) : () -> Unit {
    override fun invoke() = action()
}

/** Full-page presentations retain the caller behind them. Only the destination is deformed;
 * Navigation never animates the outgoing screen or its bottom tabs for this operation.
 */
fun NavController.openPaperPage(route: String) {
    navigate(route) { launchSingleTop = true }
}

/** Atomic forward replacement; do not start an asynchronous close and navigate immediately. */
fun NavController.replacePaperPage(route: String) {
    PullOutSources.claim()?.freezeForHandoff()?.let(PullOutSources::activate)
    val outgoing = currentDestination?.id
    navigate(route) {
        if (outgoing != null) popUpTo(outgoing) { inclusive = true }
        launchSingleTop = true
    }
}

fun NavGraphBuilder.pullOutPage(route: String, onDismiss: () -> Unit,
    content: @Composable (close: () -> Unit) -> Unit) {
    dialog(route, dialogProperties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnClickOutside = false, dismissOnBackPress = false, decorFitsSystemWindows = false)) {
        PullOutPageContent(onDismiss, content)
    }
}

/** Activity-window page host: readers keep their brightness, inset and native-surface ownership.
 * Saveable presentation state prevents a second opening when returning to an existing entry.
 * All back buttons and system back share the same interruptible paper close.
 */
private val paperPageOrigins = java.util.WeakHashMap<androidx.navigation.NavBackStackEntry, PullOutSource>()
private val paperPageClosers = java.util.WeakHashMap<androidx.navigation.NavBackStackEntry, () -> Unit>()
fun NavController.dismissPaperPage() {
    val entry = currentBackStackEntry
    val close = paperPageClosers[entry]
    if (close != null) close() else popBackStack()
}
fun NavGraphBuilder.paperComposable(
    route: String, navController: NavController,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable androidx.compose.animation.AnimatedContentScope.(androidx.navigation.NavBackStackEntry) -> Unit,
) {
    composable(route, arguments = arguments,
        enterTransition = { androidx.compose.animation.EnterTransition.None },
        exitTransition = { androidx.compose.animation.ExitTransition.None },
        popEnterTransition = { androidx.compose.animation.EnterTransition.None },
        popExitTransition = { androidx.compose.animation.ExitTransition.None }) { entry ->
        val navScope = this
        var presented by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        var savedOrigin by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<FloatArray?>(null) }
        val origin = remember(entry) {
            paperPageOrigins.getOrPut(entry) {
                if (presented && savedOrigin != null) PullOutSource().apply {
                    val b = savedOrigin!!
                    bounds = androidx.compose.ui.geometry.Rect(b[0], b[1], b[2], b[3]); virtual = b[4] == 1f
                } else PullOutSources.claim() ?: PullOutSource().apply { virtual = true }
            }.also { source ->
                val saved = if (source.frozenOpening) source.returnSource() else source
                savedOrigin = floatArrayOf(saved.bounds.left, saved.bounds.top, saved.bounds.right, saved.bounds.bottom, if (saved.virtual) 1f else 0f)
            }
        }
        val visibility = remember { MutableTransitionState(presented).apply { targetState = true } }
        val close = rememberPaperClose(visibility) {
            if (navController.currentBackStackEntry === entry) navController.popBackStack()
        }
        val latestClose by rememberUpdatedState(close)
        DisposableEffect(entry) {
            paperPageClosers[entry] = { latestClose {} }
            onDispose { paperPageClosers.remove(entry) }
        }
        LaunchedEffect(entry) { presented = true }
        BackHandler { close {} }
        CompositionLocalProvider(LocalPaperOrigin provides origin, LocalPaperOwner provides null,
            LocalPaperFullSurface provides true, LocalPaperHandoff provides null) {
            PullOutVisibility(visibility, Modifier.fillMaxSize()) {
                PaperPageBackground { navScope.content(entry) }
            }
        }
    }
}

@Composable
internal fun PullOutPageContent(onDismiss: () -> Unit, content: @Composable (close: () -> Unit) -> Unit) {
    val origin = remember { PullOutSources.claim() }
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    val close = rememberPaperClose(state, onDismiss)
    val forward = rememberPaperHandoff(state, onDismiss)
    DisablePlatformWindowMotion(fullPage = true, lightSystemBars = MaterialTheme.colorScheme.background.luminance() > .5f); WindowPaperFinalizer(state)
    BackHandler { close {} }
    CompositionLocalProvider(LocalOverlayClose provides close, LocalPaperHandoff provides forward,
        LocalPaperOrigin provides origin, LocalPaperOwner provides null, LocalPaperFullSurface provides true) {
        DialogLiquidGlass {
            val life = rememberPaperLifecycle(state)
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = life.progress.coerceIn(0f, 1f) }
                    .background(Color.Black.copy(alpha = PullOutConfig.SCRIM)).clickable { /* modal boundary */ })
                PullOutVisibility(state, Modifier.fillMaxSize()) {
                    PaperPageBackground { content { close {} } }
                }
            }
        }
    }
}

/** A page is opaque over its caller, while retaining the app's wallpaper and glass sampling.
 * Only the background is recorded; lenses/content are siblings, avoiding a render-node cycle.
 */
@Composable
private fun PaperPageBackground(content: @Composable () -> Unit) {
    val config by com.example.ui.AppBackgroundController.config.collectAsState()
    val backdrop = com.kashif_e.backdrop.backdrops.rememberLayerBackdrop()
    val quality = LocalRenderQuality.current
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(
            if (quality.realtimeGlass) Modifier.layerBackdrop(backdrop) else Modifier
        ).background(MaterialTheme.colorScheme.background)) {
            if (config.mode == 1 && !config.uri.isNullOrBlank() && !quality.minimal) {
                androidx.compose.foundation.Image(coil.compose.rememberAsyncImagePainter(config.uri), null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (config.dim > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = config.dim / 100f)))
            }
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop.takeIf { quality.realtimeGlass }) { content() }
    }
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
            DisablePlatformWindowMotion(fillWindow = !properties.usePlatformDefaultWidth); WindowPaperFinalizer(lifecycleState)
            CompositionLocalProvider(LocalPaperOrigin provides origin, LocalPaperOwner provides null,
                LocalPaperHandoff provides null, LocalPaperFullSurface provides false) {
                DialogLiquidGlass(fillMaxSize = false) { content() }
            }
        }
        return
    }
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    val close = rememberPaperClose(state, onDismissRequest)
    val forward = rememberPaperHandoff(state, onDismissRequest)
    androidx.compose.ui.window.Dialog(onDismissRequest = { close {} }, properties = DialogProperties(
        dismissOnBackPress = properties.dismissOnBackPress, dismissOnClickOutside = false,
        securePolicy = properties.securePolicy, usePlatformDefaultWidth = false,
        decorFitsSystemWindows = properties.decorFitsSystemWindows)) {
        DisablePlatformWindowMotion(); WindowPaperFinalizer(state)
        CompositionLocalProvider(LocalOverlayClose provides close, LocalPaperHandoff provides forward, LocalPaperOrigin provides origin, LocalPaperOwner provides null, LocalPaperFullSurface provides false) {
            DialogLiquidGlass(fillMaxSize = false) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val life = rememberPaperLifecycle(state)
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = life.progress.coerceIn(0f, 1f) }
                    .background(Color.Black.copy(alpha = PullOutConfig.SCRIM))
                    .clickable { if (properties.dismissOnClickOutside) close {} })
                Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
                    PullOutVisibility(state, if (properties.usePlatformDefaultWidth) Modifier.widthIn(max = 560.dp).fillMaxWidth(.9f) else Modifier) {
                        Box { content() }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun DisablePlatformWindowMotion(fullPage: Boolean = false, lightSystemBars: Boolean = false, fillWindow: Boolean = true) {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.setWindowAnimations(0)
            window.setDimAmount(0f)
            // A full-height floating window must also use the full physical frame.
            // Otherwise Android can place its 100%-height content below the status bar,
            // pushing footer buttons under the navigation area/off the display.
            if (fullPage || fillWindow) {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                window.attributes = window.attributes.apply {
                    if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                window.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT)
            }
            if (fullPage) {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightSystemBars
                    isAppearanceLightNavigationBars = lightSystemBars
                }
            }
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
    val forward = rememberPaperHandoff(state, onDismissRequest)
    var dragTarget by remember { mutableFloatStateOf(1f) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val headerDrag = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(onDragStart = { dragged = 0f },
            onDragCancel = { dragTarget = 1f },
            onDragEnd = { if (dragged > PullOutConfig.DISMISS_DISTANCE.dp.toPx()) close {} else dragTarget = 1f }) { change, amount ->
            change.consume(); dragged = (dragged + amount).coerceAtLeast(0f)
            dragTarget = 1f - (dragged / PullOutConfig.DRAG_TRAVEL.dp.toPx()).coerceIn(0f, .65f)
        }
    }
    PullOutDialog(onDismissRequest = { close {} }, animateContent = false, lifecycleState = state,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalOverlayClose provides close, LocalPaperHandoff provides forward, LocalPaperSheetDrag provides headerDrag) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val life = rememberPaperLifecycle(state)
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = life.progress.coerceIn(0f, 1f) }.background(scrimColor)
                    .clickable { close {} })
                CompositionLocalProvider(LocalPaperVisibleTarget provides dragTarget) {
                PullOutVisibility(state, Modifier.align(Alignment.BottomCenter)) {
                    Surface(modifier.widthIn(max = 840.dp).fillMaxWidth().heightIn(max = maxHeight * .94f),
                        shape = shape, color = containerColor, contentColor = contentColor, tonalElevation = tonalElevation) {
                        Column(Modifier.navigationBarsPadding().imePadding()) {
                            if (dragHandle != null) Row(Modifier.fillMaxWidth().then(headerDrag), verticalAlignment = Alignment.CenterVertically) {
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
    val forward = rememberPaperHandoff(state, onDismissRequest)
    val retainedLife = rememberPaperLifecycle(state)
    if (expanded || retainedLife.retained || state.currentState || state.targetState) {
        PullOutDialog(onDismissRequest = { close {} }, animateContent = false, lifecycleState = state,
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
            val menuView = LocalView.current
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val margin = with(density) { DesignTokens.SpaceMd.roundToPx() }
                val screenWidth = constraints.maxWidth
                val screenHeight = constraints.maxHeight - maxOf(WindowInsets.navigationBars.getBottom(density), WindowInsets.ime.getBottom(density))
                val topMargin = maxOf(margin, WindowInsets.statusBars.getTop(density))
                val bounds = source?.bounds?.translate(-menuView.paperWindowOffset())
                val life = rememberPaperLifecycle(state)
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = life.progress.coerceIn(0f, 1f) }
                    .background(Color.Black.copy(alpha = PullOutConfig.SCRIM)).clickable { close {} })
                PullOutVisibility(state, Modifier.offset {
                    val x = ((bounds?.right?.toInt() ?: screenWidth / 2) - menuSize.width + with(density) { offset.x.roundToPx() })
                        .coerceIn(margin, (screenWidth - menuSize.width - margin).coerceAtLeast(margin))
                    val below = (bounds?.bottom?.toInt() ?: screenHeight / 2) + margin
                    val y = (if (below + menuSize.height <= screenHeight - margin) below else (bounds?.top?.toInt() ?: screenHeight / 2) - menuSize.height - margin)
                        .coerceIn(topMargin, (screenHeight - menuSize.height - margin).coerceAtLeast(topMargin))
                    IntOffset(x, y)
                }.onSizeChanged { menuSize = it }) {
                    Column(modifier.widthIn(min = 180.dp, max = 360.dp).clip(RoundedCornerShape(DesignTokens.SpaceXl))
                        .background(containerColor).verticalScroll(rememberScrollState()).padding(vertical = DesignTokens.SpaceSm)) {
                        CompositionLocalProvider(LocalPaperButtonAction provides close, LocalPaperHandoff provides forward) { content() }
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
private val LocalPaperSheetDrag = compositionLocalOf<Modifier> { Modifier }

/** Custom headers keep swipe-to-dismiss without adding a second title/close row. */
@Composable
fun Modifier.paperSheetDragHandle(): Modifier = then(LocalPaperSheetDrag.current)

/** Lifecycle for overlays drawn inside the screen rather than in a separate window. */
@Composable
fun PullOutInlineOverlay(onDismiss: () -> Unit, content: @Composable (dismiss: () -> Unit) -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    val close = rememberPaperClose(state, onDismiss)
    val forward = rememberPaperHandoff(state, onDismiss)
    PullOutDialog(onDismissRequest = { close {} }, animateContent = false, lifecycleState = state,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalOverlayClose provides close, LocalPaperHandoff provides forward, LocalPaperVisibility provides state) {
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
        if (action is PaperOpenAction) action()
        else if (intercept != null) {
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
