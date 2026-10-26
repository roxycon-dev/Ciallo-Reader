package com.example.ui.components

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.BackdropEffectScope
import com.kashif_e.backdrop.backdrops.CanvasBackdrop
import com.kashif_e.backdrop.backdrops.PreBlurredBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.colorControls
import com.kashif_e.backdrop.effects.lens
import com.kashif_e.backdrop.effects.vibrancy
import com.kashif_e.backdrop.highlight.Highlight
import com.kashif_e.backdrop.highlight.HighlightStyle
import com.kashif_e.backdrop.shadow.InnerShadow
import com.kashif_e.backdrop.shadow.Shadow

/**
 * 当前窗口的玻璃采样源：由 MainActivity 在背景图/底色层上挂 layerBackdrop 后提供。
 * 页面内容卡（GlassCard）用它做与底部 Tab 栏完全同源的真实内容模糊。
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 是否具备实时 RenderEffect 模糊能力。
 *
 * Android 12（API 31）才引入 `RenderEffect`；KMPLiquidGlass 在更低版本上
 * 会把 blur / colorControls / vibrancy / lens **全部静默 no-op**
 * （见 `backdrop/.../platform/PlatformEffects.kt` 的 `PlatformCapabilities`），
 * 结果是 backdrop 以「未模糊的原始内容」直接透出——玻璃卡看起来像一块
 * 透明玻璃叠了张清晰底图，毛玻璃质感完全丢失。
 *
 * 因此所有玻璃调用点都必须经此判断走降级路径。
 */
val supportsRealtimeBlur: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 降级时给半透明基底额外加厚的 alpha（弥补没有真实模糊带来的「太透」）。 */
private const val GLASS_FALLBACK_ALPHA_BOOST = 0.20f

/**
 * 无 RenderEffect 能力时的毛玻璃降级：
 * 「加厚的半透明基底 + 柔和透光渐变 + 噪点纹理」近似磨砂介质，
 * 观感是「更实一点的磨砂面板」，绝不是透明 / 消失。
 */
private fun Modifier.frostedGlassFallback(shape: Shape, surfaceColor: Color): Modifier = this
    .background(
        surfaceColor.copy(alpha = (surfaceColor.alpha + GLASS_FALLBACK_ALPHA_BOOST).coerceAtMost(1f)),
        shape
    )
    .drawWithContent {
        // ① 全域介质渐变：左上受光提亮 → 右下轻微压暗，替代真实模糊的漫反射观感
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.12f),
                    Color.Transparent,
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.05f)
                )
            )
        )
        // ② 顶部受光带：磨砂玻璃上沿的亮边
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                startY = 0f,
                endY = size.height * 0.22f
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, size.height * 0.22f)
        )
        drawContent()
    }
    .filmGrain(alpha = 0.05f)

/**
 * 静态背景的预烘焙模糊 backdrop（由 MainActivity 提供）。
 * 玻璃卡用它做"整屏只模糊一次"的贴图采样；null 或未就绪时自动回退实时模糊。
 */
val LocalPreBlurredGlass = staticCompositionLocalOf<PreBlurredBackdrop?> { null }

/**
 * KMPLiquidGlass 官方 API 封装：
 * - 同一窗口内的玻璃层（书库搜索历史、页面内浮层）使用 [rememberLayerBackdrop] 捕获真实内容；
 * - 弹窗/底部弹层（Popup 独立窗口，无法跨窗口采样）使用 [rememberThemedGlassBackdrop]，
 *   以主题渐变作为 Backdrop 内容，再经 drawBackdrop 的 blur/colorControls/lens 产生液态玻璃质感。
 */

/**
 * 捕获同一窗口内的真实内容作为 Backdrop。
 * 用法：`Modifier.layerBackdrop(backdrop)` 放在需要透出的内容层上，
 * 玻璃元素再用 [Modifier.liquidGlass] 采样。
 */
@Composable
fun rememberScreenGlassBackdrop() = rememberLayerBackdrop()

/**
 * 为 Popup / Dialog 场景创建主题化渐变 Backdrop。
 * 使用 KMPLiquidGlass 官方 [CanvasBackdrop] API，不自行实现模糊。
 */
