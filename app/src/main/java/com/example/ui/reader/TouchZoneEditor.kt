package com.example.ui.reader

import com.example.ui.design.DesignTokens
import com.example.ui.components.PullOutTextButton
import com.example.ui.components.PullOutFilledTonalButton
import com.example.ui.components.PullOutIconButton
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.LiquidSlider
import com.example.ui.components.acrylicPanel
import kotlin.math.*

import androidx.activity.compose.BackHandler
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextAlign

/** In-place editing over the unchanged reader. Geometry always uses the actual engine viewport. */
@Composable
fun TouchZoneEditor(
    zones: List<TouchZone>?, defaults: List<TouchZone>, defaultDistanceDp: Float,
    comic: Boolean = false, vertical: Boolean = false,
    onSave: (List<TouchZone>?) -> Unit, onDismiss: () -> Unit,
    resetZones: List<TouchZone> = defaults, onRestoreDefault: () -> Unit = {},
    viewportInWindow: Rect? = null,
    onPreview: (List<TouchZone>?, Boolean) -> Unit = { _, _ -> },
    reverse: Boolean = false,
) {
    var draft by remember { mutableStateOf(zones) }
    var resetRequested by remember { mutableStateOf(false) }
    val visible = draft ?: if (resetRequested) resetZones else defaults
    var selectedId by remember { mutableStateOf(visible.firstOrNull()?.id) }
    val selected = visible.firstOrNull { it.id == selectedId }
    val range = selected?.distanceDp ?: defaultDistanceDp
    var calibrating by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var sample by remember { mutableFloatStateOf(0f) }
    var controlsExpanded by remember { mutableStateOf(false) }
    var toolsAtTop by remember { mutableStateOf(false) }
    var trying by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val pixelDensity = density.density
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val hapticsEnabled = com.example.ui.feedback.LocalHapticsEnabled.current
    fun edit(zone: TouchZone) { draft = visible.map { if (it.id == zone.id) zone.normalized() else it } }
    fun startCalibration() { trying = false; calibrating = true; completed = false; sample = 0f; controlsExpanded = false }
    val latestPreview by rememberUpdatedState(onPreview)
    LaunchedEffect(draft, trying, resetRequested) { latestPreview(if (resetRequested && draft == null) resetZones else draft, trying) }
    DisposableEffect(Unit) { onDispose { latestPreview(null, false) } }
    BackHandler { if (trying) trying = false else onDismiss() }
    val dim by animateFloatAsState(if (trying) 0f else .17f, spring(1f, 500f), label = "readerEditDim")
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        // No scale, aspect ratio, padding or re-pagination is applied to the underlying reading scene.
        val full = Rect(Offset.Zero, Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
        val requested = viewportInWindow?.translate(-origin) ?: full
        val viewport = requested.intersect(full).takeIf { it.width > 0 && it.height > 0 } ?: full
        val viewportWidth = viewport.width / pixelDensity
        val viewportHeight = viewport.height / pixelDensity
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        if (!trying) {
            // Sibling shield, never an ancestor gesture interceptor around Android's curl surface.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                do { val event = awaitPointerEvent(PointerEventPass.Initial); event.changes.forEach { it.consume() } }
                while (event.changes.any { it.pressed })
            } })
            Box(Modifier.offset { IntOffset(viewport.left.roundToInt(), viewport.top.roundToInt()) }
                .size(with(density) { viewport.width.toDp() }, with(density) { viewport.height.toDp() })) {
                visible.forEachIndexed { index, zone -> key(zone.id) {
                    var born by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { born = true }
                    val appearance by animateFloatAsState(if (born) 1f else 0f, spring(.68f, 430f), label = "zoneCreation")
                    val l by animateFloatAsState(zone.left, spring(1f, 650f), label = "zoneLeft")
                    val t by animateFloatAsState(zone.top, spring(1f, 650f), label = "zoneTop")
                    val r by animateFloatAsState(zone.right, spring(1f, 650f), label = "zoneRight")
                    val b by animateFloatAsState(zone.bottom, spring(1f, 650f), label = "zoneBottom")
                    val active by animateFloatAsState(if (selectedId == zone.id) 1f else 0f, spring(.8f, 450f), label = "zoneSelection")
                    Canvas(Modifier.fillMaxSize()) {
                        val rect = Rect(l * size.width, t * size.height, r * size.width, b * size.height)
                            .inflate(-(1f - appearance) * min((r-l)*size.width, (b-t)*size.height) * .1f)
                        drawRoundRect(colors.primary.copy(alpha = (.025f + active * .065f) * appearance), rect.topLeft, rect.size, CornerRadius(10f))
                        drawRoundRect(Color.White.copy(alpha = .42f * appearance), rect.topLeft, rect.size, CornerRadius(10f), style = Stroke(DesignTokens.StrokeStrong.toPx()))
                        drawRoundRect(colors.primary.copy(alpha = (.5f + active * .5f) * appearance), rect.topLeft, rect.size, CornerRadius(10f), style = Stroke(DesignTokens.Hairline.toPx()))
                        if (active > .01f) listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight).forEach {
                            drawCircle(colors.surface, (4f + active * 1.5f).dp.toPx(), it)
                            drawCircle(colors.primary.copy(alpha = active), DesignTokens.StrokeStrong.toPx(), it)
                        }
                    }
                    // Labels are overlays, so even narrow edge regions remain at their true physical size.
                    val labelWidth = max(120f * pixelDensity, min(viewport.width * .8f, (r-l) * viewport.width - 16f * pixelDensity)).coerceAtMost(viewport.width)
                    Box(Modifier.fillMaxSize()) {
                        Text("${index + 1} · ${zone.tap.label}\n${zone.swipe.label}",
                            color = Color.White, fontSize = DesignTokens.TypeCaption, textAlign = TextAlign.Center,
                            modifier = Modifier.offset { IntOffset((((l+r)*.5f*viewport.width)-labelWidth*.5f).coerceIn(0f, viewport.width-labelWidth).roundToInt(),
                                (((t+b)*.5f*viewport.height)-80f*pixelDensity).coerceAtLeast(t*viewport.height+12f*pixelDensity).roundToInt()) }
                                .width(with(density) { labelWidth.toDp() }).clip(RoundedCornerShape(DesignTokens.RadiusXs))
                                .background(colors.scrim.copy(alpha = .5f)).padding(DesignTokens.SpaceCompact))
                    }
                } }
                        val currentEdit by rememberUpdatedState<(TouchZone) -> Unit>({ edit(it) })
                        val currentVisible by rememberUpdatedState(visible)
                        val currentSelected by rememberUpdatedState(selectedId)
                        val currentCalibration by rememberUpdatedState(calibrating)
                        val currentDraft by rememberUpdatedState(draft)
                        var grab by remember { mutableStateOf<TouchZone?>(null) }
                        var start by remember { mutableStateOf(Offset.Zero) }
                        var handle by remember { mutableIntStateOf(-1) }
                        var dragTotal by remember { mutableStateOf(Offset.Zero) }
                        var measuring by remember { mutableStateOf(false) }
                        var rawDown by remember { mutableStateOf(Offset.Zero) }
                        var multiPointer by remember { mutableStateOf(false) }
                        Box(Modifier.fillMaxSize().semantics { contentDescription = "真实阅读区域：拖动区域移动，四角缩放；校准时在选中区域滑动" }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    rawDown = down.position; multiPointer = false
                                    do { val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.count { it.pressed } > 1) multiPointer = true
                                    } while (event.changes.any { it.pressed })
                                }
                            }.pointerInput(vertical, viewportWidth, viewportHeight) {
                                detectTapGestures { point ->
                                    val hit = if (currentCalibration) currentVisible.firstOrNull { it.id == currentSelected && it.contains(point.x / size.width, point.y / size.height) } else TouchZone.hit(currentVisible, point.x / size.width, point.y / size.height)
                                    if (hit != null) { selectedId = hit.id; controlsExpanded = !currentCalibration }
                                }
                            }.pointerInput(vertical, viewportWidth, viewportHeight) {
                                detectDragGestures(
                                    onDragStart = { point ->
                                        start = rawDown; dragTotal = Offset.Zero
                                        val hit = if (currentCalibration) currentVisible.firstOrNull { it.id == currentSelected && it.contains(point.x / size.width, point.y / size.height) } else TouchZone.hit(currentVisible, point.x / size.width, point.y / size.height)
                                        grab = hit; if (hit != null) selectedId = hit.id
                                        measuring = currentCalibration && hit?.id == currentSelected
                                        handle = -1
                                        if (!measuring && hit != null) {
                                            val corners = listOf(Offset(hit.left * size.width, hit.top * size.height), Offset(hit.right * size.width, hit.top * size.height),
                                                Offset(hit.left * size.width, hit.bottom * size.height), Offset(hit.right * size.width, hit.bottom * size.height))
                                            handle = corners.indexOfFirst { (it - point).getDistance() < DesignTokens.SpaceXxl.toPx() }
                                            val barLength = (hit.distanceDp ?: defaultDistanceDp) / (if (vertical) viewportHeight else viewportWidth) * (if (vertical) size.height else size.width)
                                            val barStart = Offset((hit.left + hit.right) * .5f * size.width - if (vertical) 0f else barLength / 2,
                                                (hit.top + hit.bottom) * .5f * size.height - if (vertical) barLength / 2 else 0f)
                                            val end = barStart + if (vertical) Offset(0f, barLength) else Offset(barLength, 0f)
                                            if (hit.swipe == SwipeAction.PAGE && (point - end).getDistance() < 22.dp.toPx()) handle = 4
                                        }
                                    },
                                    onDrag = { change, delta ->
                                        if (multiPointer) return@detectDragGestures
                                        change.consume(); dragTotal = change.position - start
                                        val old = grab
                                        if (old != null) {
                                            val dx = dragTotal.x / size.width; val dy = dragTotal.y / size.height
                                            if (measuring) {
                                                sample = dragTotal.getDistance() / pixelDensity
                                            } else if (handle == 4) {
                                                val amount = if (vertical) dy * viewportHeight else dx * viewportWidth
                                                currentEdit(old.copy(distanceDp = ((old.distanceDp ?: defaultDistanceDp) + amount * 2f).coerceIn(12f, 360f)))
                                            } else {
                                                val next = when (handle) {
                                                    0 -> old.copy(left = (old.left + dx).coerceAtMost(old.right - .08f), top = (old.top + dy).coerceAtMost(old.bottom - .08f))
                                                    1 -> old.copy(right = old.right + dx, top = (old.top + dy).coerceAtMost(old.bottom - .08f))
                                                    2 -> old.copy(left = (old.left + dx).coerceAtMost(old.right - .08f), bottom = old.bottom + dy)
                                                    3 -> old.copy(right = old.right + dx, bottom = old.bottom + dy)
                                                    else -> {
                                                        val mx = dx.coerceIn(-old.left, 1f - old.right); val my = dy.coerceIn(-old.top, 1f - old.bottom)
                                                        old.copy(left = old.left + mx, right = old.right + mx, top = old.top + my, bottom = old.bottom + my)
                                                    }
                                                }
                                                currentEdit(next)
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        val old = grab
                                        if (measuring && !multiPointer && old != null && sample >= 12f) {
                                            currentEdit(old.copy(distanceDp = sample.coerceIn(12f, 360f)))
                                            calibrating = false; completed = true
                                            if (hapticsEnabled) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                        }
                                        grab = null; measuring = false
                                    },
                                    onDragCancel = { grab = null; measuring = false; sample = 0f },
                                )
                            })

                if (selected?.swipe == SwipeAction.PAGE) {
                    SwipeCalibrationArt(selected, range, vertical, viewportWidth, viewportHeight, calibrating, completed, sample, reverse)
                }
            }
        }
        // Only these compact floating tools occupy space; the reader underneath is never remeasured.
        Column(Modifier.align(if (toolsAtTop && !trying) Alignment.BottomCenter else Alignment.TopCenter)
            .safeDrawingPadding().padding(DesignTokens.SpaceSm).widthIn(max = com.example.ui.adaptive.AdaptiveSpec.sheetMaxWidth).fillMaxWidth()
            .acrylicPanel(RoundedCornerShape(DesignTokens.RadiusLg), surfaceAlpha = .92f)
            .padding(horizontal = DesignTokens.SpaceMd), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!trying) PullOutIconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "取消编辑") }
                Column(Modifier.weight(1f).padding(vertical = DesignTokens.SpaceSm)) {
                    Text(if (trying) "正在真实试读" else "自定义触控分区", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Text(if (trying) "使用未保存的分区，可直接翻页" else if (calibrating) "试着用自己最舒适的方式滑动一页"
                        else if (completed) "✓ 已记住你的手势 · 可继续微调" else "原尺寸阅读画面 · 拖动移动 / 四角缩放",
                        color = colors.primary, fontSize = DesignTokens.TypeCaption)
                }
                PullOutTextButton(onClick = { if (trying) trying = false else { onPreview(null, false); if (resetRequested) onRestoreDefault(); onSave(draft); onDismiss() } }) {
                    Text(if (trying) "返回编辑" else "保存")
                }
            }
        }
        if (!trying) Column(Modifier.align(if (toolsAtTop) Alignment.TopCenter else Alignment.BottomCenter)
            .safeDrawingPadding().padding(DesignTokens.SpaceSm).widthIn(max = com.example.ui.adaptive.AdaptiveSpec.sheetMaxWidth).fillMaxWidth()
            .acrylicPanel(RoundedCornerShape(DesignTokens.RadiusLg), surfaceAlpha = .94f)
            .padding(horizontal = DesignTokens.SpaceMd), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PullOutTextButton(onClick = { val added = TouchZone(); draft = visible + added; selectedId = added.id; startCalibration() }, enabled = visible.size < 64) { Text("添加分区") }
                PullOutTextButton(onClick = { controlsExpanded = !controlsExpanded }) { Text(if (controlsExpanded) "收起工具" else "设置功能") }
                PullOutTextButton(onClick = { calibrating = false; controlsExpanded = false; trying = true }) { Text("试读") }
                PullOutIconButton(onClick = { toolsAtTop = !toolsAtTop }) { Icon(Icons.Default.SwapVert, "工具移到另一侧") }
            }
            com.example.ui.components.PullOutVisibility(visible = controlsExpanded) {
                Column(Modifier.fillMaxWidth().heightIn(max = this@BoxWithConstraints.maxHeight * .52f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.SpaceCompact)) {
                    if (selected != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("分区 ${visible.indexOf(selected) + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            PullOutTextButton(onClick = { draft = visible.filterNot { it.id == selected.id } + selected }) { Text("置顶") }
                            PullOutIconButton(onClick = { draft = visible.filterNot { it.id == selected.id }; selectedId = draft?.lastOrNull()?.id; calibrating = false }) { Icon(Icons.Default.DeleteOutline, "删除分区") }
                        }
                        Text("点击行为", style = MaterialTheme.typography.labelLarge)
                        ActionChoices(TouchAction.entries.filter { !comic || it != TouchAction.LISTEN }, selected.tap, { it.label }) { edit(selected.copy(tap = it)) }
                        Text("滑动行为", style = MaterialTheme.typography.labelLarge)
                        ActionChoices(SwipeAction.entries, selected.swipe, { it.label }) {
                            edit(selected.copy(swipe = it)); if (it == SwipeAction.PAGE) startCalibration() else calibrating = false
                        }
                        if (selected.swipe == SwipeAction.PAGE) {
                            Text(if (selected.distanceDp == null) "沿用原有阅读器判定 · 校准快速完成距离" else "触发距离 ${range.roundToInt()} dp", color = colors.primary, fontSize = DesignTokens.TypeCaption)
                            LiquidSlider(range.coerceIn(12f, 360f), { edit(selected.copy(distanceDp = it)); completed = false }, valueRange = 12f..360f, formatLabel = { "${it.roundToInt()} dp" })
                            Text("仅调整快速翻页的完成距离；慢速拖角、卷起与回退保持原样，滑动不限方向。", fontSize = DesignTokens.TypeCaption, color = colors.onSurfaceVariant)
                            PullOutTextButton(onClick = { startCalibration() }) { Icon(Icons.Default.Gesture, null); Text("重新校准") }
                        }
                    }
                    Text("后添加 / 置顶区域优先；空白区域不操作。点击和滑动独立，双指缩放优先。", fontSize = DesignTokens.TypeCaption, color = colors.onSurfaceVariant)
                    PullOutTextButton(onClick = { draft = null; resetRequested = true; selectedId = resetZones.firstOrNull()?.id; calibrating = false; completed = false }) { Text("恢复原有默认") }
                }
            }
            if (!controlsExpanded && selected?.swipe == SwipeAction.PAGE) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (calibrating && sample >= 12f) "本次 ${sample.roundToInt()} dp" else if (selected.distanceDp == null) "原有判定 · 可拖动范围条端点" else "触发距离 ${range.roundToInt()} dp",
                    Modifier.weight(1f), color = colors.primary, fontSize = DesignTokens.TypeCaption)
                PullOutTextButton(onClick = { if (calibrating) calibrating = false else startCalibration() }) { Text(if (calibrating) "手动调整" else "重新校准") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ActionChoices(items: List<T>, selected: T, label: (T) -> String, choose: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpaceCompact)) {
        items.forEach { item -> FilterChip(selected = selected == item, onClick = { choose(item) }, label = { Text(label(item), fontSize = DesignTokens.TypeCaption) }) }
    }
}

