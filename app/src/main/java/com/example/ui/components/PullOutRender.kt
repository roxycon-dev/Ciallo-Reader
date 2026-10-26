package com.example.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import kotlin.math.*

/** Live-content deformation: one GPU sample, no capture, bitmap, borrowed layer or renderer. */
@Composable
internal fun StablePaperSurface(life: PaperLifecycle, content: @Composable () -> Unit) {
    Box(Modifier.paperSurface(life)) { content() }
}

/** Attach deformation to the actual panel, rather than its full-screen positioning box. */
@Composable
internal fun Modifier.paperSurface(life: PaperLifecycle? = LocalPaperOwner.current): Modifier {
    if (life == null) return this
    val view = LocalView.current
    val screenLocation = remember(view) { IntArray(2) }
    val windowLocation = remember(view) { IntArray(2) }
    val geometry = remember { PaperSurfaceGeometry() }
    val enhancedMotion = LocalRenderQuality.current.enhancedMotion
    val warp = remember(life.softPaper, enhancedMotion) {
        if (enhancedMotion && life.softPaper && Build.VERSION.SDK_INT >= 33) runCatching { PaperWarpEffect() }
            .onFailure { if (com.example.BuildConfig.DEBUG) { PaperWarpStats.failures++; android.util.Log.w("PaperWarp", "Shader unavailable", it) } }.getOrNull()
        else null
    }
    return this.onGloballyPositioned {
        geometry.bounds = it.boundsInWindow().translate(view.paperWindowOffset(screenLocation, windowLocation))
    }.graphicsLayer {
        val p = life.progress.coerceIn(0f, 1f)
        val bounds = geometry.bounds
        val source = life.origin?.bounds
        val valid = source != null && !source.isEmpty && !life.origin!!.virtual && bounds.width > 0 && bounds.height > 0
        val fullViewport = size.width >= view.width * PullOutConfig.WARP_VIEWPORT_CUTOFF &&
            size.height >= view.height * PullOutConfig.WARP_VIEWPORT_CUTOFF
        val deform = (!fullViewport || life.allowFullViewport) && Build.VERSION.SDK_INT >= 33 && warp != null && !warp.failed && life.softPaper && valid &&
            size.width >= PullOutConfig.WARP_MIN_WIDTH_DP * density && size.height >= PullOutConfig.WARP_MIN_HEIGHT_DP * density && p < 1f
        // Every branch sets every property, so closing/reopening cannot retain an old effect.
        transformOrigin = TransformOrigin(0f, 0f)
        renderEffect = null; alpha = p
        scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f
        if (deform) {
            try {
                val shape = geometry.warp
                val started = if (com.example.BuildConfig.DEBUG) System.nanoTime() else 0L
                shape.update(size.width, size.height, source!!.translate(-bounds.topLeft), p, density)
                if (com.example.BuildConfig.DEBUG) {
                    PaperWarpStats.frames++
                    PaperWarpStats.maxGeometryNanos = max(PaperWarpStats.maxGeometryNanos, System.nanoTime() - started)
                }
                renderEffect = warp!!.effect(shape, size.width, size.height)
                scaleX = shape.frame.width / size.width; scaleY = shape.frame.height / size.height
                translationX = shape.frame.left; translationY = shape.frame.top
                alpha = (p * PullOutConfig.WARP_OPACITY_GAIN).coerceIn(0f, 1f)
            } catch (failure: Exception) {
                // A rejected shader stays on the proven 1.4.14 path for this surface.
                warp!!.failed = true
                if (com.example.BuildConfig.DEBUG) { PaperWarpStats.failures++; android.util.Log.w("PaperWarp", "Using stable transition", failure) }
            }
        }
        if (renderEffect == null && p < 1f) {
            val px = if (valid) ((source!!.center.x - bounds.left) / bounds.width).coerceIn(0f, 1f) else .5f
            val py = if (valid) ((source!!.center.y - bounds.top) / bounds.height).coerceIn(0f, 1f) else .5f
            transformOrigin = TransformOrigin(px, py)
            scaleX = PullOutConfig.SURFACE_SCALE + (1f - PullOutConfig.SURFACE_SCALE) * p
            scaleY = scaleX
            translationY = (1f - p) * PullOutConfig.SURFACE_TRAVEL_DP * density * (py * 2f - 1f)
        }
    }.pointerInput(life) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (life.inputBlocked) event.changes.forEach { it.consume() }
            }
        }
    }
}

