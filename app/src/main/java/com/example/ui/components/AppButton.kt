package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.trishiraj.shadowglow.consistentShadow
import me.trishiraj.shadowglow.shadowGlow
import dev.liquidglass.compose.GlassHighlight
import dev.liquidglass.compose.GlassRefraction
import dev.liquidglass.compose.GlassShape
import dev.liquidglass.compose.GlassStyle
import dev.liquidglass.compose.LiquidGlassProviderState
import dev.liquidglass.compose.liquidGlass

/**
 * 全局统一按钮模块（真液态玻璃版）。
 *
 * 四个变体用“结构”区分层级，而不是同一色调的不同透明度：
 * - Primary：实心渐变 + 液态玻璃高光 + gel-press，唯一主操作。
 * - Secondary：accent 固定 14% 色调填充 + 25% 描边，常规操作。
 * - Tertiary：纯描边幽灵按钮，低频/外部跳转。
 * - Destructive：独立语义色，危险操作。
 *
 * 所有颜色取自 [LocalAppButtonColors] 主题 token；未提供时自动跟随
 * MaterialTheme（primary / secondary / error），组件内部零硬编码主题色。
 * disabled 统一 alpha 0.4；内置 loading 态（图标位置替换为转圈）。
 */

enum class AppButtonVariant {
    Primary,
    Secondary,
    Tertiary,
    Destructive
}

enum class AppButtonSize(
    val height: Dp,
    val horizontalPadding: Dp,
    val fontSize: TextUnit,
    val iconSize: Dp
) {
    Small(height = 36.dp, horizontalPadding = 16.dp, fontSize = 13.sp, iconSize = 14.dp),
    Medium(height = 44.dp, horizontalPadding = 20.dp, fontSize = 14.sp, iconSize = 16.dp),
    Large(height = 52.dp, horizontalPadding = 24.dp, fontSize = 15.sp, iconSize = 18.dp)
}

data class AppButtonColors(
    val primary: Color,
    val primaryVariant: Color,
    val accent: Color,
    val destructive: Color
)

/** 主题 token；不提供时 [AppButton] 内自动从 MaterialTheme 派生。 */
val LocalAppButtonColors = staticCompositionLocalOf<AppButtonColors?> { null }

/** 从主题派生按钮配色（主色/主色浅变体/强调色/语义错误色）。 */
@Composable
fun rememberAppButtonColors(): AppButtonColors {
    val themePrimary = MaterialTheme.colorScheme.primary
    val themeAccent = MaterialTheme.colorScheme.secondary
    val themeDestructive = MaterialTheme.colorScheme.error
    val fallbackColors = remember(themePrimary, themeAccent, themeDestructive) {
        AppButtonColors(
            primary = themePrimary,
            primaryVariant = lerp(themePrimary, Color.White, 0.35f),
            accent = themeAccent,
            destructive = themeDestructive
        )
    }
    return LocalAppButtonColors.current ?: fallbackColors
}