/** Decoration only: never intercepts the calibration pointer stream. */
@Composable
private fun SwipeCalibrationArt(zone: TouchZone, distance: Float, vertical: Boolean, viewportW: Float, viewportH: Float,
                                guiding: Boolean, complete: Boolean, sample: Float, reverse: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val surface = MaterialTheme.colorScheme.surface
    val reduce = com.example.ui.feedback.LocalReduceMotion.current
    val phase = remember { Animatable(0f) }
    val bidirectional = zone.tap != TouchAction.PREVIOUS && zone.tap != TouchAction.NEXT
    var demoAction by remember(zone.id, zone.tap) { mutableStateOf(if (zone.tap == TouchAction.PREVIOUS) TouchAction.PREVIOUS else TouchAction.NEXT) }
    val labelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER } }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(complete) { if (complete) { flash.snapTo(1f); flash.animateTo(0f, spring(.85f, 180f)) } }
    val animatedDistance by animateFloatAsState(distance, spring(.86f, 580f), label = "swipeRangeLength")
    LaunchedEffect(guiding, reduce, zone.id, zone.tap, reverse) {
        if (guiding && !reduce) while (true) {
            phase.snapTo(0f); phase.animateTo(1f, tween(2200, easing = LinearEasing)); kotlinx.coroutines.delay(450)
            if (bidirectional) demoAction = if (demoAction == TouchAction.NEXT) TouchAction.PREVIOUS else TouchAction.NEXT
        }
    }
    val success by animateFloatAsState(if (complete) 1f else 0f, spring(.55f, 280f), label = "calibrationSuccess")
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset((zone.left + zone.right) * .5f * size.width, (zone.top + zone.bottom) * .5f * size.height)
        val axisScale = if (vertical) size.height / viewportH else size.width / viewportW
        val length = animatedDistance * axisScale
        val axis = if (vertical) Offset(0f, 1f) else Offset(1f, 0f)
        val start = center - axis * (length / 2)
        val end = center + axis * (length / 2)
        drawLine(accent.copy(alpha = .12f), start, end, DesignTokens.SpaceComfortable.toPx(), StrokeCap.Round)
        drawLine(accent.copy(alpha = .7f), start, end, DesignTokens.StrokeStrong.toPx(), StrokeCap.Round)
        drawCircle(surface, DesignTokens.SpaceCompact.toPx(), start); drawCircle(accent, DesignTokens.StrokeStrong.toPx(), start)
        drawCircle(surface, (7 + success * 2).dp.toPx(), end); drawCircle(accent, DesignTokens.SpaceXs.toPx(), end)
        if (flash.value > .01f) {
            drawCircle(secondary.copy(alpha = .24f * flash.value), (12f + 26f * (1f - flash.value)).dp.toPx(), end, style = Stroke(DesignTokens.CardElevation.toPx()))
            for (i in 0..5) { val angle = i * PI.toFloat() / 3; val radius = (12f + 18f * (1f - flash.value)).dp.toPx()
                drawCircle(accent.copy(alpha = flash.value * .65f), 1.5.dp.toPx(), end + Offset(cos(angle), sin(angle)) * radius) }
        }
        if (success > .01f) drawCircle(secondary.copy(alpha = .16f * (1 - (success - 1).coerceAtLeast(0f))), (18 * success).dp.toPx(), end, style = Stroke(DesignTokens.CardElevation.toPx()))
        // Opposite physical directions, including RTL. These are examples, never a gesture constraint.
        val normal = if (vertical) Offset(1f, 0f) else Offset(0f, -1f)
        val actions = if (bidirectional) listOf(TouchAction.PREVIOUS, TouchAction.NEXT) else listOf(zone.tap)
        actions.forEachIndexed { index, action ->
            val sign = swipeGuideSign(action, reverse)
            val lane = center + normal * ((if (index == 0) 22f else -22f).dp.toPx())
            val head = lane + axis * (sign * length / 2f)
            val tail = lane - axis * (sign * length / 2f)
            val guide = Path().apply {
                moveTo(tail.x, tail.y); quadraticTo(center.x + normal.x * 9.dp.toPx(), center.y + normal.y * 9.dp.toPx(), head.x, head.y)
            }
            drawPath(guide, accent.copy(alpha = .5f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))))
            drawLine(accent, head, head - axis * (sign * 7.dp.toPx()) + normal * 4.dp.toPx(), 1.5.dp.toPx(), StrokeCap.Round)
            drawLine(accent, head, head - axis * (sign * 7.dp.toPx()) - normal * 4.dp.toPx(), 1.5.dp.toPx(), StrokeCap.Round)
            labelPaint.color = accent.toArgb(); labelPaint.textSize = 11.sp.toPx()
            drawContext.canvas.nativeCanvas.drawText(action.label, lane.x + normal.x * 12.dp.toPx(),
                lane.y + normal.y * 12.dp.toPx(), labelPaint)
        }
        if (guiding) {
            val direction = swipeGuideSign(demoAction, reverse)
            val demoAxis = axis * direction
            val demoStart = center - demoAxis * (length / 2)
            val p = phase.value
            val travel = ((p - .12f) / .65f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
            val tip = demoStart + demoAxis * (length * travel)
            val normal = if (vertical) Offset(1f, 0f) else Offset(0f, -1f)
            val path = Path().apply {
                moveTo(demoStart.x, demoStart.y)
                val c1 = demoStart + demoAxis * length * .32f + normal * 7.dp.toPx()
                val c2 = tip - demoAxis * length * .15f + normal * 5.dp.toPx()
                cubicTo(c1.x, c1.y, c2.x, c2.y, tip.x, tip.y)
            }
            drawPath(path, Brush.linearGradient(listOf(secondary.copy(alpha = .25f), accent), demoStart, tip + Offset(1f, 1f)),
                style = Stroke(DesignTokens.CardElevation.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DesignTokens.SpaceXs.toPx(), 5.dp.toPx()), -p * 60.dp.toPx())))
            val alpha = (sin(PI * p).toFloat() * 1.6f).coerceIn(0f, 1f)
            drawCircle(accent.copy(alpha = .10f * alpha), DesignTokens.SpaceLoose.toPx(), tip)
            drawRoundRect(surface.copy(alpha = .9f * alpha), tip - Offset(5.dp.toPx(), DesignTokens.CardElevation.toPx()), Size(DesignTokens.SpaceMd.toPx(), 26.dp.toPx()), CornerRadius(7.dp.toPx()))
            drawCircle(Color.White.copy(alpha = alpha * .8f), DesignTokens.SpaceXs.toPx(), tip + Offset(DesignTokens.Hairline.toPx(), DesignTokens.SpaceXs.toPx()))
            drawCircle(accent.copy(alpha = .7f * alpha), DesignTokens.SpaceCompact.toPx(), tip, style = Stroke(DesignTokens.Hairline.toPx()))
            if (sample > 0) drawLine(secondary, demoStart, demoStart + demoAxis * (sample * axisScale), DesignTokens.CardElevation.toPx(), StrokeCap.Round)
        }
    }
}