private class PaperSurfaceGeometry {
    var bounds = Rect.Zero
    val warp by lazy(LazyThreadSafetyMode.NONE) { PaperWarpGeometry() }
}

/** Forward rows describe released paper length; an inverse table lets the GPU sample
 * the live window directly. The arrays are allocated once, not once per frame.
 */
internal class PaperWarpGeometry {
    private val count = PullOutConfig.WARP_FORWARD_ROWS
    private val y = FloatArray(count + 1)
    private val center = FloatArray(count + 1)
    private val halfWidth = FloatArray(count + 1)
    private val shade = FloatArray(count + 1)
    val rows = FloatArray(PullOutConfig.WARP_ROWS * 4)
    var frame = Rect.Zero
        private set

    fun update(width: Float, height: Float, source: Rect, progress: Float, density: Float) {
        val q = progress.coerceIn(0f, 1f)
        val wave = sin(PI.toFloat() * q)
        val slit = source.center.y.coerceIn(0f, height)
        val far = max(slit, height - slit).coerceAtLeast(1f)
        val softness = PullOutConfig.WARP_SOFTNESS_DP * density
        val lock = (1f - q) * (far + softness * 10f) - q * softness * 10f
        fun softplus(x: Float): Float = when {
            x > softness * 20f -> x
            x < -softness * 20f -> 0f
            else -> softness * ln(1f + exp(x / softness))
        }
        val base = softplus(-lock)
        val y0 = source.center.y + (slit / height - .5f) * source.height * (1f - q) + (slit - source.center.y) * q
        val cx = source.center.x + (width / 2f - source.center.x) * q
        val neckWave = (1f - PullOutConfig.WARP_NECK) * wave.coerceAtLeast(0f).pow(.7f)
        var left = Float.POSITIVE_INFINITY; var right = Float.NEGATIVE_INFINITY
        for (i in 0..count) {
            val v = i.toFloat() / count
            val delta = v * height - slit
            val released = softplus(abs(delta) - lock) - base
            val mapped = released + (abs(delta) - released) * source.height / height
            val free = (released / far).coerceIn(0f, 1f)
            val neck = 1f - neckWave * exp(-abs(mapped) / (PullOutConfig.WARP_DECAY_DP * density))
            val unfold = q + (1f - q) * (1f - exp(-released / (PullOutConfig.WARP_UNFOLD_DP * density)))
            val w = source.width + (width - source.width) * unfold
            center[i] = cx + density * wave * (PullOutConfig.WARP_BEND_DP * free * free +
                PullOutConfig.WARP_FLUTTER_DP * free * sin(v * 4f + q * 5f))
            halfWidth[i] = w * neck * .5f
            y[i] = y0 + delta.sign * mapped
            shade[i] = 1f - PullOutConfig.WARP_SHADE * (1f - q) * wave * (1f - free) * (1f + sin(v * 9f))
            left = min(left, center[i] - halfWidth[i]); right = max(right, center[i] + halfWidth[i])
        }
        // Endpoint identity is exact, including external triggers and rectangular panels.
        if (q == 0f) { left = source.left; right = source.right }
        if (q == 1f) { left = 0f; right = width }
        frame = when (q) {
            0f -> source
            1f -> Rect(0f, 0f, width, height)
            else -> Rect(left, y.first(), right, y.last())
        }
        val fw = frame.width.coerceAtLeast(1f); val fh = frame.height.coerceAtLeast(1f)
        var segment = 0
        for (j in 0 until PullOutConfig.WARP_ROWS) {
            val targetY = frame.top + fh * j / (PullOutConfig.WARP_ROWS - 1)
            while (segment < count - 1 && y[segment + 1] < targetY) segment++
            val t = ((targetY - y[segment]) / (y[segment + 1] - y[segment]).coerceAtLeast(.00001f)).coerceIn(0f, 1f)
            val k = j * 4
            rows[k] = (segment + t) / count
            rows[k + 1] = (center[segment] + (center[segment + 1] - center[segment]) * t - frame.left) / fw
            rows[k + 2] = (halfWidth[segment] + (halfWidth[segment + 1] - halfWidth[segment]) * t) / fw
            rows[k + 3] = shade[segment] + (shade[segment + 1] - shade[segment]) * t
        }
    }
}

