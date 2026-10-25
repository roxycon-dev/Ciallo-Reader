package dev.liquidglass.compose.internal

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect
import dev.liquidglass.core.GlassUniforms
import dev.liquidglass.core.LiquidGlassShaders
import kotlin.math.abs

private const val MIN_BLUR_RADIUS_PX = 0.05f
private const val SATURATION_EPSILON = 0.01f

/**
 * Fully resolved per-draw scene parameters in glass-layer pixels, mirroring the
 * uniform block of [LiquidGlassShaders.LIQUID_GLASS] one to one.
 */
internal class GlassSceneUniforms(
    val shapes: FloatArray,
    val cornerRadii: FloatArray,
    val mergeSmoothing: Float,
    val refractionHeightPx: Float,
    val refractionAmountPx: Float,
    val chromaticAberration: Float,
    val tintRed: Float,
    val tintGreen: Float,
    val tintBlue: Float,
    val tintAlpha: Float,
    val noiseAlpha: Float,
    val lightDirX: Float,
    val lightDirY: Float,
    val highlightAlpha: Float,
    val highlightWidthPx: Float,
    var pressAmount: Float,
    var pressX: Float,
    var pressY: Float,
)

/**
 * Blur + saturation pre-pass applied to the backdrop before the glass shader
 * samples it (and the whole effect on the BLUR tier). Returns null when both
 * stages are no-ops.
 */
@RequiresApi(31)
internal fun buildBackdropPrepEffect(blurRadiusPx: Float, saturation: Float): RenderEffect? {
    val blur = if (blurRadiusPx > MIN_BLUR_RADIUS_PX) {
        RenderEffect.createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP)
    } else {
        null
    }
    if (abs(saturation - 1f) < SATURATION_EPSILON) return blur
    val filter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) })
    return if (blur != null) {
        RenderEffect.createColorFilterEffect(filter, blur)
    } else {
        RenderEffect.createColorFilterEffect(filter)
    }
}

/** One entry per painter: no global shader sharing and no unbounded parameter cache. */
@RequiresApi(31)
internal class BackdropPrepEffectCache {
    private var blur = Float.NaN
    private var saturation = Float.NaN
    private var effect: RenderEffect? = null
    private var composeEffect: androidx.compose.ui.graphics.RenderEffect? = null

    fun get(blurRadiusPx: Float, saturation: Float): RenderEffect? {
        if (blur != blurRadiusPx || this.saturation != saturation) {
            effect = buildBackdropPrepEffect(blurRadiusPx, saturation)
            composeEffect = null
            blur = blurRadiusPx
            this.saturation = saturation
        }
        return effect
    }

    fun getCompose(blurRadiusPx: Float, saturation: Float): androidx.compose.ui.graphics.RenderEffect? {
        val native = get(blurRadiusPx, saturation) ?: return null
        return composeEffect ?: native.asComposeRenderEffect().also { composeEffect = it }
    }
}

/**
 * Owns the compiled [RuntimeShader] for one glass node. The AGSL program is
 * compiled once per node; each draw only refreshes uniforms and rebuilds the
 * (cheap) effect chain around it.
 */
@RequiresApi(33)
internal class LiquidGlassRuntimeEffect {

    private val shader = RuntimeShader(LiquidGlassShaders.LIQUID_GLASS)
    private val prepCache = BackdropPrepEffectCache()
    private var staticUniforms: GlassSceneUniforms? = null
    private var effectScene: GlassSceneUniforms? = null
    private var effect: RenderEffect? = null
    private var effectBlur = Float.NaN
    private var effectSaturation = Float.NaN
    private var effectPress = Float.NaN
    private var effectPressX = Float.NaN
    private var effectPressY = Float.NaN
    private var composeNative: RenderEffect? = null
    private var composeEffect: androidx.compose.ui.graphics.RenderEffect? = null

    fun buildComposeEffect(uniforms: GlassSceneUniforms, blurRadiusPx: Float, saturation: Float): androidx.compose.ui.graphics.RenderEffect {
        val native = buildRenderEffect(uniforms, blurRadiusPx, saturation)
        if (composeNative !== native || composeEffect == null) {
            composeEffect = native.asComposeRenderEffect()
            composeNative = native
        }
        return requireNotNull(composeEffect)
    }

    fun buildRenderEffect(
        uniforms: GlassSceneUniforms,
        blurRadiusPx: Float,
        saturation: Float,
    ): RenderEffect {
        val cached = effect
        if (cached != null && effectScene === uniforms && effectBlur == blurRadiusPx &&
            effectSaturation == saturation && effectPress == uniforms.pressAmount &&
            effectPressX == uniforms.pressX && effectPressY == uniforms.pressY) return cached
        with(shader) {
            // Painter creates a new scene when any static parameter/buffer changes.
            // Press frames reuse that scene and only need the three dynamic uniforms.
            if (staticUniforms !== uniforms) {
                setFloatUniform(GlassUniforms.SHAPES, uniforms.shapes)
                setFloatUniform(GlassUniforms.SHAPE_RADII, uniforms.cornerRadii)
                setFloatUniform(GlassUniforms.MERGE_SMOOTHING, uniforms.mergeSmoothing)
                setFloatUniform(GlassUniforms.REFRACTION_HEIGHT, uniforms.refractionHeightPx)
                setFloatUniform(GlassUniforms.REFRACTION_AMOUNT, uniforms.refractionAmountPx)
                setFloatUniform(GlassUniforms.CHROMATIC_ABERRATION, uniforms.chromaticAberration)
                setFloatUniform(
                    GlassUniforms.TINT,
                    uniforms.tintRed,
                    uniforms.tintGreen,
                    uniforms.tintBlue,
                    uniforms.tintAlpha,
                )
                setFloatUniform(GlassUniforms.NOISE_ALPHA, uniforms.noiseAlpha)
                setFloatUniform(GlassUniforms.LIGHT_DIRECTION, uniforms.lightDirX, uniforms.lightDirY)
                setFloatUniform(GlassUniforms.HIGHLIGHT_ALPHA, uniforms.highlightAlpha)
                setFloatUniform(GlassUniforms.HIGHLIGHT_WIDTH, uniforms.highlightWidthPx)
                staticUniforms = uniforms
            }
            setFloatUniform(GlassUniforms.PRESS_AMOUNT, uniforms.pressAmount)
            setFloatUniform(GlassUniforms.PRESS_POINT, uniforms.pressX, uniforms.pressY)
        }
        val shaderEffect = RenderEffect.createRuntimeShaderEffect(shader, GlassUniforms.CONTENT)
        // RuntimeShader effects snapshot uniforms: rebuild that wrapper after each update.
        // The immutable blur/saturation pre-pass can safely be retained independently.
        val prep = prepCache.get(blurRadiusPx, saturation)
        val result = if (prep == null) shaderEffect else RenderEffect.createChainEffect(shaderEffect, prep)
        effectScene = uniforms
        effectBlur = blurRadiusPx
        effectSaturation = saturation
        effectPress = uniforms.pressAmount
        effectPressX = uniforms.pressX
        effectPressY = uniforms.pressY
        effect = result
        return result
    }
}
