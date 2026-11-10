package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MintPrimary
import com.example.ui.theme.clickableWithFeedback
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.example.ui.wenku.WenkuButtonGlyph
import com.example.ui.wenku.LocalWenkuIconContact
import com.example.ui.wenku.WenkuIconContact
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.wenkuSymbolOrNull

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
 * 图标按钮的 Wenku 版：`icon` 命中 [wenkuSymbolOrNull] 时改画手绘纸片图标，
 * 并带参考仓库的三拍手势（按下 140ms / 抬起 240ms 的 contact 时钟）；
 * 没命中就退回 Material 图标 —— 调用点不需要知道映射表。
 *
 * @param active 状态型图标（书签、听书、播放）传当前状态，图标会走状态 morph。
 */
@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color = LocalContentColor.current,
    glyphSize: Dp = 22.dp,
    wenkuSymbol: WenkuSymbol? = null
) {
    val symbol = wenkuSymbol ?: icon.wenkuSymbolOrNull()
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var activation by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .pullOutClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    activation++
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        val glyphTint = if (enabled) tint else tint.copy(alpha = .35f)
        CompositionLocalProvider(LocalWenkuIconContact provides WenkuIconContact(pressed && enabled, activation)) {
            WenkuButtonGlyph(icon, glyphSize, glyphTint, active, symbol)
        }
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