@RequiresApi(33)
private class PaperWarpEffect {
    var failed = false
    private val shader = RuntimeShader(PAPER_WARP_SHADER)
    fun effect(shape: PaperWarpGeometry, width: Float, height: Float): androidx.compose.ui.graphics.RenderEffect {
        shader.setFloatUniform("inputSize", width, height)
        shader.setFloatUniform("frameSize", shape.frame.width, shape.frame.height)
        shader.setFloatUniform("rows", shape.rows)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "paper").asComposeRenderEffect()
    }
}

// AGSL permits only constant array indices. A balanced lookup selects one cubic
// segment in at most six coherent branches, not a 64-iteration fragment loop.
private fun paperWarpLookup(first: Int, last: Int): String {
    if (first == last) {
        val a = (first - 1).coerceAtLeast(0)
        val d = (first + 2).coerceAtMost(PullOutConfig.WARP_ROWS - 1)
        return """
            float4 a = rows[$a]; float4 b = rows[$first];
            float4 c = rows[${first + 1}]; float4 d = rows[$d];
            float4 r = b + 0.5*t*(c-a + t*(2.0*a-5.0*b+4.0*c-d + t*(3.0*(b-c)+d-a)));
            r.x = clamp(r.x, b.x, c.x);
            return r;
        """.trimIndent()
    }
    val middle = (first + last + 1) / 2
    return "if (i < $middle.0) { ${paperWarpLookup(first, middle - 1)} } else { ${paperWarpLookup(middle, last)} }"
}

// One content sample; no blur, iterative solver, trig or exponentials per pixel.
private val PAPER_WARP_SHADER = """
    uniform shader paper;
    uniform float2 inputSize;
    uniform float2 frameSize;
    uniform float4 rows[${PullOutConfig.WARP_ROWS}];
    float4 sampleRows(float i, float t) {
        ${paperWarpLookup(0, PullOutConfig.WARP_ROWS - 2)}
    }
    half4 main(float2 xy) {
        float2 uv = xy / inputSize;
        float index = clamp(uv.y, 0.0, 1.0) * ${PullOutConfig.WARP_ROWS - 1}.0;
        float i = floor(min(index, ${PullOutConfig.WARP_ROWS - 2}.0));
        float4 r = sampleRows(i, index-i);
        float v = clamp(r.x, 0.0, 1.0);
        float hw = max(r.z, 0.00001);
        float u = (uv.x-r.y)/(2.0*hw)+0.5;
        if (u < 0.0 || u > 1.0 || uv.y < 0.0 || uv.y > 1.0) return half4(0.0);
        float aa = clamp(min(u,1.0-u)*2.0*hw*frameSize.x+0.5,0.0,1.0);
        half4 color = paper.eval(float2(u,v)*inputSize);
        return half4(color.rgb * half(r.w), color.a) * half(aa);
    }
""".trimIndent()

/** Debug-only counters; release calls are stripped at compilation. */
internal object PaperWarpStats {
    var frames = 0
    var failures = 0
    var maxGeometryNanos = 0L
    fun reset() { frames = 0; failures = 0; maxGeometryNanos = 0L }
}