@Composable
fun rememberThemedGlassBackdrop(): Backdrop {
    val surface = MaterialTheme.colorScheme.surface
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    return remember(surface, primary, secondary) {
        CanvasBackdrop {
            drawThemeGradient(surface, primary, secondary)
        }
    }
}

/** 玻璃面板专用渐变：比通用渐变更明显的层次，白色背景上也能看出玻璃质感。 */
@Composable
fun rememberGlassPanelBackdrop(): Backdrop {
    val surface = MaterialTheme.colorScheme.surface
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    return remember(surface, primary, secondary) {
        CanvasBackdrop {
            // 关键：不画不透明底色，让 Dialog 背后被实时模糊的真实内容透过来
            drawRect(androidx.compose.ui.graphics.Color.Transparent)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.06f),
                        Color.Transparent,
                        secondary.copy(alpha = 0.05f),
                        primary.copy(alpha = 0.06f)
                    )
                )
            )
        }
    }
}

/**
 * 玻璃弹窗窗口辅助：
 * 1) 把 Dialog 窗口背景设为透明（否则盖住背后内容）；
 * 2) 对宿主 Activity 的 decorView 施加实时 RenderEffect 模糊——
 *    这是真正实时的背景模糊，后台滚动/动画都会跟着虚化。
 * 弹窗关闭时自动清除模糊。
 */
@Composable
fun GlassDialogWindowEffect(
    activity: Activity?,
    blurRadiusPx: Float
) {
    if (!LocalRenderQuality.current.realtimeGlass) return
    val view = LocalView.current
    DisposableEffect(view, activity, blurRadiusPx) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        // 去掉 Dialog 默认的重度变暗，只留轻量压暗让玻璃面板突出
        window?.setDimAmount(0.12f)

        val decorView = activity?.window?.decorView
        if (decorView != null && supportsRealtimeBlur) {
            GlassWindowBlur.acquire(decorView, window ?: view, blurRadiusPx)
        } else if (decorView != null) {
            // ── 跨机型降级（API < 31 无 RenderEffect）──────────────────
            // 宿主窗口无法实时模糊，弹窗背后的内容会以清晰原样透出，
            // 玻璃面板不再有「磨砂」层次。把窗口变暗量从轻压 0.12 提高
            // 到 0.42，用「暗化 + 弹窗本体加厚基底」近似毛玻璃层次，
            // 观感是明显的磨砂弹窗而不是透明的清晰玻璃。
            window?.setDimAmount(0.42f)
        }
        onDispose {
            if (android.os.Build.VERSION.SDK_INT >= 31 && decorView != null) GlassWindowBlur.release(decorView, window ?: view)
        }
    }
}

/** Multiple dialog windows share one activity blur; closing a child must not clear its parent. */
internal object GlassWindowBlur {
    private val owners = java.util.WeakHashMap<android.view.View, LinkedHashMap<Any, Float>>()
    @androidx.annotation.RequiresApi(31)
    fun acquire(view: android.view.View, owner: Any, radius: Float) {
        val entries = owners.getOrPut(view) { linkedMapOf() }
        entries[owner] = radius
        apply(view, entries.values.last())
    }
    @androidx.annotation.RequiresApi(31)
    fun release(view: android.view.View, owner: Any) {
        val entries = owners[view] ?: return
        entries.remove(owner)
        if (entries.isEmpty()) { owners.remove(view); view.setRenderEffect(null) }
        else apply(view, entries.values.last())
    }
    @androidx.annotation.RequiresApi(31)
    private fun apply(view: android.view.View, radius: Float) {
        view.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP))
    }
}

