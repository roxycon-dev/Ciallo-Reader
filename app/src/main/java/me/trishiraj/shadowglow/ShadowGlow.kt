/*
 * ShadowGlow —— StarkDroid/compose-ShadowGlow 的适配移植（开源仓库，经用户确认可用）。
 *
 * 本文件将原库基于 `Modifier.composed` 的两个入口改写为 @Composable 扩展函数
 * （新版 Compose 中 composed 已移除），绘制逻辑（辉光 / 渐变辉光 / 呼吸动画 /
 * 光尾巡游 / 陀螺仪视差）与原实现一一对应。
 *
 * ⚠️ 绘制内核已从 BlurMaskFilter 迁移到自研的「确定性 CPU 模糊」
 * （见 ShadowBlurEngine.kt）。原因：`Paint.setMaskFilter()` 在硬件加速画布上
 * 不受支持，会被静默忽略，导致模糊形状退化成实心硬边色块。
 */
package me.trishiraj.shadowglow

import android.graphics.RectF
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 纯色辉光阴影。
 *
 * @param blurRadius 模糊半径（Dp）。内部按 BlurMaskFilter 的半径→σ 换算
 *   （σ ≈ 0.577·r）走确定性 CPU 模糊，任何机型结果一致。
 * @param blurStyle 保留参数以兼容原库签名；当前 CPU 模糊只有 NORMAL 语义。
 */
