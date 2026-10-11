package com.example.ui.reader

import com.example.ui.design.DesignTokens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.feedback.AppMotion
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
    val reduceMotion = LocalReduceMotion.current
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
            val scrim by androidx.compose.animation.core.animateFloatAsState(
                if (visibility.targetState) .16f else 0f, tween(if (reduceMotion) 0 else AppMotion.CONTENT_MS), label = "readingSettingsScrim")
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))
            AnimatedVisibility(
                visibleState = visibility,
                enter = if (reduceMotion) fadeIn(tween(0)) else fadeIn(tween(AppMotion.CONTENT_MS)) +
                    if (wide) slideInHorizontally(AppMotion.reorder()) { it / 5 } else slideInVertically(AppMotion.reorder()) { it / 5 },
                exit = if (reduceMotion) fadeOut(tween(0)) else fadeOut(tween(AppMotion.EXIT_MS)) +
                    if (wide) slideOutHorizontally(tween(AppMotion.EXIT_MS)) { it / 5 } else slideOutVertically(tween(AppMotion.EXIT_MS)) { it / 5 },
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
                    CompositionLocalProvider(
                        LocalContentColor provides contentColor,
                        LocalReadingGlass provides ReadingGlassStyle(backdrop, themeColor, contentColor),
                    ) { content { visibility.targetState = false } }
                }
            }
        }
    }
}
