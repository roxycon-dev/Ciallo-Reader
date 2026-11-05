package com.example.library

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.drawAppProgressRing
import com.example.ui.components.rememberProgressPhase
import com.example.ui.components.PullOutIconButton
import com.example.ui.feedback.AppMotion
import com.example.ui.feedback.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin


@Composable
internal fun DownloadCenterEntry(model: LibraryViewModel, onClick: () -> Unit) {
    val novels by model.downloadStates.collectAsStateWithLifecycle()
    val comics by model.comicDownloadTasks.collectAsStateWithLifecycle()
    val state = remember(novels, comics) { downloadIndicatorState(novels, comics) }
    DownloadCenterButton(state, onClick)
}

/** Original Compose drawing inspired by continuous media-icon transitions.
 * This always opens the queue; pause/error are small status markers, never alternate actions.
 */
@Composable
internal fun DownloadCenterButton(state: DownloadIndicatorState, onClick: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val tracker = remember { DownloadCompletionTracker() }
    var completionEvent by remember { mutableIntStateOf(0) }
    var acknowledging by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (tracker.update(state)) completionEvent++
    }
    // A separate event clock cannot be cancelled/stranded by progress or history emissions.
    LaunchedEffect(completionEvent) {
        if (completionEvent > 0) {
            acknowledging = true
            delay(AppMotion.FEEDBACK_HOLD_MS)
            acknowledging = false
        }
    }
    LaunchedEffect(state.status) {
        if (state.status != DownloadIndicatorStatus.Idle) acknowledging = false
    }
    val showingCompletion = acknowledging && state.status == DownloadIndicatorStatus.Idle
    val running = state.status == DownloadIndicatorStatus.Running
    val morphSpec: FiniteAnimationSpec<Float> = if (reduceMotion) snap() else AppMotion.icon()
    val activity = animateFloatAsState(if (running || showingCompletion) 1f else 0f, morphSpec, label = "downloadActivity")
    val success = animateFloatAsState(if (showingCompletion) 1f else 0f, morphSpec, label = "downloadSuccess")
    val progress = remember { Animatable(state.progress ?: 0f) }
    var wasDeterminate by remember { mutableStateOf(false) }
    LaunchedEffect(state.progress, running, showingCompletion) {
        when {
            showingCompletion -> progress.animateTo(1f, morphSpec)
            running && state.progress != null -> {
                if (wasDeterminate) progress.animateTo(state.progress, morphSpec)
                else progress.snapTo(state.progress)
            }
        }
        wasDeterminate = running && state.progress != null
    }
    val phase = rememberProgressPhase(running && !reduceMotion)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val ringStroke = remember(density) { Stroke(with(density) { 1.8.dp.toPx() }, cap = StrokeCap.Round) }
    val glyphStroke = remember(density) { Stroke(with(density) { 2.1.dp.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    val chevron = remember { Path() }
    val tray = remember { Path() }

    Box(Modifier.size(48.dp)) {
    PullOutIconButton(onClick, modifier = Modifier.fillMaxSize().testTag("download_tasks_entry")
        .semantics(mergeDescendants = true) {
            contentDescription = "下载任务"
            stateDescription = if (showingCompletion) "下载完成" else state.description
            if (running) progressBarRangeInfo = state.progress?.let { ProgressBarRangeInfo(it, 0f..1f) }
                ?: ProgressBarRangeInfo.Indeterminate
        }) {
        Box(Modifier.size(32.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val unit = size.minDimension / 24f
                val center = Offset(size.width / 2f, size.height / 2f)
                val active = activity.value.coerceIn(0f, 1f)
                val checked = success.value.coerceIn(0f, 1f)
                val cycle = phase.value
                val ringRadius = 10.5f * unit
                if (active > 0f) {
                    drawAppProgressRing(if (running && state.progress == null) null else progress.value,
                        cycle, colors.primary.copy(alpha = active), colors.primary.copy(alpha = .13f * active),
                        center, ringRadius, ringStroke)
                }
                withTransform({
                    translate(center.x - 12f * unit, center.y - 12f * unit)
                    scale(1f - .24f * active, 1f - .24f * active, Offset(12f * unit, 12f * unit))
                    if (running) translate(top = sin(cycle * 2f * PI.toFloat()) * .6f * unit)
                }) {
                    fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
                    if (checked < 1f) {
                        drawLine(colors.primary.copy(alpha = 1f - checked), point(12f, 4f), point(12f, 15f), glyphStroke.width, StrokeCap.Round)
                    }
                    fun mix(a: Float, b: Float) = a + (b - a) * checked
                    chevron.reset()
                    chevron.moveTo(mix(7f, 6.5f) * unit, mix(10f, 12f) * unit)
                    chevron.lineTo(mix(12f, 10f) * unit, mix(15f, 15.5f) * unit)
                    chevron.lineTo(mix(17f, 17.5f) * unit, mix(10f, 8f) * unit)
                    drawPath(chevron, colors.primary, style = glyphStroke)
                    val trayAlpha = (1f - active) * (1f - checked)
                    if (trayAlpha > 0f) {
                        tray.reset()
                        tray.moveTo(5f * unit, 16.5f * unit)
                        tray.lineTo(5f * unit, 20f * unit)
                        tray.lineTo(19f * unit, 20f * unit)
                        tray.lineTo(19f * unit, 16.5f * unit)
                        drawPath(tray, colors.primary.copy(alpha = trayAlpha), style = glyphStroke)
                    }
                }
            }
        }
    }
            // Keep badges outside IconButton's circular state-layer clip, inside its 48dp hit area.
            Box(Modifier.align(Alignment.TopEnd).size(40.dp, 20.dp).clearAndSetSemantics {
                this[androidx.compose.ui.semantics.SemanticsProperties.TestTag] = "download_count_badge"
            }) {
            AnimatedContent(state.activeCount, Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd,
                transitionSpec = {
                    val motion = if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                    else (fadeIn(tween(180)) + scaleIn(tween(220), initialScale = .7f)) togetherWith
                        (fadeOut(tween(120)) + scaleOut(tween(160), targetScale = .7f))
                    motion.using(null)
                }, label = "downloadCount") { count ->
                if (count > 0) Badge(modifier = Modifier.wrapContentSize(Alignment.TopEnd), containerColor = colors.primary, contentColor = colors.onPrimary) {
                    Text(if (count > 99) "99+" else count.toString(), maxLines = 1, softWrap = false)
                }
            }
            }
            AnimatedVisibility(!running && !showingCompletion && (state.failedCount > 0 || state.pausedCount > 0),
                Modifier.align(Alignment.TopEnd).clearAndSetSemantics { },
                enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(160)) + scaleIn(tween(220), initialScale = .7f),
                exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(120)) + scaleOut(tween(160), targetScale = .7f)) {
                AttentionMarker(state.failedCount > 0, reduceMotion)
            }
    }
}

@Composable
private fun AttentionMarker(failed: Boolean, reduceMotion: Boolean) {
    val shape = animateFloatAsState(if (failed) 1f else 0f,
        if (reduceMotion) snap() else tween(200, easing = AppMotion.easeOutCubic), label = "downloadAttention")
    val colors = MaterialTheme.colorScheme
    val tint = animateColorAsState(if (failed) colors.error else colors.secondary,
        if (reduceMotion) snap() else tween(200), label = "downloadAttentionColor")
    Canvas(Modifier.size(14.dp)) {
        val unit = size.width / 16f
        val fraction = shape.value
        fun mix(a: Float, b: Float) = a + (b - a) * fraction
        drawCircle(colors.surface, size.width / 2f)
        drawLine(tint.value, Offset(mix(5f, 8f) * unit, 4f * unit),
            Offset(mix(5f, 8f) * unit, mix(12f, 8.8f) * unit), 1.8f * unit, StrokeCap.Round)
        drawLine(tint.value, Offset(mix(10f, 8f) * unit, mix(4f, 12.2f) * unit),
            Offset(mix(10f, 8f) * unit, mix(12f, 12.2f) * unit), 1.8f * unit, StrokeCap.Round)
    }
}
