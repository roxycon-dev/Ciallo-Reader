package com.example.ui.glasskit

import com.example.ui.components.pullOutClickable
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.ui.components.LocalGlassBackdrop
import com.example.ui.components.LocalRenderQuality
import com.example.ui.components.liquidGlass
import com.example.ui.components.supportsRealtimeBlur
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.lens
import com.kashif_e.backdrop.effects.vibrancy
import com.kashif_e.backdrop.highlight.Highlight
import com.kashif_e.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.collectLatest
import me.trishiraj.shadowglow.consistentShadow

/* ══════════════════════════════════════════════════════════════════════════
 * GlassKit —— 与项目既有 GlassCard / liquidGlass **完全并行** 的第二套玻璃
 *
 * 为什么另起一套而不是改原有的：
 *  · 原有那套（com.example.ui.components.GlassCard / liquidGlass）被全 App 几十处
 *    调用点依赖，改动即全局回归；本次只服务「书源管理页」与「搜索历史卡」两处，
 *    因此独立成包，原有实现一行不动。
 *  · 本套使用 backdrop 库（com.kashif_e.backdrop，已 vendored 在 :backdrop 模块）
 *    的**实时图层录制**（layerBackdrop + drawBackdrop）：玻璃采样的是它背后真实
 *    渲染的内容，而不是预先模糊好的位图；并在 GPU 上叠加 vibrancy / blur / lens 折射。
 *
 * 与既有那套的差别（可感知的）：
 *  1. 形状是**连续曲率圆角**（squircle），不是普通圆角矩形；
 *  2. 边缘有真正的**透镜折射**（lens，API 33+，低版本库自动跳过）+ 方向性高光；
 *  3. 着色只用中性白 / 中性深，**不使用强调色**，也不使用渐变着色；
 *  4. 轮廓只有库自带高光这一圈，不再叠第二圈描边。
 *
 * ══════════════════════════════════════════════════════════════════════════ */

object GlassTokens {
    val ManagementTitleHeight = 152.dp
    /** 大卡圆角（连续曲率）。 */
    val Radius = 24.dp
    /** 头像 / 输入框等小元素。 */
    val InnerRadius = 12.dp
    /** 快捷入口的淡染图标底。 */
    val IconRadius = 10.dp
    /** 玻璃模糊半径。 */
    val Blur = 16.dp
    /**
     * 轻量档模糊（用于"正在做尺寸/显隐动画"的卡片）。
     *
     * 展开/收起动画期间卡片每帧尺寸都在变，`drawBackdrop` 每帧都要重跑一遍
     * blur + vibrancy + lens 三个 shader（lens 是逐像素 SDF 的 RuntimeShader，最贵），
     * 与输入法动画撞在同一帧就是掉帧的根源。动画期间降到只有 blur(8)，
     * 静止后再切回完整效果 —— 静止态的观感才需要折射。
     */
    val BlurLightweight = 8.dp
    /** 折射作用宽度（克制）。 */
    val LensHeight = 14.dp
    /** 折射强度（过大会变成哈哈镜）。 */
    val LensAmount = 22.dp

    /**
     * 着色：中性白 / 中性深，**不用强调色、不用渐变**。
     *
     * 数值是按「最坏壁纸」反推出来的（不是拍脑袋）：
     * - 亮色：白 22%。最坏情况 = 纯黑壁纸 → 卡片有效亮度
     *   0.22 + 0.78×0.16 ≈ 0.345 → onSurface（近黑）对比 ≈ 6.1:1 ✓
     * - 深色：**不是**文档里那个"白 8%" —— 深色下白字压在透亮玻璃上，
     *   最坏情况（纯白壁纸）对比只有 ~2.7:1，整页糊成一片。
     *   改成 `surface` 中性深 0.78（iOS 深色 materialDark 同样是"暗玻璃"）：
     *   最坏情况卡片亮度 ≈ 0.17 → 白字对比 ≈ 4.7:1 ✓
     *
     * 代价：深色下通透感弱于亮色。可读性优先于通透，若要更透就把下面两个数
     * 一起往下调，但深色主文字会先掉到 4.5:1 以下。
     */
    const val TintLight = 0.22f
    const val TintDark = 0.78f
    /** 降低透明度 / 高对比度开启时的着色（几乎不透明）。 */
    const val TintReduce = 0.92f

