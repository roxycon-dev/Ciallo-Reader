package com.example.ui.reader

import com.example.ui.design.DesignTokens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.kashif_e.backdrop.backdrops.LayerBackdrop
import com.kashif_e.backdrop.drawPlainBackdrop
import com.kashif_e.backdrop.effects.blur
import com.example.ui.components.overlayTouchShield

/** A theme-tinted overlay; the app theme (and its switches) stays untouched. */
@Composable
internal fun ReadingSettingsPanel(
    themeColor: Color,
    contentColor: Color,
    backdrop: LayerBackdrop?,
    onDismiss: () -> Unit,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    val latestDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(visibility.isIdle, visibility.currentState) {
        if (visibility.isIdle && !visibility.currentState && !visibility.targetState) latestDismiss()
    }
    Dialog(
        onDismissRequest = { visibility.targetState = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 600.dp || maxWidth > maxHeight
            val shape = RoundedCornerShape(DesignTokens.RadiusOverlay)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.08f)))
            AnimatedVisibility(
                visibleState = visibility,
                enter = fadeIn(tween(220)) + slideInVertically(tween(300)) { it / 8 },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(180)) { it / 8 },
                modifier = Modifier.align(if (wide) Alignment.CenterEnd else Alignment.BottomCenter)
                    .padding(horizontal = if (wide) DesignTokens.SpaceXl else DesignTokens.SpaceTight, vertical = DesignTokens.SpaceTight),
            ) {
                Box(
                    Modifier.widthIn(max = if (wide) 440.dp else 560.dp).fillMaxWidth()
                        .heightIn(max = maxHeight * if (wide) 0.94f else 0.80f)
                        .shadow(DesignTokens.SpaceTight, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.10f))
                        .clip(shape)
                        .then(if (backdrop != null) Modifier.drawPlainBackdrop(
                            backdrop = backdrop, shape = { shape },
                            effects = { blur(DesignTokens.SpaceSm.toPx()) },
                        ) else Modifier)
                        .background(themeColor.copy(alpha = 0.56f))
                        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent)))
                        .border(1.dp, contentColor.copy(alpha = 0.12f), shape)
                        .overlayTouchShield(),
                ) {
                    CompositionLocalProvider(LocalContentColor provides contentColor) { content { visibility.targetState = false } }
                }
            }
        }
    }
}
