package com.example.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

val LocalAppDarkTheme = staticCompositionLocalOf { false }

internal fun themeTextContrast(ink: Color, paper: Color): Float {
    val a = ink.luminance(); val b = paper.luminance()
    return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
}

/** Light and dark ink cannot cross through the same grey as the surface. */
internal fun legibleThemeInk(light: Color, dark: Color, paper: Color, progress: Float): Color {
    val candidate = lerp(light, dark, progress)
    if (themeTextContrast(candidate, paper) >= 4.5f) return candidate
    val endpoint = if (themeTextContrast(light, paper) >= themeTextContrast(dark, paper)) light else dark
    if (themeTextContrast(endpoint, paper) >= 4.5f) return endpoint
    return if (themeTextContrast(Color.Black, paper) >= themeTextContrast(Color.White, paper)) Color.Black else Color.White
}

val BasePrimaryColors = listOf(
    Color(0xFF2563EB), // 0: 蓝
    Color(0xFF7C3AED), // 1: 紫
    Color(0xFF059669), // 2: 绿
    Color(0xFFDB2777), // 3: 粉
    Color(0xFFEA580C)  // 4: 橙
)

val BaseSecondaryColors = listOf(
    Color(0xFF3B82F6), // 0: 蓝
    Color(0xFF8B5CF6), // 1: 紫
    Color(0xFF10B981), // 2: 绿
    Color(0xFFEC4899), // 3: 粉
    Color(0xFFF97316)  // 4: 橙
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorPrimaryIndex: Int = 2,
    colorSecondaryIndex: Int = 2,
    content: @Composable () -> Unit
) {
    val reduceMotion = com.example.ui.feedback.LocalReduceMotion.current || com.example.ui.feedback.systemReduceMotion()
    val colorTransitionMs = if (reduceMotion) 0 else 280
    val targetPrimary = BasePrimaryColors.getOrElse(colorPrimaryIndex) { BasePrimaryColors[2] }
    val targetSecondary = BaseSecondaryColors.getOrElse(colorSecondaryIndex) { BaseSecondaryColors[2] }
    val primaryColor by animateColorAsState(
        targetValue = targetPrimary,
        // 2026-09-21：换主题色是“全界面换肤”，线性补间的中段变化太平均，
        // 观感偏钝。改用 iOS easeOut：起步快、尾部缓，收尾更干净。
        animationSpec = tween(durationMillis = colorTransitionMs, easing = IosMotion.EaseOut),
        label = "themePrimary"
    )
    val secondaryColor by animateColorAsState(
        targetValue = targetSecondary,
        animationSpec = tween(durationMillis = colorTransitionMs, easing = IosMotion.EaseOut),
        label = "themeSecondary"
    )

    // Wenku8 论文纸 / 中性炭黑层级。主题色仍由用户选择，只承担强调与选中。
    val lightColorScheme = lightColorScheme(
        primary = primaryColor,
        secondary = secondaryColor,
        tertiary = primaryColor,
        background = com.example.ui.wenku.WenkuTokens.PaperLight,
        surface = com.example.ui.wenku.WenkuTokens.PanelLight,
        surfaceVariant = Color(0xFFF0EDE5),
        surfaceTint = Color.Transparent,
        onBackground = com.example.ui.wenku.WenkuTokens.InkLight,
        onSurface = com.example.ui.wenku.WenkuTokens.InkLight,
        onSurfaceVariant = com.example.ui.wenku.WenkuTokens.InkSecondaryLight,
        outline = Color(0xFFB9B4A8),
        outlineVariant = Color(0xFFDAD5C9),
        surfaceContainerLowest = Color(0xFFFFFEFA),
        surfaceContainerLow = Color(0xFFFAF8F2),
        surfaceContainer = Color(0xFFF4F2EB),
        surfaceContainerHigh = Color(0xFFEDEAE2),
        surfaceContainerHighest = Color(0xFFE5E1D7)
    )

    val darkColorScheme = darkColorScheme(
        primary = primaryColor,
        secondary = secondaryColor,
        tertiary = primaryColor,
        background = com.example.ui.wenku.WenkuTokens.PaperDark,
        surface = com.example.ui.wenku.WenkuTokens.PanelDark,
        surfaceVariant = com.example.ui.wenku.WenkuTokens.RaisedDark,
        surfaceTint = Color.Transparent,
        onBackground = com.example.ui.wenku.WenkuTokens.InkDark,
        onSurface = com.example.ui.wenku.WenkuTokens.InkDark,
        onSurfaceVariant = com.example.ui.wenku.WenkuTokens.InkSecondaryDark,
        outline = com.example.ui.wenku.WenkuTokens.OutlineDark,
        outlineVariant = com.example.ui.wenku.WenkuTokens.RaisedHighDark,
        surfaceContainerLowest = Color(0xFF151515),
        surfaceContainerLow = Color(0xFF1D1D1D),
        surfaceContainer = Color(0xFF242424),
        surfaceContainerHigh = Color(0xFF2D2D2D),
        surfaceContainerHighest = Color(0xFF3A3A3A)
    )

    val themeProgress by animateFloatAsState(if (darkTheme) 1f else 0f, tween(if (reduceMotion) 0 else 240, easing = IosMotion.EaseOut), label = "dayNightTheme")
    fun mix(light: Color, dark: Color) = lerp(light, dark, themeProgress)
    val paper = mix(lightColorScheme.background, darkColorScheme.background)
    val surface = mix(lightColorScheme.surface, darkColorScheme.surface)
    val variant = mix(lightColorScheme.surfaceVariant, darkColorScheme.surfaceVariant)
    fun containerInk(light: Color, dark: Color, lightPaper: Color, darkPaper: Color) =
        legibleThemeInk(light, dark, mix(lightPaper, darkPaper), themeProgress)
    val colorScheme = lightColorScheme.copy(
        primary = primaryColor,
        secondary = secondaryColor,
        tertiary = primaryColor,
        background = paper,
        surface = surface,
        onBackground = legibleThemeInk(lightColorScheme.onBackground, darkColorScheme.onBackground, paper, themeProgress),
        onSurface = legibleThemeInk(lightColorScheme.onSurface, darkColorScheme.onSurface, surface, themeProgress),
        onSurfaceVariant = legibleThemeInk(lightColorScheme.onSurfaceVariant, darkColorScheme.onSurfaceVariant, variant, themeProgress),
        surfaceVariant = variant,
        primaryContainer = mix(lightColorScheme.primaryContainer, darkColorScheme.primaryContainer),
        onPrimaryContainer = containerInk(lightColorScheme.onPrimaryContainer, darkColorScheme.onPrimaryContainer, lightColorScheme.primaryContainer, darkColorScheme.primaryContainer),
        secondaryContainer = mix(lightColorScheme.secondaryContainer, darkColorScheme.secondaryContainer),
        onSecondaryContainer = containerInk(lightColorScheme.onSecondaryContainer, darkColorScheme.onSecondaryContainer, lightColorScheme.secondaryContainer, darkColorScheme.secondaryContainer),
        outline = mix(lightColorScheme.outline, darkColorScheme.outline),
        outlineVariant = mix(lightColorScheme.outlineVariant, darkColorScheme.outlineVariant),
        errorContainer = mix(lightColorScheme.errorContainer, darkColorScheme.errorContainer),
        onErrorContainer = containerInk(lightColorScheme.onErrorContainer, darkColorScheme.onErrorContainer, lightColorScheme.errorContainer, darkColorScheme.errorContainer),
        tertiaryContainer = mix(lightColorScheme.tertiaryContainer, darkColorScheme.tertiaryContainer),
        onTertiaryContainer = containerInk(lightColorScheme.onTertiaryContainer, darkColorScheme.onTertiaryContainer, lightColorScheme.tertiaryContainer, darkColorScheme.tertiaryContainer),
        inverseSurface = mix(lightColorScheme.inverseSurface, darkColorScheme.inverseSurface),
        inverseOnSurface = containerInk(lightColorScheme.inverseOnSurface, darkColorScheme.inverseOnSurface, lightColorScheme.inverseSurface, darkColorScheme.inverseSurface),
        inversePrimary = mix(lightColorScheme.inversePrimary, darkColorScheme.inversePrimary),
        surfaceContainerLowest = mix(lightColorScheme.surfaceContainerLowest, darkColorScheme.surfaceContainerLowest),
        surfaceContainerLow = mix(lightColorScheme.surfaceContainerLow, darkColorScheme.surfaceContainerLow),
        surfaceContainer = mix(lightColorScheme.surfaceContainer, darkColorScheme.surfaceContainer),
        surfaceContainerHigh = mix(lightColorScheme.surfaceContainerHigh, darkColorScheme.surfaceContainerHigh),
        surfaceContainerHighest = mix(lightColorScheme.surfaceContainerHighest, darkColorScheme.surfaceContainerHighest),
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography
    ) {
        // 2026-09-21：Android 滚动容器默认带“边缘发光”（overscroll glow），
        // iOS 的 UIScrollView 没有这个效果（它用回弹而非光晕），这是滚动时
        // 一眼能看出的安卓味来源。全局关掉后，长列表/书架的滚动观感明显接近 iOS。
        CompositionLocalProvider(LocalOverscrollConfiguration provides null, LocalAppDarkTheme provides darkTheme) {
            content()
        }
    }
}