    /** 壁纸全局遮罩（画在**录制层内**，所以玻璃采样到的是已压暗的背景）。 */
    const val PageOverlayLight = 0.16f
    const val PageOverlayDark = 0.40f

    /** 顶部渐变遮罩（保护大标题与返回箭头）。 */
    const val TopGradientAlpha = 0.75f

    val RowMinHeight = 60.dp
    val ScreenPadding = 16.dp
    val CardGap = 12.dp
    val ChipHeight = 26.dp
    /** 导入行（虚线）圆角。 */
    val PillRadius = 20.dp
    /** 折叠顶栏高度（不含状态栏，状态栏由调用方补）。 */
    val TopBarHeight = 64.dp
    /** 折叠顶栏的玻璃模糊（比卡片弱，只是薄薄一层）。 */
    val TopBarBlur = 8.dp
    /** 细分割线。 */
    val Hairline = 0.5.dp
}

/* ── 连续曲率圆角（squircle）───────────────────────────────────────────── */

/**
 * 连续曲率圆角矩形（超椭圆近似）。
 *
 * ⚠️ 必须继承 [CornerBasedShape]：backdrop 库的 `lens()` 是把 shape 强转成
 * CornerBasedShape 取四角半径来算 SDF 的（见 `effects/Lens.kt`），
 * 传一个普通 Shape 会直接抛 `UnsupportedOperationException`。
 *
 * 轮廓本身改用三段贝塞尔拟合超椭圆：直线段更长、拐角处曲率连续，
 * 观感比 `RoundedCornerShape` 更接近 iOS。
 */
class SquircleShape(corner: CornerSize) : CornerBasedShape(corner, corner, corner, corner) {

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection
    ): Outline {
        val max = minOf(size.width, size.height) / 2f
        val r = minOf(topStart, topEnd, bottomEnd, bottomStart, max)
        return Outline.Generic(squirclePath(size, r))
    }

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize
    ): CornerBasedShape = SquircleShape(topStart)
}

/** 卡片统一形状：24dp 连续曲率。 */
val GlassCardShape: CornerBasedShape get() = SquircleShape(CornerSize(GlassTokens.Radius))

private fun squirclePath(size: Size, r: Float): Path = Path().apply {
    val w = size.width
    val h = size.height
    if (r <= 0f) {
        addRect(Rect(0f, 0f, w, h))
        return@apply
    }
    // 控制点比例：0.5522 = 正圆；取 0.72 让曲线更"饱满"，接近超椭圆的连续曲率
    val k = r * 0.72f
    moveTo(r, 0f)
    lineTo(w - r, 0f)
    cubicTo(w - r + k, 0f, w, r - k, w, r)
    lineTo(w, h - r)
    cubicTo(w, h - r + k, w - r + k, h, w - r, h)
    lineTo(r, h)
    cubicTo(r - k, h, 0f, h - r + k, 0f, h - r)
    lineTo(0f, r)
    cubicTo(0f, r - k, r - k, 0f, r, 0f)
    close()
}

/* ── 页面级宿主：录制「已加遮罩的背景」 ───────────────────────────────── */

/** 本套玻璃的采样源。未提供的页面自动回退到全局壁纸 backdrop。 */
val LocalGlassKitBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 玻璃宿主：铺一层「壁纸采样模糊 + 全局遮罩 + 顶部渐变」，并把这一整层
 * **录制**为 [LocalGlassKitBackdrop] 供卡片采样。
 *
 * 关键点（也是与上一版最大的区别）：
 * - 遮罩与渐变画在**被录制的层里**，因此卡片采样到的是"已经压暗并保护过标题的背景"，
 *   而不是原始壁纸 —— 卡内文字的对比度不再取决于用户选了哪张壁纸。
 * - 卡片必须作为这层的**兄弟节点**（见 [content]），放进被录制的层里会采样到自己（递归）。
 *
 * @param topGradientHeight 顶部渐变高度（= 状态栏 + 大标题区 + 24）。
 */
