/*
 * EASU kernel adapted from AMD FidelityFX FSR 1, ffx_fsr1.h.
 * Copyright (c) 2021 Advanced Micro Devices, Inc. MIT License.
 * Full notice: docs/vendor-licenses/FidelityFX-FSR-MIT.txt
 */
package com.example.ui.comic

import android.graphics.Bitmap
import kotlin.math.*

/** CPU edge-adaptive reconstruction. No model download, GPU extension or source-specific code. */
internal object ComicEdgeUpscaler {
    private val tapX = intArrayOf(0, 1, -1, 0, 0, -1, 1, 2, 2, 1, 1, 0)
    private val tapY = intArrayOf(-1, -1, 1, 1, 0, 0, 1, 1, 0, 0, 2, 2)
    private val crosses = arrayOf(
        intArrayOf(0, 5, 4, 9, 3), intArrayOf(1, 4, 9, 8, 6),
        intArrayOf(4, 2, 3, 6, 11), intArrayOf(9, 3, 6, 7, 10),
    )

    /** Uniform scale, bounded output area; an already large input is never reduced. */
    fun targetSize(src: Bitmap): Pair<Int, Int> = targetSize(src.width, src.height)

    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val budgetScale = minOf(2f, ComicImagePipeline.MAX_EDGE.toFloat() / max(width, height),
            sqrt(6_000_000f / (width.toLong() * height).toFloat())).coerceAtLeast(1f)
        val scale = if (budgetScale < 1.15f) 1f else budgetScale
        return max(width, (width * scale).roundToInt()) to max(height, (height * scale).roundToInt())
    }

    fun upscale(src: Bitmap, width: Int, height: Int): Bitmap {
        if (width == src.width && height == src.height) return src
        require(width >= src.width && height >= src.height)
        val sw = src.width; val sh = src.height
        val pixels = IntArray(sw * sh)
        src.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        val output = IntArray(width * height)
        val sx = sw.toFloat() / width; val sy = sh.toFloat() / height
        ComicImagePipeline.parallelStripes(height) { y0, y1 ->
            // Scratch belongs to one stripe, never to the whole invocation.
            val colors = IntArray(12)
            val luma = FloatArray(12)
            for (y in y0 until y1) {
                val fy = (y + 0.5f) * sy - 0.5f
                val iy = floor(fy).toInt(); val py = fy - iy
                for (x in 0 until width) {
                    val fx = (x + 0.5f) * sx - 0.5f
                    val ix = floor(fx).toInt(); val px = fx - ix
                    for (i in 0..11) {
                        val c = pixels[(iy + tapY[i]).coerceIn(0, sh - 1) * sw +
                            (ix + tapX[i]).coerceIn(0, sw - 1)]
                        colors[i] = c
                        luma[i] = ((c shr 16 and 255) * 0.5f + (c shr 8 and 255) +
                            (c and 255) * 0.5f) / 255f
                    }
                    var dx = 0f; var dy = 0f; var len = 0f
                    for (i in 0..3) {
                        val w = (if (i and 1 == 0) 1f - px else px) *
                            (if (i < 2) 1f - py else py)
                        val cross = crosses[i]
                        val a = luma[cross[0]]; val b = luma[cross[1]]; val c = luma[cross[2]]
                        val d = luma[cross[3]]; val e = luma[cross[4]]
                        val gx = d - b; val gy = e - a
                        dx += gx * w; dy += gy * w
                        val lx = (abs(gx) / max(1e-6f, max(abs(d - c), abs(c - b)))).coerceAtMost(1f)
                        val ly = (abs(gy) / max(1e-6f, max(abs(e - c), abs(c - a)))).coerceAtMost(1f)
                        len += (lx * lx + ly * ly) * w
                    }
                    val norm = dx * dx + dy * dy
                    if (norm < 1f / 32768f) { dx = 1f; dy = 0f }
                    else { val inv = 1f / sqrt(norm); dx *= inv; dy *= inv }
                    len *= 0.5f; len *= len
                    val stretch = 1f / max(abs(dx), abs(dy))
                    val lenX = 1f + (stretch - 1f) * len
                    val lenY = 1f - 0.5f * len
                    val lobe = 0.5f - 0.29f * len
                    val clip = 1f / lobe
                    var r = 0f; var g = 0f; var b = 0f; var sum = 0f
                    for (i in 0..11) {
                        val ox = tapX[i] - px; val oy = tapY[i] - py
                        val vx = (ox * dx + oy * dy) * lenX
                        val vy = (-ox * dy + oy * dx) * lenY
                        val d2 = min(vx * vx + vy * vy, clip)
                        val wb = 0.4f * d2 - 1f; val wa = lobe * d2 - 1f
                        val w = (1.5625f * wb * wb - 0.5625f) * wa * wa
                        val c = colors[i]
                        r += (c shr 16 and 255) * w; g += (c shr 8 and 255) * w
                        b += (c and 255) * w; sum += w
                    }
                    // Clamp to the nearest 2x2 texels: EASU deringing preserves ink/white-paper edges.
                    fun channel(value: Float, shift: Int): Int {
                        val f = colors[4] ushr shift and 255; val g0 = colors[9] ushr shift and 255
                        val j = colors[3] ushr shift and 255; val k = colors[6] ushr shift and 255
                        val v = if (abs(sum) > 1e-6f) value / sum else f.toFloat()
                        return v.roundToInt().coerceIn(minOf(f, g0, j, k), maxOf(f, g0, j, k))
                    }
                    val alphaTop = (colors[4] ushr 24) * (1f - px) + (colors[9] ushr 24) * px
                    val alphaBot = (colors[3] ushr 24) * (1f - px) + (colors[6] ushr 24) * px
                    val alpha = (alphaTop * (1f - py) + alphaBot * py).roundToInt().coerceIn(0, 255)
                    output[y * width + x] = (alpha shl 24) or (channel(r, 16) shl 16) or
                        (channel(g, 8) shl 8) or channel(b, 0)
                }
            }
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Restore softened ink at two spatial scales. A blurred stroke's centre is a local
     * minimum: clamping to the cross's min/max would erase exactly that restoration.
     * Only a supported soft extremum may extend that range, with a small bounded margin.
     * Flat paper, linear ramps and already resolved hard transitions remain unchanged.
     */
    fun sharpen(src: Bitmap, strength: Float): Bitmap = sharpenImpl(src, strength, restoreInk = true)

    /** The reconstructed image already has focused ink; refine antialiasing without restoring twice. */
    fun sharpenReconstruction(src: Bitmap, strength: Float): Bitmap = sharpenImpl(src, strength, restoreInk = false)

    /** Crisp antialiased pages contain a large proportion of already steep edge samples. */
    private fun restorationScale(pixels: IntArray, w: Int, h: Int): Float {
        val step = max(1, sqrt(w.toDouble() * h / 65_536).toInt())
        fun lum(c: Int): Float = (c shr 16 and 255) * .299f + (c shr 8 and 255) * .587f + (c and 255) * .114f
        var edges = 0; var focused = 0
        for (y in 0 until h - 1 step step) for (x in 0 until w - 1 step step) {
            val i = y * w + x; val c = lum(pixels[i])
            val horizontal = abs(c - lum(pixels[i + 1])); val vertical = abs(c - lum(pixels[i + w]))
            if (horizontal > 12f) { edges++; if (horizontal > 72f) focused++ }
            if (vertical > 12f) { edges++; if (vertical > 72f) focused++ }
        }
        if (edges < 64) return 1f
        return ((.40f - focused.toFloat() / edges) / .25f).coerceIn(0f, 1f)
    }

    private fun sharpenImpl(src: Bitmap, strength: Float, restoreInk: Boolean): Bitmap {
        if (strength <= 0f || src.width < 3 || src.height < 3) return src
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = pixels.copyOf()
        val restoreStrength = if (restoreInk) strength.coerceIn(0f, 1f) * restorationScale(pixels, w, h) else strength
        fun lum(c: Int): Float = (c shr 16 and 255) * 0.299f + (c shr 8 and 255) * 0.587f + (c and 255) * 0.114f
        ComicImagePipeline.parallelStripes(h - 2) { y0, y1 ->
            for (y in y0 + 1 until y1 + 1) for (x in 1 until w - 1) {
                val i = y * w + x
                val c = lum(pixels[i]); val a = lum(pixels[i - w]); val b = lum(pixels[i - 1])
                val d = lum(pixels[i + 1]); val e = lum(pixels[i + w])
                val lo = minOf(c, a, b, d, e); val hi = maxOf(c, a, b, d, e)
                val range = hi - lo
                if (!restoreInk) {
                    val edge = ((range - 8f) / 40f).coerceIn(0f, 1f)
                    val detail = c - (a + b + d + e) * .25f
                    val gain = edge * edge * (3f - 2f * edge) * strength.coerceIn(0f, 1f)
                    val red = pixels[i] shr 16 and 255; val green = pixels[i] shr 8 and 255; val blue = pixels[i] and 255
                    val delta = (detail * gain * 1.8f).coerceIn(-24f * strength, 24f * strength)
                        .coerceIn(lo - c, hi - c)
                        .coerceIn(-minOf(red, green, blue).toFloat(), (255 - maxOf(red, green, blue)).toFloat())
                    fun ch(shift: Int) = ((pixels[i] ushr shift and 255) + delta).roundToInt().coerceIn(0, 255)
                    out[i] = (pixels[i] and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                    continue
                }
                val edge = ((range - 8f) / 28f).coerceIn(0f, 1f)
                val detail = c - (a + b + d + e) * 0.25f
                if (edge == 0f || abs(detail) < .25f) continue
                // Opposing samples distinguish a soft stroke/transition from a one-sided
                // step. Very large jumps are already focused; do not invent dark outlines.
                val opposing = max(min(abs(c - a), abs(c - e)), min(abs(c - b), abs(c - d)))
                val soft = ((opposing - 1f) / 5f).coerceIn(0f, 1f) *
                    (1f - ((opposing - 64f) / 48f).coerceIn(0f, 1f))
                if (soft == 0f) continue
                val ax = (x - 2).coerceAtLeast(0); val bx = (x + 2).coerceAtMost(w - 1)
                val ay = (y - 2).coerceAtLeast(0); val by = (y + 2).coerceAtMost(h - 1)
                val broad = c - (lum(pixels[ay * w + x]) + lum(pixels[by * w + x]) +
                    lum(pixels[y * w + ax]) + lum(pixels[y * w + bx])) * .25f
                // A broader supported residual survives fitting the full page to the screen.
                val supported = if (broad * detail > 0f) broad else 0f
                val gain = edge * edge * (3f - 2f * edge) * soft * restoreStrength
                val minimum = c <= lo + .01f && ((c < b - 2f && c < d - 2f) || (c < a - 2f && c < e - 2f))
                val maximum = c >= hi - .01f && ((c > b + 2f && c > d + 2f) || (c > a + 2f && c > e + 2f))
                val margin = min(20f, range * .6f) * restoreStrength * soft
                val red = pixels[i] shr 16 and 255; val green = pixels[i] shr 8 and 255; val blue = pixels[i] and 255
                val delta = ((detail * 2.2f + supported * .75f) * gain).coerceIn(-36f * restoreStrength, 36f * restoreStrength)
                    .coerceIn(lo - c - if (minimum) margin else 0f, hi - c + if (maximum) margin else 0f)
                    .coerceIn(-minOf(red, green, blue).toFloat(), (255 - maxOf(red, green, blue)).toFloat())
                fun ch(shift: Int) = ((pixels[i] ushr shift and 255) + delta).roundToInt().coerceIn(0, 255)
                out[i] = (pixels[i] and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
