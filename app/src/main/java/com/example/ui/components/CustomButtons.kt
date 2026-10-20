package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MintPrimary
import com.example.ui.theme.clickableWithFeedback

/**
 * A highly polished icon button component with built-in spring-scale animation
 * and unbounded/borderless Material Ripple.
 */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clickableWithFeedback(
                enabled = enabled,
                bounded = false, // Unbounded borderless ripple
                onClick = paperClick(onClick)
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * A highly polished button component with built-in spring-scale animation
 * and bounded Material Ripple.
 */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MintPrimary,
    contentColor: Color = Color.White,
    shape: Shape = CircleShape,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .clickableWithFeedback(
                enabled = enabled,
                bounded = true,
                onClick = paperClick(onClick)
            ),
        shape = shape,
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.5f),
        contentColor = if (enabled) contentColor else contentColor.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
