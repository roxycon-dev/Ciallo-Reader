package com.kashif_e.backdrop.backdrops

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.BackdropEffectScope
import com.kashif_e.backdrop.BackdropEffectScopeImpl
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 性能优化（视觉零变化）：静态背景的"整屏只模糊一次"方案。
 *
 * 原理：blur 与 colorControls/vibrancy 都是平移不变算子，对静态源先整屏执行一次效果链并
 * 读回位图后，消费方按平移量贴图采样，与"每帧对窗口区域实时执行效果链"逐像素一致
 * （边界处理同为透明补零；差异仅存在于被卡片裁剪掉的 padding 环上的高斯尾项，不可见）。
 *
 * 安全策略：
 *  - 位图未就绪/构建失败/探针检测到 RenderEffect 未参与快照时，自动回退到源 backdrop
 *    的实时路径（与旧行为完全相同），因此最坏情况等于现状。
 *  - [rememberPreBlurredBackdrop] 在 buildKey 变化时先清空位图回退实时，等待
 *    settleDelayMs（跨过主题色 600ms 过渡动画）后再烘焙，过渡期间与现状一致。
 */
@Stable
class PreBlurredBackdrop internal constructor(
    internal val source: LayerBackdrop
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    internal var bitmap: ImageBitmap? by mutableStateOf(null)

    @Volatile
    internal var enabled: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val bmp = bitmap
        val consumer = coordinates
        if (!enabled || bmp == null || consumer == null || layerBlock != null) {
            with(source) { drawBackdrop(density, coordinates, layerBlock) }
            return
        }
        val srcCoords = source.layerCoordinates
        if (srcCoords == null) {
            with(source) { drawBackdrop(density, coordinates, layerBlock) }
            return
        }
        val offset = try {
            srcCoords.localPositionOf(consumer)
        } catch (_: Exception) {
            consumer.positionInWindow() - srcCoords.positionInWindow()
        }
        withTransform({ translate(-offset.x, -offset.y) }) {
            drawImage(bmp)
        }
    }

    /**
     * 把源内容经 effects 链处理后整屏读回位图。失败返回 false（保持实时回退）。
     */
    internal suspend fun bake(
        workLayer: GraphicsLayer,
        density: Density,
        effects: BackdropEffectScope.() -> Unit
    ): Boolean {
        if (!PreBlurSupport.supported || !enabled) return false
        if (!PreBlurSupport.verified) {
            PreBlurSupport.supported = verifySnapshotEffects(workLayer, density)
            PreBlurSupport.verified = true
            workLayer.renderEffect = null
            if (!PreBlurSupport.supported) {
                enabled = false
                return false
            }
        }
        val srcCoords = source.layerCoordinates ?: return false
        val w = srcCoords.size.width.toInt()
        val h = srcCoords.size.height.toInt()
        if (w <= 0 || h <= 0) return false

        val scope = object : BackdropEffectScopeImpl() {
            override val shape: Shape get() = RectangleShape
        }
        scope.density = density.density
        scope.fontScale = density.fontScale
        scope.size = Size(w.toFloat(), h.toFloat())
        scope.layoutDirection = LayoutDirection.Ltr
        scope.apply(effects)

        try {
            workLayer.record(density, LayoutDirection.Ltr, IntSize(w, h)) {
                val c = source.layerCoordinates ?: return@record
                with(source) { drawBackdrop(density, c, null) }
            }
            workLayer.renderEffect = scope.renderEffect?.asComposeRenderEffect()
            val bmp = workLayer.toImageBitmap()
            currentCoroutineContext().ensureActive()
            if (bmp.width <= 0 || bmp.height <= 0) return false
            // A fold/resize can invalidate coordinates while GPU readback is suspended.
            val current = source.layerCoordinates
            if (current !== srcCoords || !srcCoords.isAttached || srcCoords.size != IntSize(w, h)) return false
            bitmap = bmp
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } finally {
            workLayer.renderEffect = null
        }
    }
}

internal object PreBlurSupport {
    @Volatile var verified: Boolean = false
    @Volatile var supported: Boolean = true
}

/**
 * 探针自检：小画布中央放一个白色小块并施加 DECAL 大半径模糊，
 * 若 RenderEffect 参与了 toImageBitmap 光栅化，画布角落会出现可测的白色溢出。
 * 检测不到则说明该机型快照路径不含效果 → 永久走实时回退。
 */
private suspend fun verifySnapshotEffects(layer: GraphicsLayer, density: Density): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    return try {
        layer.record(density, LayoutDirection.Ltr, IntSize(40, 40)) {
            drawRect(color = Color.Transparent, size = Size(40f, 40f))
            drawRect(
                color = Color.White,
                topLeft = Offset(16f, 16f),
                size = Size(8f, 8f)
            )
        }
        layer.renderEffect = android.graphics.RenderEffect
            .createBlurEffect(16f, 16f, android.graphics.Shader.TileMode.DECAL)
            .asComposeRenderEffect()
        val bmp = layer.toImageBitmap()
        currentCoroutineContext().ensureActive()
        val pm = bmp.toPixelMap()
        pm[2, 2].alpha > 0.05f
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    } finally {
        layer.renderEffect = null
    }
}

/**
 * 创建并维护一个预烘焙 backdrop。
 *
 * @param buildKey 背景/主题/尺寸/密度的离散组合键，任一变化即清空回退并在稳定后重烘焙
 * @param effects 与实时路径完全一致的效果链 lambda（调用方需 remember 保持实例稳定）
 * @param settleDelayMs 键变化后的等待时长，用于跨过主题色弹簧过渡动画
 */
@Composable
fun rememberPreBlurredBackdrop(
    source: LayerBackdrop,
    buildKey: Any?,
    effects: BackdropEffectScope.() -> Unit,
    settleDelayMs: Long = 800L
): PreBlurredBackdrop {
    val backdrop = remember(source) { PreBlurredBackdrop(source) }
    val workLayer = rememberGraphicsLayer()
    val density = LocalDensity.current
    LaunchedEffect(backdrop, workLayer, buildKey, effects, density) {
        backdrop.bitmap = null // 立即回退实时路径，避免过渡期间显示旧背景
        if (settleDelayMs > 0) delay(settleDelayMs)
        backdrop.bake(workLayer, density, effects)
    }
    return backdrop
}