@Composable
fun GlassKitHost(
    topGradientHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val global = LocalGlassBackdrop.current
    val quality = LocalRenderQuality.current
    val pageBackdrop = rememberLayerBackdrop()
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val overlay = if (dark) {
        Color.Black.copy(alpha = GlassTokens.PageOverlayDark)
    } else {
        Color.White.copy(alpha = GlassTokens.PageOverlayLight)
    }
    val bg = cs.background
    val realtime = global != null && quality.realtimeGlass && supportsRealtimeBlur

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // ⚠️ 顺序不能反：layerBackdrop 在外层，liquidGlass 在内层。
                // LayerBackdropNode.draw() 会先 drawContent()（把内层 liquidGlass 画出来），
                // 再 recordLayer { onDraw() } 把同样内容录进纹理 —— 内层才录得进去。
                .layerBackdrop(pageBackdrop)
                .then(
                    if (realtime) {
                        Modifier.liquidGlass(
                            backdrop = global!!,
                            shape = RectangleShape,
                            surfaceColor = overlay,
                            blurRadius = GlassTokens.Blur,
                            saturation = 1.15f
                        )
                    } else {
                        Modifier.background(overlay)
                    }
                )
        ) {
            // 顶部渐变作为**子节点**才会被一起录制进 pageBackdrop
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topGradientHeight)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                bg.copy(alpha = GlassTokens.TopGradientAlpha),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
        CompositionLocalProvider(LocalGlassKitBackdrop provides pageBackdrop) {
            content()
        }
    }
}

/* ── 玻璃卡 ───────────────────────────────────────────────────────────── */

/**
 * 系统「降低透明度 / 高对比度」是否开启（开启后着色提浓、去掉折射）。
 *
 * 这两个设置项没有稳定的 SDK 常量，按 key 读取；取不到就当关闭。
 */
@Composable
fun rememberReduceTransparency(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        fun flag(key: String): Boolean = try {
            Settings.Secure.getString(context.contentResolver, key)?.toFloatOrNull()
                ?.let { it >= 0.5f } ?: false
        } catch (_: Throwable) {
            false
        }
        flag("reduce_transparency") || flag("high_text_contrast_enabled")
    }
}

