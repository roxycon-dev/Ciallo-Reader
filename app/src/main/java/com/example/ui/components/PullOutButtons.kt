package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.wenku.WenkuMotion
import com.example.ui.wenku.WenkuSymbol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer

/** Reference LiquidGlass press: scale .97, 120ms in / 420ms settle out, no paper source. */
@Composable
private fun rememberPressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val reduce = com.example.ui.feedback.LocalReduceMotion.current
    return animateFloatAsState(
        targetValue = if (pressed && !reduce) .97f else 1f,
        animationSpec = if (pressed) {
            tween(120, easing = CubicBezierEasing(.215f, .61f, .355f, 1f))
        } else {
            tween(420, easing = CubicBezierEasing(.2f, 1.08f, .34f, 1f))
        },
        label = "referencePressScale"
    ).value
}

/** Standard Material semantics and appearance, with automatic paper source registration. */
@Composable
fun PullOutButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape, colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val scale = rememberPressScale(source)
    Button(onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale }, enabled, shape, colors, elevation, border, contentPadding, source, content)
}
@Composable
fun PullOutTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape, colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val scale = rememberPressScale(source)
    TextButton(onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale }, enabled, shape, colors, elevation, border, contentPadding, source, content)
}
@Composable
fun PullOutOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape, colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val scale = rememberPressScale(source)
    OutlinedButton(onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale }, enabled, shape, colors, elevation, border, contentPadding, source, content)
}
@Composable
fun PullOutFilledTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape, colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val scale = rememberPressScale(source)
    FilledTonalButton(onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale }, enabled, shape, colors, elevation, border, contentPadding, source, content)
}
@Composable
fun PullOutIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(), interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val scale = rememberPressScale(source)
    IconButton(onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale }, enabled, colors, source, content = content)
}

/**
 * Wenku 版图标按钮：图标名命中映射表时改用纸片图标 + 三拍手势；
 * 未命中保持 Material 图标。诊断面板 / 标题栏这类「只有一个图标」的按钮优先用它。
 */
@Composable
fun PullOutIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.LocalContentColor.current,
    glyphSize: Dp = 20.dp,
    wenkuSymbol: WenkuSymbol? = null
) {
    AppIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        active = active,
        tint = tint,
        glyphSize = glyphSize,
        wenkuSymbol = wenkuSymbol
    )
}
@Composable
fun PullOutDropdownMenuItem(text: @Composable () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true, colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuItemContentPadding,
    interactionSource: MutableInteractionSource? = null) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val scale = rememberPressScale(source)
    DropdownMenuItem(text, onClick, modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }, leadingIcon, trailingIcon, enabled, colors, contentPadding, source)
}
