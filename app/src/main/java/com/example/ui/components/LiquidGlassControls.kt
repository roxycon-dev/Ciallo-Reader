/*
 * 液态玻璃按钮 —— 基于 Abdullajon1881/LiquidGlass 实现：
 * SDF 透镜 + AGSL 边缘折射 + gel-press 凝胶按压 + 液体流动切换。
 * 按钮统一走 AppButton 四变体；开关已统一到 AppSwitch（见 AppSwitch.kt，
 * 第十一轮第 3 条：全局只保留一套开关组件），本文件不再承载开关实现。
 * 所有颜色取自主题 token（primary / primaryLight / accent / rimMid），
 * 组件内不硬编码主题色。
 */
package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.ImageVector
import dev.liquidglass.compose.LiquidGlassProviderState
import dev.liquidglass.compose.rememberLiquidGlassProviderState
import dev.liquidglass.compose.liquidGlassProvider

/** 全局液态玻璃 Provider 状态（由 MainActivity 提供，弹窗内由 DialogLiquidGlass 提供）。 */
val LocalLiquidGlassState: ProvidableCompositionLocal<LiquidGlassProviderState?> =
    staticCompositionLocalOf { null }
private val LocalGlassWindowOwner = staticCompositionLocalOf<android.view.View?> { null }

/**
 * 弹窗/底部弹层专用：为独立窗口创建属于该窗口自己的 LiquidGlass Provider。
 * 不能复用主窗口的 Provider（不同 view hierarchy 会抛
 * “layouts are not part of the same hierarchy”），必须每个窗口单独一个。
 */
@Composable
fun DialogLiquidGlass(
    fillMaxSize: Boolean = true,
    content: @Composable () -> Unit
) {
    if (LocalRenderQuality.current.minimal) {
        CompositionLocalProvider(LocalLiquidGlassState provides null) { content() }
        return
    }
    val view = LocalView.current
    if (LocalGlassWindowOwner.current === view) {
        // Existing call sites may request glass again inside the same modal. Keep
        // their layout, but share its one provider instead of recording it twice.
        Box(Modifier.then(if (fillMaxSize) Modifier.fillMaxSize() else Modifier)) { content() }
        return
    }
    val glass = rememberLiquidGlassProviderState()
    CompositionLocalProvider(LocalLiquidGlassState provides glass, LocalGlassWindowOwner provides view) {
        Box(modifier = Modifier.then(if (fillMaxSize) Modifier.fillMaxSize() else Modifier)) {
            // The optical source is a sibling, never an ancestor of its own lenses.
            // Keep the full shader/refraction pipeline without a cyclic display list.
            val colors = MaterialTheme.colorScheme
            Box(Modifier.matchParentSize().liquidGlassProvider(glass, visible = false)
                .background(Brush.linearGradient(listOf(colors.surface,
                    colors.primary.copy(alpha = .08f), colors.secondary.copy(alpha = .06f)))))
            content()
        }
    }
}

/**
 * 液态玻璃按钮兼容入口：统一走 [AppActionButton] Primary 变体
 * （实心渐变 + 液态玻璃高光 + gel-press + 双层阴影）。
 */
@Composable
fun AppLiquidButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    AppActionButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        variant = AppButtonVariant.Primary,
        buttonSize = AppButtonSize.Medium,
        icon = icon,
        enabled = enabled
    )
}