/**
 * 玻璃卡：[shape] 默认 24dp 连续曲率，中心通透、边缘折射 + 左上方向性高光。
 *
 * 卡片内部一律**不再**有第二层玻璃、渐变或描边（玻璃套玻璃会让边缘出现两圈轮廓）。
 *
 * @param interactiveHighlight 可点击卡片可开启「高光跟随手指」。库的 [Highlight] 只支持
 *   固定方向，无法指定位置，所以这里用 `onDrawFront` 自己画一圈柔和径向光；
 *   列表大卡（分组卡 / 搜索历史卡）不要开 —— 会和滚动手势抢按压。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassKitCard(
    modifier: Modifier = Modifier,
    shape: CornerBasedShape = GlassCardShape,
    onClick: (() -> Unit)? = null,
    interactiveHighlight: Boolean = false,
    /**
     * 轻量档：**只跑 blur(8)**，去掉 vibrancy 与 lens。
     *
     * 用于卡片正在做显隐/尺寸动画的那些帧 —— 此时每帧都要重跑 shader，
     * 而动画期的折射没人看得清，静止后自动切回完整效果。
     */
    lightweight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    if (LocalRenderQuality.current.minimal) {
        Column(modifier.clip(shape).background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.pullOutClickable(onClick = onClick) else Modifier), content = content)
        return
    }
    val kitBackdrop = LocalGlassKitBackdrop.current
    val fallbackBackdrop = LocalGlassBackdrop.current
    val backdrop = kitBackdrop ?: fallbackBackdrop
    val quality = LocalRenderQuality.current
    val reduce = rememberReduceTransparency()
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f

    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "glass_press"
    )
    var pressPoint by remember { mutableStateOf(Offset.Unspecified) }
    if (interactiveHighlight) {
        LaunchedEffect(source) {
            source.interactions.collectLatest { interaction ->
                pressPoint = when (interaction) {
                    is PressInteraction.Press -> interaction.pressPosition
                    else -> Offset.Unspecified
                }
            }
        }
    }

    val tint = if (reduce) {
        if (dark) cs.surface.copy(alpha = GlassTokens.TintReduce)
        else Color.White.copy(alpha = GlassTokens.TintReduce)
    } else if (dark) {
        cs.surface.copy(alpha = GlassTokens.TintDark)
    } else {
        Color.White.copy(alpha = GlassTokens.TintLight)
    }
    // API 31 以下不支持模糊：回退为近乎不透明的纯色底（文档 §9）
    val solidTint = if (dark) cs.surface.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.78f)

    val overlay = com.example.ui.components.LocalPaperOwner.current != null
    val realtime = backdrop != null && quality.realtimeGlass && supportsRealtimeBlur
    val glassModifier = if (realtime) {
        Modifier.drawBackdrop(
            backdrop = backdrop!!,
            shape = { shape },
            effects = {
                if (lightweight) {
                    // 动画中的帧：一个 shader 就够（lens 与 vibrancy 静止后再回来）
                    blur(GlassTokens.BlurLightweight.toPx())
                } else {
                    vibrancy()
                    blur(GlassTokens.Blur.toPx())
                    if (!reduce) {
                        lens(
                            refractionHeight = GlassTokens.LensHeight.toPx(),
                            refractionAmount = GlassTokens.LensAmount.toPx(),
                            depthEffect = true
                        )
                    }
                }
            },
            highlight = { if (overlay) null else Highlight.Default },
            shadow = { Shadow.Default },
            layerBlock = { scaleX = press; scaleY = press },
            onDrawSurface = { drawRect(tint) },
            onDrawFront = if (interactiveHighlight && !overlay) {
                {
                    val p = pressPoint
                    if (p != Offset.Unspecified) {
                        val outline = shape.createOutline(size, layoutDirection, this)
                        val path = Path().apply { addOutline(outline) }
                        clipPath(path) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
                                    center = p,
                                    radius = size.minDimension * 0.55f
                                ),
                                radius = size.minDimension * 0.55f,
                                center = p,
                                blendMode = BlendMode.Screen
                            )
                        }
                    }
                }
            } else null
        )
    } else {
        Modifier
            .consistentShadow(
                elevation = 2.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.12f)
            )
            .clip(shape)
            .background(solidTint)
            .graphicsLayer { scaleX = press; scaleY = press }
    }

    Column(
        modifier = modifier
            .then(glassModifier)
            .then(
                if (onClick != null) {
                    Modifier.pullOutClickable(
                        interactionSource = source,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            ),
        content = content
    )
}

/* ── 折叠顶栏玻璃条 ───────────────────────────────────────────────────── */

/**
 * 折叠后的顶栏玻璃条：只有 blur(8) + 轻着色，**不加 lens**（顶栏是薄片，折射会显脏），
 * 透明度随 [collapsedFraction] 淡入，底部一条 0.5dp 分割线。
 *
 * 顶栏区域因此不会是实色块，也不会有硬切的底边。
 */
@Composable
fun GlassTopBarStrip(
    collapsedFraction: Float,
    modifier: Modifier = Modifier
) {
    if (LocalRenderQuality.current.minimal) {
        Box(modifier.background(androidx.compose.material3.MaterialTheme.colorScheme.surface))
        return
    }
    val kitBackdrop = LocalGlassKitBackdrop.current
    val fallbackBackdrop = LocalGlassBackdrop.current
    val backdrop = kitBackdrop ?: fallbackBackdrop
    val quality = LocalRenderQuality.current
    val reduce = rememberReduceTransparency()
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val tint = if (reduce) {
        cs.background.copy(alpha = 0.92f)
    } else {
        cs.background.copy(alpha = 0.60f)
    }
    val realtime = backdrop != null && quality.realtimeGlass && supportsRealtimeBlur

    Box(
        modifier = modifier.then(
            if (realtime) {
                Modifier.drawBackdrop(
                    backdrop = backdrop!!,
                    shape = { RectangleShape },
                    effects = { blur(GlassTokens.TopBarBlur.toPx()) },
                    layerBlock = { alpha = collapsedFraction },
                    onDrawSurface = { drawRect(tint) }
                )
            } else {
                Modifier.background(tint.copy(alpha = tint.alpha * collapsedFraction))
            }
        )
    ) {
        // 底部分割线：随折叠进度淡入，避免展开时顶栏出现一条硬边
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(GlassTokens.Hairline)
                .background(cs.onSurface.copy(alpha = 0.10f * collapsedFraction))
        )
    }
}
