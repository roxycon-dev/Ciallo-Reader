package com.example.ui.components

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.ui.adaptive.adaptiveDialogWidth
import com.example.ui.adaptive.adaptiveSheetWidth
import me.trishiraj.shadowglow.consistentShadow

/** Allows an action button to finish the same exit as back/outside dismissal. */
val LocalOverlayClose = compositionLocalOf<((() -> Unit) -> Unit)> { { action -> action() } }

@Composable
private fun overlayDismiss(visibility: MutableTransitionState<Boolean>, onDismiss: () -> Unit): ((() -> Unit) -> Unit) {
    return rememberPaperClose(visibility, onDismiss)
}

/** 亚克力立牌面板：品牌色 tint + 镜面光束 + 顶棱聚光 + 颗粒噪点 + 水晶棱镜描边 + 倒角高光 + 双层阴影。 */
@Composable
fun Modifier.acrylicPanel(
    shape: Shape = RoundedCornerShape(24.dp),
    surfaceAlpha: Float = 0.50f
): Modifier {
    if (LocalRenderQuality.current.minimal) return clip(shape).background(MaterialTheme.colorScheme.surface)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val prismColors = rememberCrystalPrismColors()
    val surface = MaterialTheme.colorScheme.surface.copy(alpha = surfaceAlpha)
    return this
        .consistentShadow(
            elevation = 32.dp,
            shape = shape,
            ambientColor = primary.copy(alpha = 0.14f),
            spotColor = primary.copy(alpha = 0.18f)
        )
        .consistentShadow(
            elevation = 8.dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.18f),
            spotColor = Color.Black.copy(alpha = 0.22f)
        )
        .clip(shape)
        .background(surface)
        .drawBehind {
            // 顶棱抛光聚光带
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.08f),
                        Color.White.copy(alpha = 0.50f),
                        Color.White.copy(alpha = 0.72f),
                        Color.White.copy(alpha = 0.50f),
                        Color.White.copy(alpha = 0.08f)
                    )
                ),
                topLeft = androidx.compose.ui.geometry.Offset.Zero,
                size = androidx.compose.ui.geometry.Size(size.width, 2.5.dp.toPx())
            )
            // 125° 对角镜面光束与底部微晕染
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.20f),
                        Color.White.copy(alpha = 0.05f),
                        Color.Transparent,
                        primary.copy(alpha = 0.035f),
                        secondary.copy(alpha = 0.055f)
                    ),
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height)
                )
            )
        }
        .filmGrain(alpha = 0.035f)
        .iridescentBorder(
            shape = shape,
            colors = prismColors,
            width = 1.5.dp,
            alpha = 0.38f
        )
        .crystalInnerBevel(shape = shape, width = 1.dp)
}

/** 统一亚克力弹窗：径向遮罩 + 玻璃面板 + 主题一致的按钮区。 */
@Composable
fun AcrylicDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null
) {
    val activity = LocalContext.current as? Activity
    val blurPx = with(LocalDensity.current) { 18.dp.toPx() }
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    val close = overlayDismiss(visibility, onDismissRequest)
    PullOutDialog(
        animateContent = false, lifecycleState = visibility,
        onDismissRequest = { close {} },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        CompositionLocalProvider(LocalOverlayClose provides close) { DialogLiquidGlass {
            GlassDialogWindowEffect(activity = activity, blurRadiusPx = blurPx)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ScrimEntrance(visibility = visibility) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (LocalRenderQuality.current.minimal) Modifier.background(Color.Black.copy(alpha = PullOutConfig.SCRIM)) else Modifier.radialGlassScrim())
                            .pullOutClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { close {} }
                            )
                    )
                }
                DialogEntrance(visibility = visibility) {
                Column(
                    modifier = modifier
                        .adaptiveDialogWidth(0.86f)
                        .acrylicPanel(shape = shape)
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                        .padding(24.dp)
                ) {
                    title?.invoke()
                    if (title != null && text != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    text?.invoke()
                    if (confirmButton != null || dismissButton != null) {
                        Spacer(modifier = Modifier.height(18.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CompositionLocalProvider(LocalPaperButtonAction provides close) { dismissButton?.invoke() }
                            if (dismissButton != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            CompositionLocalProvider(LocalPaperButtonAction provides close) { confirmButton?.invoke() }
                        }
                    }
                }
                }
            }
        } }
    }
}

/** 底部悬浮亚克力面板（下载中心等）：Dialog + decorView 实时模糊 + 径向遮罩。 */
@Composable
fun AcrylicBottomOverlay(
    onDismissRequest: () -> Unit,
    shape: Shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    dismissOnBackgroundClick: Boolean = true,
    fitAvailableHeight: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val activity = LocalContext.current as? Activity
    val blurPx = with(LocalDensity.current) { 18.dp.toPx() }
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    val close = overlayDismiss(visibility, onDismissRequest)
    PullOutDialog(
        animateContent = false, lifecycleState = visibility,
        onDismissRequest = { close {} },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = !fitAvailableHeight)
    ) {
        CompositionLocalProvider(LocalOverlayClose provides close) { DialogLiquidGlass {
            GlassDialogWindowEffect(activity = activity, blurRadiusPx = blurPx)
            BoxWithConstraints(modifier = Modifier.fillMaxSize().then(if (fitAvailableHeight) Modifier.safeDrawingPadding().imePadding().padding(bottom = 16.dp) else Modifier)) {
                ScrimEntrance(visibility = visibility) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (LocalRenderQuality.current.minimal) Modifier.background(Color.Black.copy(alpha = PullOutConfig.SCRIM)) else Modifier.radialGlassScrim())
                            .pullOutClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { if (dismissOnBackgroundClick) close {} }
                            )
                    )
                }
                val sheetHeight = maxHeight * .9f
                BottomSheetEntrance(modifier = Modifier.align(Alignment.BottomCenter), visibility = visibility) {
                Column(
                    modifier = Modifier
                        .adaptiveSheetWidth()
                        .then(if (fitAvailableHeight) Modifier.height(sheetHeight) else Modifier)
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                        .acrylicPanel(shape = shape, surfaceAlpha = 0.40f)
                        .then(if (fitAvailableHeight) Modifier else Modifier.navigationBarsPadding())
                ) {
                    content()
                }
                }
            }
        } }
    }
}