@Composable
fun AppActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    buttonSize: AppButtonSize = AppButtonSize.Medium,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    fullWidth: Boolean = false
) {
    val paperAction = paperClick(onClick)
    if (LocalRenderQuality.current.minimal) {
        val cs = MaterialTheme.colorScheme
        val bg = when (variant) {
            AppButtonVariant.Primary -> cs.primary
            AppButtonVariant.Destructive -> cs.error
            AppButtonVariant.Secondary -> cs.secondaryContainer
            AppButtonVariant.Tertiary -> Color.Transparent
        }
        val ink = when (variant) {
            AppButtonVariant.Primary -> cs.onPrimary
            AppButtonVariant.Destructive -> cs.onError
            AppButtonVariant.Secondary -> cs.onSecondaryContainer
            AppButtonVariant.Tertiary -> cs.primary
        }
        Row(modifier.then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .height(buttonSize.height).clip(RoundedCornerShape(12.dp))
            .background(bg).pullOutClickable(enabled = enabled && !loading, onClick = paperAction)
            .padding(horizontal = buttonSize.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            icon?.let { Icon(it, null, tint = ink, modifier = Modifier.size(buttonSize.iconSize)); Spacer(Modifier.width(6.dp)) }
            Text(if (loading) "处理中…" else text, color = ink.copy(alpha = if (enabled) 1f else .4f), fontSize = buttonSize.fontSize)
        }
        return
    }
    val colors = rememberAppButtonColors()
    val glass = LocalLiquidGlassState.current
    val primaryRimColors = remember(colors) {
        listOf(
            colors.primary,
            colors.accent,
            lerp(colors.primary, colors.accent, 0.5f),
            colors.primary
        )
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "appButtonScale"
    )
    val shadowElevation by animateDpAsState(
        targetValue = if (isPressed && enabled) {
            2.dp
        } else if (variant == AppButtonVariant.Primary) {
            16.dp
        } else {
            0.dp
        },
        label = "appButtonShadow"
    )

    val shape = RoundedCornerShape(percent = 50)
    val disabledAlpha = 0.4f

    val pressModifier = modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = if (enabled) 1f else disabledAlpha
        }
        .height(buttonSize.height)
        .let { if (fullWidth) it.fillMaxWidth() else it }

    Box(
        modifier = when (variant) {
            AppButtonVariant.Primary -> pressModifier
                .consistentShadow(
                    elevation = shadowElevation,
                    shape = shape,
                    ambientColor = colors.primary.copy(alpha = 0.35f),
                    spotColor = colors.primary.copy(alpha = 0.35f)
                )
                // D1 极致档：ShadowGlow 辉光（呼吸+光尾），其余档位零开销
                // 参数拉满确保肉眼可见：大 blur + 大 spread + 高 alpha + 宽光尾
                .then(
                    if (LocalRenderQuality.current == RenderQuality.MAX) {
                        Modifier.shadowGlow(
                            color = colors.primary.copy(alpha = 0.85f),
                            borderRadius = 50.dp,
                            blurRadius = 48.dp,
                            offsetY = 8.dp,
                            spread = 12.dp,
                            enableBreathingEffect = true,
                            breathingEffectIntensity = 14.dp,
                            breathingDurationMillis = 1200,
                            enableGlowTrail = true,
                            glowTrailWidth = 14.dp,
                            glowTrailBlurRadius = 28.dp,
                            glowTrailLengthDegrees = 90f,
                            glowTrailAlpha = 0.9f
                        )
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (glass != null) {
                        Modifier.liquidGlass(glass, primaryGlassStyle(colors))
                    } else {
                        Modifier
                    }
                )
                .background(
                    // 纵向渐变：上浅下深，模拟顶光打在胶囊上的体积感
                    Brush.verticalGradient(
                        listOf(
                            colors.primary.copy(alpha = 0.52f),
                            lerp(colors.primary, colors.primaryVariant, 0.5f).copy(alpha = 0.40f)
                        )
                    ),
                    shape
                )
                .clip(shape)
                .drawWithContent {
                    drawContent()
                    // 顶部内高光细线：胶囊受光的"锋利感"来源
                    drawLine(
                        color = Color.White.copy(alpha = 0.45f),
                        start = Offset(size.width * 0.16f, 1.2f),
                        end = Offset(size.width * 0.84f, 1.2f),
                        strokeWidth = 1.2f
                    )
                    // 底部内暗线：接地面的厚度
                    drawLine(
                        color = Color.Black.copy(alpha = 0.18f),
                        start = Offset(size.width * 0.20f, size.height - 1.0f),
                        end = Offset(size.width * 0.80f, size.height - 1.0f),
                        strokeWidth = 1.0f
                    )
                    // 左上镜面光晕
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(
                                Color.White.copy(alpha = 0.30f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.24f, size.height * 0.12f),
                            radius = size.width * 0.55f
                        ),
                        radius = size.width * 0.55f,
                        center = Offset(size.width * 0.24f, size.height * 0.12f)
                    )
                }
                .iridescentBorder(
                    shape = shape,
                    colors = primaryRimColors,
                    width = 1.5.dp,
                    alpha = 0.35f
                )

            AppButtonVariant.Secondary -> pressModifier
                .background(
                    // 纵向渐变：与 Primary 同语言但更含蓄
                    Brush.verticalGradient(listOf(
                        colors.accent.copy(alpha = 0.18f),
                        colors.accent.copy(alpha = 0.10f)
                    )),
                    shape
                )
                .clip(shape)
                .drawWithContent {
                    drawContent()
                    // 顶部内高光细线（比 Primary 更淡）
                    drawLine(
                        color = Color.White.copy(alpha = 0.28f),
                        start = Offset(size.width * 0.18f, 1.0f),
                        end = Offset(size.width * 0.82f, 1.0f),
                        strokeWidth = 1.0f
                    )
                }
                .border(1.dp, colors.accent.copy(alpha = 0.30f), shape)

            AppButtonVariant.Tertiary -> pressModifier
                .background(Color.Transparent, shape)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape)

            AppButtonVariant.Destructive -> pressModifier
                .background(
                    Brush.verticalGradient(listOf(
                        colors.destructive.copy(alpha = 0.16f),
                        colors.destructive.copy(alpha = 0.08f)
                    )),
                    shape
                )
                .clip(shape)
                .drawWithContent {
                    drawContent()
                    drawLine(
                        color = Color.White.copy(alpha = 0.20f),
                        start = Offset(size.width * 0.18f, 1.0f),
                        end = Offset(size.width * 0.82f, 1.0f),
                        strokeWidth = 1.0f
                    )
                }
                .border(1.dp, colors.destructive.copy(alpha = 0.35f), shape)
        }
            .pullOutClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = paperAction
            )
            .padding(horizontal = buttonSize.horizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        val contentColor = when (variant) {
            AppButtonVariant.Primary -> Color.White
            AppButtonVariant.Secondary -> colors.accent
            AppButtonVariant.Tertiary -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            AppButtonVariant.Destructive -> colors.destructive
        }

        AnimatedContent(
            targetState = loading,
            label = "appButtonContent"
        ) { isLoading ->
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(buttonSize.iconSize),
                    color = contentColor,
                    strokeWidth = 2.dp
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    icon?.let {
                        Icon(
                            imageVector = it,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(buttonSize.iconSize)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = text,
                        color = contentColor,
                        fontSize = buttonSize.fontSize,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun primaryGlassStyle(colors: AppButtonColors): GlassStyle =
    GlassStyle.Regular.interactive().copy(
        shape = GlassShape.Capsule,
        blurRadius = 20.dp,
        refraction = GlassRefraction(height = 14.dp, amount = 22.dp),
        saturation = 1.5f,
        tint = colors.primary.copy(alpha = 0.30f),
        highlight = GlassHighlight(
            width = 3.dp,
            alpha = 0.85f,
            lightAngleDegrees = 245f
        ),
        noiseAlpha = 0.02f,
        chromaticAberration = 0.4f
    )
