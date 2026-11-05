package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.ui.feedback.AppMotion
import com.example.ui.feedback.LocalReduceMotion

/** One finite transition and one accessible label for stateful utility icons. */
@Composable
fun AnimatedStateIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val reduce = LocalReduceMotion.current
    val color = animateColorAsState(tint, if (reduce) snap() else AppMotion.icon(), label = "stateIconColor")
    Box(modifier.semantics { contentDescription?.let { this.contentDescription = it } }) {
        AnimatedContent(imageVector, modifier = Modifier.clearAndSetSemantics { }, transitionSpec = {
            if (reduce) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(tween(AppMotion.ICON_MS)) + scaleIn(AppMotion.icon(), initialScale = .82f)) togetherWith
                (fadeOut(tween(AppMotion.PRESS_MS)) + scaleOut(AppMotion.icon(), targetScale = .92f))
        }, label = "stateIcon") { icon ->
            Icon(icon, contentDescription = null, tint = color.value)
        }
    }
}

/** Disclosure chevrons rotate in place; their target geometry never changes layout. */
@Composable
fun AnimatedChevron(
    expanded: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    collapsedRotation: Float = 0f,
    expandedRotation: Float = 180f,
) {
    val reduce = LocalReduceMotion.current
    val rotation = animateFloatAsState(if (expanded) expandedRotation else collapsedRotation,
        if (reduce) snap() else AppMotion.icon(), label = "disclosureRotation")
    Icon(Icons.Default.ExpandMore, contentDescription, modifier.graphicsLayer { rotationZ = rotation.value }, tint)
}

/** The same restrained check reveal for chapter/list multi-selection. */
@Composable
fun AnimatedSelectionMark(
    checked: Boolean,
    modifier: Modifier = Modifier,
    uncheckedColor: Color = MaterialTheme.colorScheme.onBackground.copy(alpha = .35f),
) {
    val reduce = LocalReduceMotion.current
    val progress = animateFloatAsState(if (checked) 1f else 0f,
        if (reduce) snap() else AppMotion.icon(), label = "selectionMark")
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val outline = remember(density) { Stroke(with(density) { 1.5.dp.toPx() }) }
    Canvas(modifier.size(22.dp).semantics { contentDescription = if (checked) "已选中" else "未选中" }) {
        val p = progress.value
        val unit = size.minDimension / 22f
        drawCircle(uncheckedColor.copy(alpha = uncheckedColor.alpha * (1f - p)),
            (size.minDimension - outline.width) / 2f, style = outline)
        if (p > 0f) {
            drawCircle(colors.primary.copy(alpha = p), size.minDimension / 2f * (.8f + .2f * p))
            val ink = colors.onPrimary.copy(alpha = p)
            val middle = Offset(9f * unit, 14f * unit)
            drawLine(ink, Offset(6f * unit, 11f * unit), middle, 1.8f * unit, StrokeCap.Round)
            drawLine(ink, middle, Offset((9f + 7f * p) * unit, (14f - 7f * p) * unit), 1.8f * unit, StrokeCap.Round)
        }
    }
}