/**
 * KMPLiquidGlass 官方 `Modifier.drawBackdrop` 的封装：
 * 液态玻璃表面 = backdrop（真实内容或主题渐变）+ blur + colorControls + lens + 高光 + 内外阴影。
 */
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    shape: Shape,
    surfaceColor: Color = Color.Transparent,
    blurRadius: Dp = 18.dp,
    refraction: Boolean = false,
    saturation: Float = 1.30f,
    refractionHeight: Dp = 16.dp,
    refractionAmount: Dp = 28.dp,
    highlightEnabled: Boolean = true
): Modifier =
    // ── 跨机型降级（API < 31 无 RenderEffect）──────────────────────────
    // KMPLiquidGlass 的 blur 在低版本静默 no-op，backdrop 会以未模糊的
    // 原始内容透出（= 清晰玻璃，毛玻璃质感丢失）。降级为「加厚半透明
    // 基底 + 柔和透光渐变 + 噪点」，观感接近磨砂而不是消失。
    if (!supportsRealtimeBlur) {
        frostedGlassFallback(shape = shape, surfaceColor = surfaceColor)
    } else drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        colorControls(
            brightness = 0.04f,
            contrast = 1.02f,
            saturation = saturation
        )
        vibrancy()
        blur(radius = blurRadius.toPx())
        if (refraction) {
            lens(
                refractionHeight = refractionHeight.toPx(),
                refractionAmount = refractionAmount.toPx(),
                depthEffect = true,
                chromaticAberration = false
            )
        }
    },
    highlight = {
        if (!highlightEnabled) null else Highlight(
            width = 1.2.dp,
            blurRadius = 1.dp,
            alpha = 0.8f,
            style = HighlightStyle.Default
        )
    },
    shadow = { Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.16f)) },
    innerShadow = { InnerShadow(radius = 5.dp, color = Color.Black.copy(alpha = 0.10f)) },
    onDrawSurface = { drawRect(surfaceColor) }
)

/** DrawScope 小工具：绘制主题化渐变（供 CanvasBackdrop 使用）。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThemeGradient(
    surface: Color,
    primary: Color,
    secondary: Color
) {
    drawRect(surface)
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                primary.copy(alpha = 0.14f),
                Color.Transparent,
                secondary.copy(alpha = 0.10f),
                primary.copy(alpha = 0.12f)
            )
        )
    )
}

/**
 * 预烘焙路径的效果链：必须与 [Modifier.liquidGlass] 的实时链逐参数一致，
 * 这样烘焙位图与实时回退的输出才完全相同。lambda 需被 remember 保持实例稳定。
 */
@Composable
fun rememberGlassFxChain(blurRadiusPx: Float): BackdropEffectScope.() -> Unit =
    remember(blurRadiusPx) {
        {
            colorControls(
                brightness = 0.04f,
                contrast = 1.02f,
                saturation = 1.30f
            )
            vibrancy()
            blur(radius = blurRadiusPx)
        }
    }

/**
 * 玻璃卡静态采样：效果已整屏预烘焙进 [PreBlurredBackdrop] 的位图，
 * 此处效果链仅复刻 blur 造成的外扩（padding），保证采样窗口与实时路径一致。
 * 位图未就绪时 backdrop 内部自动回退实时路径，视觉与旧行为一致。
 */
fun Modifier.liquidGlassStatic(
    backdrop: PreBlurredBackdrop,
    shape: Shape,
    surfaceColor: Color,
    blurRadiusPx: Float
): Modifier =
    // ── 跨机型降级（API < 31 无 RenderEffect）：同 [liquidGlass] ──
    if (!supportsRealtimeBlur) {
        frostedGlassFallback(shape = shape, surfaceColor = surfaceColor)
    } else drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            blurRadiusPx > padding
        ) {
            padding = blurRadiusPx
        }
    },
    highlight = {
        Highlight(
            width = 1.2.dp,
            blurRadius = 1.dp,
            alpha = 0.8f,
            style = HighlightStyle.Default
        )
    },
    shadow = { Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.16f)) },
    innerShadow = { InnerShadow(radius = 5.dp, color = Color.Black.copy(alpha = 0.10f)) },
    onDrawSurface = { drawRect(surfaceColor) }
)

private var grainBitmap: Bitmap? = null

private fun createGrainBitmap(): Bitmap {
    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    val rnd = java.util.Random(42)
    for (x in 0 until 64) {
        for (y in 0 until 64) {
            val v = 128 + rnd.nextInt(56) - 28
            bmp.setPixel(x, y, android.graphics.Color.rgb(v, v, v))
        }
    }
    return bmp
}

