package com.example.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Standard controls keep values and callbacks while avoiding native liquid renderers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrdinarySlider(
    value: Float, onValueChange: (Float) -> Unit, modifier: Modifier,
    valueRange: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean, reverse: Boolean,
    rangeEnd: Float?, minGap: Float, onRangeChange: ((Float, Float) -> Unit)?,
    onValueChangeFinished: (() -> Unit)?, onDraggingChange: ((Boolean) -> Unit)?,
    formatLabel: (Float) -> String,
) {
    val startInteraction = remember { MutableInteractionSource() }
    val endInteraction = remember { MutableInteractionSource() }
    val startDragging by startInteraction.collectIsDraggedAsState()
    val endDragging by endInteraction.collectIsDraggedAsState()
    val dragging = startDragging || endDragging
    val supportedSteps = steps.coerceIn(0, LiquidSliderConfig.MAX_STOPS)
    val reportDragging by rememberUpdatedState(onDraggingChange)
    var reportedDragging by remember { mutableStateOf(false) }
    LaunchedEffect(dragging) {
        if (reportedDragging != dragging) {
            reportedDragging = dragging
            reportDragging?.invoke(dragging)
        }
    }
    DisposableEffect(Unit) { onDispose { if (reportedDragging) reportDragging?.invoke(false) } }
    Column(modifier.fillMaxWidth().height(LiquidSliderConfig.HEIGHT.dp), verticalArrangement = Arrangement.Center) {
        Text(if (rangeEnd == null) formatLabel(value) else "${formatLabel(value)} – ${formatLabel(rangeEnd)}",
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp))
        CompositionLocalProvider(LocalLayoutDirection provides if (reverse) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            if (rangeEnd == null) {
                androidx.compose.material3.Slider(value = value.coerceIn(valueRange), onValueChange = onValueChange,
                    enabled = enabled, valueRange = valueRange, steps = supportedSteps, interactionSource = startInteraction,
                    onValueChangeFinished = onValueChangeFinished, modifier = Modifier.fillMaxWidth())
            } else {
                val gap = minGap.coerceIn(0f, valueRange.endInclusive - valueRange.start)
                val start = value.coerceIn(valueRange.start, valueRange.endInclusive - gap)
                val end = rangeEnd.coerceIn(start + gap, valueRange.endInclusive)
                RangeSlider(value = start..end, onValueChange = { next ->
                    if (next.start != start) onRangeChange?.invoke(next.start.coerceAtMost(next.endInclusive - gap), next.endInclusive)
                    else onRangeChange?.invoke(next.start, next.endInclusive.coerceAtLeast(next.start + gap))
                }, enabled = enabled, valueRange = valueRange, steps = supportedSteps,
                    startInteractionSource = startInteraction, endInteractionSource = endInteraction,
                    onValueChangeFinished = onValueChangeFinished, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrdinarySegmentedSelector(options: List<LiquidSegmentOption>, selected: Int,
    onSelect: (Int) -> Unit, modifier: Modifier, height: Dp, fontSp: Float) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().height(height)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(selected = selected == option.id, onClick = { if (selected != option.id) onSelect(option.id) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size), icon = {}) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(option.label, fontSize = fontSp.sp, maxLines = 1)
                    if (option.favorite) Text("♥", fontSize = fontSp.sp)
                    if (option.badge > 0) Text(option.badge.toString(), fontSize = fontSp.sp)
                }
            }
        }
    }
}
