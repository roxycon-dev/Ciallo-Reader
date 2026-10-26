package me.trishiraj.shadowglow

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.roundToInt

/** Geometry belongs to one draw cache; a new window size or density recreates it. */
internal class RoundRectGlowDrawCache(
    private val width: Float,
    private val height: Float,
    private val spread: Float,
    private val offsetX: Float,
    private val offsetY: Float,
    private val cornerRadius: Float,
    private val color: Color,
    private val alpha: Float,
) {
    private val path = Path()
    private val bounds = RectF()
    private var previousLeft = Float.NaN
    private var previousTop = Float.NaN
    private var previousRight = Float.NaN
    private var previousBottom = Float.NaN
    private var signature = ""
    private var previousSigma = Int.MIN_VALUE
    private var shadow: ResolvedBlurredShadow? = null

    fun draw(scope: DrawScope, parallaxX: Float, parallaxY: Float, sigma: Float) {
        // Preserve the original order of Float operations, including translation.
        val totalOffsetX = offsetX + parallaxX
        val totalOffsetY = offsetY + parallaxY
        val left = -spread + totalOffsetX
        val top = -spread + totalOffsetY
        val right = width + spread + totalOffsetX
        val bottom = height + spread + totalOffsetY
        if (right <= left || bottom <= top) return
        val changed = left != previousLeft || top != previousTop || right != previousRight || bottom != previousBottom
        if (changed) {
            previousLeft = left; previousTop = top; previousRight = right; previousBottom = bottom
            val radius = cornerRadius.coerceIn(0f, (right - left) / 2f)
            path.rewind()
            path.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)
            @Suppress("DEPRECATION")
            path.computeBounds(bounds, true)
            signature = "RR|${((right - left) * 2f).roundToInt()}|${((bottom - top) * 2f).roundToInt()}|${(radius * 2f).roundToInt()}"
        }
        if (sigma.isNaN() || sigma < 0f) { shadow = null; previousSigma = Int.MIN_VALUE; return }
        // resolveBlurredShadow uses only this quantized sigma thereafter. Reusing
        // the resolved wrapper within the same bucket cannot alter a single pixel.
        val sigmaKey = (sigma * 2f).roundToInt()
        if (changed || sigmaKey != previousSigma) {
            previousSigma = sigmaKey
            shadow = resolveBlurredShadow(path, bounds, signature, color, sigma, 0f, 0f, 0f, Fill,
                alpha = alpha, bucket = ShadowCacheBucket.ANIMATED)
        }
        shadow?.let { with(scope) { drawResolvedBlurredShadow(it) } }
    }
}

/** Keep the full outline and PathMeasure; only the moving segment is rewritten. */
internal class GlowTrailDrawCache(
    outline: Outline,
    strokeWidth: Float,
    private val sigma: Float,
    private val alpha: Float,
    trailFraction: Float,
) {
    private val fullPath = outline.toAndroidPath()
    private val measure = PathMeasure(fullPath, false)
    private val length = measure.length
    private val segmentLength = length * trailFraction.coerceIn(.01f, 1f)
    private val segment = Path()
    private val bounds = RectF()
    private val stroke = Stroke(strokeWidth)

    fun draw(scope: DrawScope, progress: Float, color: Color) {
        if (length <= 0f) return
        val start = (progress * length) % length
        val end = start + segmentLength
        segment.rewind()
        if (end <= length) {
            measure.getSegment(start, end, segment, true)
        } else {
            measure.getSegment(start, length, segment, true)
            measure.getSegment(0f, end - length, segment, true)
        }
        @Suppress("DEPRECATION")
        segment.computeBounds(bounds, true)
        val resolved = resolveBlurredShadow(segment, bounds, "TRAIL", color.copy(alpha = alpha), sigma,
            0f, 0f, stroke.width, stroke, useCache = false, bucket = ShadowCacheBucket.ANIMATED) ?: return
        with(scope) { drawResolvedBlurredShadow(resolved) }
    }
}