/** 极细噪点纹理（film grain），打破纯数字模糊的塑料感。 */
fun Modifier.filmGrain(alpha: Float = 0.04f): Modifier = this.drawWithCache {
    val bmp = grainBitmap ?: createGrainBitmap().also { grainBitmap = it }
    val shader = ImageShader(
        image = bmp.asImageBitmap(),
        tileModeX = TileMode.Repeated,
        tileModeY = TileMode.Repeated
    )
    val brush = ShaderBrush(shader)
    onDrawBehind {
        drawRect(
            brush = brush,
            alpha = alpha,
            blendMode = BlendMode.Overlay
        )
    }
}

/** 沿边缘一圈的低饱和虹彩渐变描边（sweep 渐变，亚克力立牌折光）。 */
fun Modifier.iridescentBorder(
    shape: Shape,
    colors: List<Color>,
    width: Dp = 2.dp,
    alpha: Float = 0.22f
): Modifier = this.drawWithCache {
    val brush = Brush.sweepGradient(
        colors = colors.map { it.copy(alpha = alpha) },
        center = Offset(size.width / 2f, size.height / 2f)
    )
    onDrawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        when (outline) {
            is Outline.Rounded -> drawRoundRect(
                brush = brush,
                cornerRadius = outline.roundRect.topLeftCornerRadius,
                style = Stroke(width.toPx())
            )
            is Outline.Rectangle -> drawRect(
                brush = brush,
                style = Stroke(width.toPx())
            )
            else -> Unit
        }
    }
}

/**
 * 物理厚度倒角高光（Inner Bevel Lighting）：
 * 左上受光面珍珠白柔光，右下背光面暗调接触线，赋予玻璃卡片真实的毫米级切角晶体厚度感。
 */
fun Modifier.crystalInnerBevel(
    shape: Shape,
    width: Dp = 1.dp
): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val lightBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.35f),
            Color.White.copy(alpha = 0.12f),
            Color.Transparent,
            Color.Black.copy(alpha = 0.08f)
        ),
        start = Offset(0f, 0f),
        end = Offset(size.width, size.height)
    )
    onDrawBehind {
        when (outline) {
            is Outline.Rounded -> drawRoundRect(
                brush = lightBrush,
                cornerRadius = outline.roundRect.topLeftCornerRadius,
                style = Stroke(width.toPx())
            )
            is Outline.Rectangle -> drawRect(
                brush = lightBrush,
                style = Stroke(width.toPx())
            )
            else -> Unit
        }
    }
}

/** 径向渐变背景遮罩：中心（弹窗位置）亮、四周暗，模拟聚光灯打在立牌上。 */
fun Modifier.radialGlassScrim(): Modifier = this.drawBehind {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.25f),
                Color.Black.copy(alpha = PullOutConfig.SCRIM)
            ),
            center = Offset(size.width * 0.5f, size.height * 0.78f),
            radius = size.maxDimension * 0.95f
        )
    )
}

/** 从主题强调色推导虹彩边缘三色（同色系邻近色，不引入无关色相）。 */
@Composable
fun rememberIridescentColors(): List<Color> {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    return remember(primary, secondary) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(primary.toArgb(), hsv)
        val hue = hsv[0]
        val warm = Color.hsv((hue + 28f) % 360f, 0.45f, 1f)
        val cool = Color.hsv((hue - 28f + 360f) % 360f, 0.42f, 1f)
        listOf(primary, warm, secondary, cool, primary)
    }
}

/**
 * 顶级水晶棱镜多段色散（模拟施华洛世奇光学晶体 / VisionOS 玻璃边缘的分光色散）。
 * 色散序列：主色 -> 晨曦金 -> 珍珠白高光 -> 翡翠水绿 -> 薰衣草紫 -> 主色
 */
@Composable
fun rememberCrystalPrismColors(): List<Color> {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    return remember(primary, secondary) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(primary.toArgb(), hsv)
        val hue = hsv[0]
        val warmGold = Color.hsv((hue + 32f) % 360f, 0.40f, 1f)
        val pearlSheen = Color(0xFFEAF5F8)
        val coolViolet = Color.hsv((hue - 32f + 360f) % 360f, 0.38f, 1f)
        listOf(primary, warmGold, pearlSheen, secondary, coolViolet, primary)
    }
}