@Composable
fun Modifier.shadowGlow(
    color: Color = Color.Black.copy(alpha = 0.4f),
    borderRadius: Dp = 0.dp,
    blurRadius: Dp = 8.dp,
    offsetX: Dp = 0.dp,
    offsetY: Dp = 4.dp,
    spread: Dp = 0.dp,
    blurStyle: ShadowBlurStyle = ShadowBlurStyle.NORMAL,
    enableGyroParallax: Boolean = false,
    parallaxSensitivity: Dp = 4.dp,
    enableBreathingEffect: Boolean = false,
    breathingEffectIntensity: Dp = 4.dp,
    breathingDurationMillis: Int = 1500,
    enableGlowTrail: Boolean = false,
    glowTrailWidth: Dp = 8.dp,
    glowTrailBlurRadius: Dp = 16.dp,
    glowTrailLengthDegrees: Float = 60f,
    glowTrailDurationMillis: Int = 2500,
    glowTrailClockwise: Boolean = true,
    glowTrailAlpha: Float = 1f
): Modifier {
    val glowTrailProgress = if (enableGlowTrail) {
        rememberGlowTrailProgess(true, glowTrailClockwise, glowTrailDurationMillis)
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val parallaxState = if (enableGyroParallax) {
        rememberGyroParallaxState(parallaxSensitivity)
    } else {
        null
    }

    val breathingPx = if (enableBreathingEffect) {
        rememberAnimatedBreathingValue(true, breathingEffectIntensity, breathingDurationMillis)
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    // 光尾用的轮廓：参数稳定时复用同一个 Shape 实例，避免每帧在 draw 里 new 一个
    // RoundedCornerShape（原实现每帧分配，属于纯浪费）。
    val trailShape = remember(borderRadius) { RoundedCornerShape(borderRadius) }

    return this.drawBehind {
        val spreadPx = spread.toPx()
        val breathingValue = breathingPx.value
        val totalBlurRadiusPx = (blurRadius.toPx() + breathingValue).coerceAtLeast(0f)

        val baseOffsetXPx = offsetX.toPx()
        val baseOffsetYPx = offsetY.toPx()
        val shadowBorderRadiusPx = borderRadius.toPx()

        val dynamicOffsetXPx = parallaxState?.value?.first ?: 0f
        val dynamicOffsetYPx = parallaxState?.value?.second ?: 0f

        val totalOffsetXPx = baseOffsetXPx + dynamicOffsetXPx
        val totalOffsetYPx = baseOffsetYPx + dynamicOffsetYPx

        val left = -spreadPx + totalOffsetXPx
        val top = -spreadPx + totalOffsetYPx
        val right = size.width + spreadPx + totalOffsetXPx
        val bottom = size.height + spreadPx + totalOffsetYPx

        drawDeterministicGlowRoundRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            cornerRadiusPx = shadowBorderRadiusPx,
            color = color,
            sigmaPx = blurRadiusToSigma(totalBlurRadiusPx),
            dx = 0f,
            dy = 0f,
            alpha = 1f,
            bucket = ShadowCacheBucket.ANIMATED,
        )

        if (enableGlowTrail) {
            drawGlowTrailAlongShape(
                shape = trailShape,
                progress = glowTrailProgress.value,
                trailFraction = glowTrailLengthDegrees / 360f,
                color = color,
                strokeWidthPx = glowTrailWidth.toPx(),
                blurRadiusPx = glowTrailBlurRadius.toPx(),
                alpha = glowTrailAlpha
            )
        }
    }
}

/**
 * 渐变辉光阴影（当前无调用点）。
 *
 * ⚠️ 说明：CPU 模糊的掩码是「单色 tint」，无法直接承载线性渐变。
 * 该重载当前**无任何调用点**，为消除硬边风险，暂以渐变首色 `gradientColors.first()`
 * 作为 tint 色渲染（保留签名，后续如启用需扩展为渐变掩码）。
 */
@Composable
fun Modifier.shadowGlow(
    gradientColors: List<Color>,
    gradientStartFactorX: Float = 0f,
    gradientStartFactorY: Float = 0f,
    gradientEndFactorX: Float = 1f,
    gradientEndFactorY: Float = 1f,
    gradientColorStops: List<Float>? = null,
    borderRadius: Dp = 0.dp,
    blurRadius: Dp = 8.dp,
    offsetX: Dp = 0.dp,
    offsetY: Dp = 4.dp,
    spread: Dp = 0.dp,
    alpha: Float = 1.0f,
    blurStyle: ShadowBlurStyle = ShadowBlurStyle.NORMAL,
    enableGyroParallax: Boolean = false,
    parallaxSensitivity: Dp = 4.dp,
    enableBreathingEffect: Boolean = false,
    breathingEffectIntensity: Dp = 4.dp,
    breathingDurationMillis: Int = 1500,
    enableGlowTrail: Boolean = false,
    glowTrailWidth: Dp = 8.dp,
    glowTrailBlurRadius: Dp = 16.dp,
    glowTrailLengthDegrees: Float = 60f,
    glowTrailDurationMillis: Int = 2500,
    glowTrailClockwise: Boolean = true,
    glowTrailAlpha: Float = 1f
): Modifier {
    val parallaxState = if (enableGyroParallax) {
        rememberGyroParallaxState(parallaxSensitivity)
    } else {
        null
    }

    val breathingPx = if (enableBreathingEffect) {
        rememberAnimatedBreathingValue(true, breathingEffectIntensity, breathingDurationMillis)
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val glowTrailProgress = if (enableGlowTrail) {
        rememberGlowTrailProgess(true, glowTrailClockwise, glowTrailDurationMillis)
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val trailShape = remember(borderRadius) { RoundedCornerShape(borderRadius) }

    return this.drawBehind {
        if (gradientColors.isEmpty() || alpha == 0f) return@drawBehind
        val spreadPx = spread.toPx()
        val totalBlurRadiusPx = (blurRadius.toPx() + breathingPx.value).coerceAtLeast(0f)

        val baseOffsetXPx = offsetX.toPx()
        val baseOffsetYPx = offsetY.toPx()
        val shadowBorderRadiusPx = borderRadius.toPx()

        val dynamicOffsetXPx = parallaxState?.value?.first ?: 0f
        val dynamicOffsetYPx = parallaxState?.value?.second ?: 0f

        val totalOffsetXPx = baseOffsetXPx + dynamicOffsetXPx
        val totalOffsetYPx = baseOffsetYPx + dynamicOffsetYPx

        val left = -spreadPx + totalOffsetXPx
        val top = -spreadPx + totalOffsetYPx
        val right = size.width + spreadPx + totalOffsetXPx
        val bottom = size.height + spreadPx + totalOffsetYPx

        val tint = gradientColors.first()
        drawDeterministicGlowRoundRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            cornerRadiusPx = shadowBorderRadiusPx,
            color = tint,
            sigmaPx = blurRadiusToSigma(totalBlurRadiusPx),
            dx = 0f,
            dy = 0f,
            alpha = alpha,
            bucket = ShadowCacheBucket.ANIMATED,
        )

        if (enableGlowTrail) {
            drawGlowTrailAlongShape(
                shape = trailShape,
                progress = glowTrailProgress.value,
                trailFraction = glowTrailLengthDegrees / 360f,
                color = tint,
                strokeWidthPx = glowTrailWidth.toPx(),
                blurRadiusPx = glowTrailBlurRadius.toPx(),
                alpha = glowTrailAlpha
            )
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
 * 跨机型一致的 elevation 阴影（自绘版 / Deterministic Elevation Shadow）
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 为什么要替换 `Modifier.shadow` / `Surface(shadowElevation=…)` /
 * `graphicsLayer { shadowElevation = … }`：
 *
 *  这三者最终都落到 HWUI 的 `RenderNode.setElevation()`，由 **系统 libhwui**
 *  现场做「凸包细分 + 模糊」绘制。模糊半径、环境光阴影与聚光阴影的 alpha
 *  系数是 libhwui 内部常量，各 ROM（MIUI / OneUI / ColorOS / HarmonyOS）
 *  都有改写记录；软件渲染与个别省电模式下还有 ROM 直接不画投影。
 *  结果就是同一张卡在 A 机与 B 机上阴影浓淡、扩散范围肉眼可见地不一致。
 *
 * 本实现改为 **应用内 CPU 确定性模糊**（ShadowBlurEngine.kt：降采样掩码 +
 * 三次盒式模糊 ≈ 高斯 + 双线性放大 + LruCache）。纯数学运算，与 GPU/ROM
 * 无关 → 在所有机型上逐像素一致，且不依赖 RenderEffect（API 21+ 全可用）。
 *
 * ⚠️ 历史坑（务必保留注释）：上一版用 Skia `BlurMaskFilter` 自绘，因
 * 「硬件加速画布忽略 MaskFilter」，模糊失效→画成**实心硬边色块**，
 * 出现「大块纯色框」。当前实现不再触碰 MaskFilter，硬件加速下也必然模糊。
 *
 * 参数语义（对齐 `Modifier.shadow` 的实测观感）：
 *   ambient 层：无位移，σ = elevation × SHADOW_AMBIENT_BLUR_FACTOR，全向扩散
 *   spot   层：向下偏移 elevation × SHADOW_SPOT_OFFSET_FACTOR，
 *              σ = elevation × SHADOW_SPOT_BLUR_FACTOR
 * 两层 alpha 各乘一个 gain（见下方常量注释）。
 */

/** ambient（环境光）阴影的 σ 系数（σ / elevation）。 */
private const val SHADOW_AMBIENT_BLUR_FACTOR = 0.22f

/** spot（聚光）阴影的 σ 系数（σ / elevation）。 */
private const val SHADOW_SPOT_BLUR_FACTOR = 0.22f

/** spot（聚光）阴影的垂直偏移系数（× elevation，向下为正）。 */
private const val SHADOW_SPOT_OFFSET_FACTOR = 0.28f

/**
 * alpha 增益：HWUI 在基准机上把标称 alpha 画得比标称淡（libhwui 的
 * ambient/spot 系数很小），自绘版乘这个系数才能逼近「与改前一致」的观感。
 * 数值由像素级验收（`docs/shadow-consistency.md`）实测标定。
 */
private const val SHADOW_AMBIENT_ALPHA_GAIN = 0.10f
private const val SHADOW_SPOT_ALPHA_GAIN = 0.10f

/** BlurMaskFilter 半径 → 高斯 σ 的换算系数（Skia: σ ≈ 0.577·r + 0.5，忽略常数项）。 */
private const val BLUR_RADIUS_TO_SIGMA = 0.577f

/** 半径（px）→ σ（px）。 */
private fun blurRadiusToSigma(radiusPx: Float): Float =
    (radiusPx * BLUR_RADIUS_TO_SIGMA).coerceAtLeast(0f)

/**
 * 跨机型一致的 elevation 阴影：`Modifier.shadow(...)` 的自绘等价物。
 *
 * 与 `Modifier.shadow` 一样不影响布局、不裁剪内容；区别在于阴影由本模块
 * 用 CPU 确定性模糊画出，参数跨机型完全一致。
 *
 * ⚠️ 调用位置约定：请把本修饰符放在链中任何 `graphicsLayer { … }` /
 * `clip(…)` **之前**（即更外层）。`graphicsLayer` 在需要离屏合成（alpha<1、
 * 旋转等）时会把内容裁剪到节点边界，阴影画在里面会被切掉。
 *
 * @param elevation 原生语义的「海拔」，决定模糊半径与聚光偏移
 * @param shape 阴影轮廓（与 `Modifier.shadow` 的 shape 同义）
 * @param ambientColor 环境光阴影色（通常带 alpha）
 * @param spotColor 聚光阴影色（通常带 alpha）
 * @param offsetY 额外垂直偏移（在 elevation 自动偏移之上叠加）
 * @param alpha 整体透明度倍率，便于按压态等动态调淡
 */
@Composable
fun Modifier.consistentShadow(
    elevation: () -> Dp,
    shape: Shape,
    ambientColor: Color = Color.Black.copy(alpha = 0.08f),
    spotColor: Color = Color.Black.copy(alpha = 0.15f),
    offsetY: Dp = 0.dp,
    alpha: Float = 1f
): Modifier {
    if (alpha <= 0f) return this
    val clipShape = shape
    return drawWithCache {
        if (size.width <= 0f || size.height <= 0f) return@drawWithCache onDrawBehind { }
        // Elevation changes blur/offset, never the outline. Keep geometry outside
        // the animated read and resolve only when its exact Float value changes.
        val outline = shape.createOutline(size, layoutDirection, this)
        val shadowPath = outline.toAndroidPath()
        val bounds = RectF()
        @Suppress("DEPRECATION")
        shadowPath.computeBounds(bounds, true)
        val signature = outline.shadowOutlineSignature()
        val totalAlpha = alpha.coerceIn(0f, 1f)
        val ambientAlpha = ambientColor.alpha * SHADOW_AMBIENT_ALPHA_GAIN * totalAlpha
        val spotAlpha = spotColor.alpha * SHADOW_SPOT_ALPHA_GAIN * totalAlpha
        val ambientInk = if (ambientAlpha > 0.002f) ambientColor.copy(alpha = 1f) else ambientColor
        val spotInk = if (spotAlpha > 0.002f) spotColor.copy(alpha = 1f) else spotColor
        val baseOffsetY = offsetY.toPx()
        var lastElevation = Float.NaN
        var ambient: ResolvedBlurredShadow? = null
        var spot: ResolvedBlurredShadow? = null
        onDrawBehind {
            val e = elevation().toPx()
            if (e != lastElevation) {
                lastElevation = e
                ambient = if (e <= 0f || !(ambientAlpha > 0.002f)) null else resolveBlurredShadow(
                    path = shadowPath, bounds = bounds, signature = signature,
                    color = ambientInk, sigmaPx = e * SHADOW_AMBIENT_BLUR_FACTOR,
                    dx = 0f, dy = baseOffsetY, strokeWidthPx = 0f, style = Fill, alpha = ambientAlpha,
                )
                spot = if (e <= 0f || !(spotAlpha > 0.002f)) null else resolveBlurredShadow(
                    path = shadowPath, bounds = bounds, signature = signature,
                    color = spotInk, sigmaPx = e * SHADOW_SPOT_BLUR_FACTOR,
                    dx = 0f, dy = baseOffsetY + e * SHADOW_SPOT_OFFSET_FACTOR,
                    strokeWidthPx = 0f, style = Fill, alpha = spotAlpha,
                )
            }
            ambient?.let { drawResolvedBlurredShadow(it) }
            spot?.let { drawResolvedBlurredShadow(it) }
        }
    }.graphicsLayer {
        this.shape = clipShape
        clip = !(elevation() <= 0.dp)
    }
}

@Composable
fun Modifier.consistentShadow(
    elevation: Dp,
    shape: Shape,
    ambientColor: Color = Color.Black.copy(alpha = 0.08f),
    spotColor: Color = Color.Black.copy(alpha = 0.15f),
    offsetY: Dp = 0.dp,
    alpha: Float = 1f
): Modifier {
    // 空参数早退：避免在列表滚动场景为一个零尺寸阴影白白挂 draw 修饰符
    if (elevation <= 0.dp || alpha <= 0f) return this
    return this
        // ── 性能关键：用 drawWithCache 把「重活」移到组合期 ──────────────────
        // createOutline / toAndroidPath / computeBounds / 轮廓签名 / 掩码建位图
        // 全部只在 **size 或参数变化时**执行一次；绘制期只剩两次 drawImage（零分配）。
        // 旧实现放在 drawBehind 里 ⇒ 每帧 2× 路径运算 + 2× 建 key + 2× asImageBitmap，
        // 书架连续快滚时造成 p99 ≈ 3×、吞吐 −28% 的尾部回退（QA V5 实测）。
        .drawWithCache {
            val e = elevation.toPx()
            if (size.width <= 0f || size.height <= 0f || e <= 0f) {
                // 早退也返回合法的 DrawResult（空绘制），避免为无效尺寸挂真实绘制。
                return@drawWithCache onDrawBehind { }
            }

            val totalAlpha = alpha.coerceIn(0f, 1f)
            val ambientAlpha = ambientColor.alpha * SHADOW_AMBIENT_ALPHA_GAIN * totalAlpha
            val spotAlpha = spotColor.alpha * SHADOW_SPOT_ALPHA_GAIN * totalAlpha
            val baseOffsetY = offsetY.toPx()

            // 轮廓只解析一次：路径 / 外接矩形 / 值签名
            val outline = shape.createOutline(size, layoutDirection, this)
            val shadowPath = outline.toAndroidPath()
            val bounds = RectF()
            @Suppress("DEPRECATION")
            shadowPath.computeBounds(bounds, true)
            val signature = outline.shadowOutlineSignature()

            // 两层阴影也在组合期解析成「可直接贴的位图」
            val ambient = if (ambientAlpha > 0.002f) {
                resolveBlurredShadow(
                    path = shadowPath,
                    bounds = bounds,
                    signature = signature,
                    color = ambientColor.copy(alpha = 1f),
                    sigmaPx = e * SHADOW_AMBIENT_BLUR_FACTOR,
                    dx = 0f,
                    dy = baseOffsetY,
                    strokeWidthPx = 0f,
                    style = Fill,
                    alpha = ambientAlpha,
                )
            } else {
                null
            }
            val spot = if (spotAlpha > 0.002f) {
                resolveBlurredShadow(
                    path = shadowPath,
                    bounds = bounds,
                    signature = signature,
                    color = spotColor.copy(alpha = 1f),
                    sigmaPx = e * SHADOW_SPOT_BLUR_FACTOR,
                    dx = 0f,
                    dy = baseOffsetY + e * SHADOW_SPOT_OFFSET_FACTOR,
                    strokeWidthPx = 0f,
                    style = Fill,
                    alpha = spotAlpha,
                )
            } else {
                null
            }

            // 绘制期：只贴图（无分配、无路径运算）
            onDrawBehind {
                ambient?.let { drawResolvedBlurredShadow(it) }
                spot?.let { drawResolvedBlurredShadow(it) }
            }
        }
        // ⚠️ 关键：复刻 `Modifier.shadow(elevation, shape, clip = elevation > 0.dp)` 的裁剪语义。
        // 原实现（shadow / Surface(shadowElevation)）都会把**内容**裁剪到 shape，
        // 于是 GlassCard 里挂在阴影之后的 MAX 档 `maxCardAura`（整圈光晕都在卡片
        // 边界之外）被裁掉、默认不可见。若此处不裁剪，光晕会整圈外泄，MAX 档会出现
        // 「大块品牌色圆角光框」的观感回退。`clip()` 只作用于其内侧内容，
        // 不影响 `drawBehind` / `onDrawBehind` 画出的阴影本身。
        .clip(shape)
}
