package com.example.ui.comic

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.floor

/**
 * Anime4K CNN CPU 求值器（第 20 条增强引擎）。
 *
 * 按 GLSL 语义逐层执行 [Anime4KCnnWeights] 的固定权重网络：
 * - 列主序 mat4×vec4：result_row = Σ_col w[col*4+row] · v[col]；
 * - go_0/go_1 = 上一层输出的正/负半波（激活打包）；
 * - 边缘 clamp 采样（等价 texOff）；
 * - Restore 末层输出 3 通道残差 + 原图；Upscale 末层 depth-to-space x2
 *   （4 通道 = 2x2 子像素，(oy%2)*2+(ox%2)）。EASU 基准 + 受局部细节约束的残差。
 *
 * 原生像素分块推理，8px halo 覆盖四层卷积及子像素采样的感受野。
 * 不缩小整页；256px 工作块将三份浮点平面限制在约 3.4MiB。
 */
internal object Anime4KCnn {

    internal class G(val tex: Int, val act: Int, val ox: Int, val oy: Int, val w: FloatArray)
    internal class Layer(val groups: Array<G>, val bias: FloatArray)

    /** 解析扁平权重布局（见 Anime4KCnnWeights 注释） */
    internal fun readFlat(flat: FloatArray): Array<Layer> {
        var cur = 0
        val nLayers = flat[cur++].toInt()
        val layers = arrayOfNulls<Layer>(nLayers)
        for (l in 0 until nLayers) {
            val nGroups = flat[cur++].toInt()
            val bias = FloatArray(4) { flat[cur + it] }
            cur += 4
            val groups = arrayOfNulls<G>(nGroups)
            for (g in 0 until nGroups) {
                val tex = flat[cur++].toInt()
                val act = flat[cur++].toInt()
                val ox = flat[cur++].toInt()
                val oy = flat[cur++].toInt()
                val w = FloatArray(16)
                for (i in 0 until 16) w[i] = flat[cur + i]
                cur += 16
                groups[g] = G(tex, act, ox, oy, w)
            }
            layers[l] = Layer(groups.requireNoNulls(), bias)
        }
        @Suppress("UNCHECKED_CAST")
        return layers as Array<Layer>
    }

    private val restoreLayers by lazy { readFlat(Anime4KCnnWeights.RESTORE_S) }
    private val upscaleLayers by lazy { readFlat(Anime4KCnnWeights.UPSCALE_S) }

    internal fun referenceConvolution(model: String, rgba: FloatArray, width: Int, height: Int): FloatArray =
        runNetwork(if (model == "restore") restoreLayers else upscaleLayers, rgba, width, height)

    /* ── 基础采样与卷积 ── */

    /** clamp 边缘采样：返回 tex 平面 (x,y) 的 RGBA（act: 0 原始 / 1 正半波 / 2 负半波） */
    private inline fun sample(tex: FloatArray, w: Int, h: Int, x: Int, y: Int, act: Int, out: FloatArray) {
        val cx = x.coerceIn(0, w - 1)
        val cy = y.coerceIn(0, h - 1)
        val i = (cy * w + cx) * 4
        when (act) {
            1 -> { out[0] = max(0f, tex[i]); out[1] = max(0f, tex[i + 1]); out[2] = max(0f, tex[i + 2]); out[3] = max(0f, tex[i + 3]) }
            2 -> { out[0] = max(0f, -tex[i]); out[1] = max(0f, -tex[i + 1]); out[2] = max(0f, -tex[i + 2]); out[3] = max(0f, -tex[i + 3]) }
            else -> { out[0] = tex[i]; out[1] = tex[i + 1]; out[2] = tex[i + 2]; out[3] = tex[i + 3] }
        }
    }

    /** 单层卷积（src0=原图平面，src1=上一层输出；返回新 4 通道平面）。
     *  第六轮第 5 条：行条带多核并行（读 src ±1 行越界只读安全，out 独立缓冲）。 */
    private fun convLayer(layer: Layer, src0: FloatArray, src1: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h * 4)
        ComicImagePipeline.parallelStripes(h, minParallelRows = 128) { y0, y1 ->
            // Each worker owns its sample buffer. Sharing this array races every mat4 multiply.
            val v = FloatArray(4)
            for (y in y0 until y1) {
                for (x in 0 until w) {
                    var r0 = layer.bias[0]; var r1 = layer.bias[1]; var r2 = layer.bias[2]; var r3 = layer.bias[3]
                    for (g in layer.groups) {
                        sample(if (g.tex == 0) src0 else src1, w, h, x + g.ox, y + g.oy, g.act, v)
                        val wq = g.w
                        r0 += wq[0] * v[0] + wq[4] * v[1] + wq[8] * v[2] + wq[12] * v[3]
                        r1 += wq[1] * v[0] + wq[5] * v[1] + wq[9] * v[2] + wq[13] * v[3]
                        r2 += wq[2] * v[0] + wq[6] * v[1] + wq[10] * v[2] + wq[14] * v[3]
                        r3 += wq[3] * v[0] + wq[7] * v[1] + wq[11] * v[2] + wq[15] * v[3]
                    }
                    val o = (y * w + x) * 4
                    out[o] = r0; out[o + 1] = r1; out[o + 2] = r2; out[o + 3] = r3
                }
            }
        }
        return out
    }

    private fun runNetwork(layers: Array<Layer>, src0: FloatArray, w: Int, h: Int): FloatArray {
        var prev = src0
        for (l in layers) prev = convLayer(l, src0, prev, w, h)
        return prev
    }

    private fun bitmapToPlane(src: Bitmap): FloatArray {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val plane = FloatArray(w * h * 4)
        for (i in px.indices) {
            val p = px[i]
            val o = i * 4
            plane[o] = ((p shr 16) and 0xFF) / 255f
            plane[o + 1] = ((p shr 8) and 0xFF) / 255f
            plane[o + 2] = (p and 0xFF) / 255f
            plane[o + 3] = 1f   // GLSL MAIN 纹理的 alpha（mpv 挂钩下恒 1）
        }
        return plane
    }

    private fun clamp255(v: Float): Int = (v * 255f).roundToInt().coerceIn(0, 255)

    /** Remove the network's DC colour bias; only bounded luminance detail may be added. */
    private fun restoreDetail(conv: FloatArray, w: Int, h: Int): FloatArray {
        val luminance = FloatArray(w * h) { i ->
            conv[i * 4] * .299f + conv[i * 4 + 1] * .587f + conv[i * 4 + 2] * .114f
        }
        return FloatArray(w * h) { i ->
            val x = i % w; val y = i / w
            val mean = (luminance[max(0, y - 1) * w + x] + luminance[min(h - 1, y + 1) * w + x] +
                luminance[y * w + max(0, x - 1)] + luminance[y * w + min(w - 1, x + 1)]) * .25f
            luminance[i] - mean
        }
    }

    private fun edgeGate(plane: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        var lo = 1f; var hi = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val i = ((y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)) * 4
            val value = plane[i] * .299f + plane[i + 1] * .587f + plane[i + 2] * .114f
            lo = min(lo, value); hi = max(hi, value)
        }
        val edge = (((hi - lo) * 255f - 8f) / 32f).coerceIn(0f, 1f)
        return edge * edge * (3f - 2f * edge)
    }

    /* ── 公开 API ── */

    /** Restore 保留原尺寸。[maxEdge] 是单块预算，不能用于缩小整页。 */
    fun restore(src: Bitmap, strength: Float, maxEdge: Int = 256): Bitmap =
        tiled(src, 1, maxEdge.coerceIn(32, 256)) { restorePatch(it, strength) }

    private fun restorePatch(src: Bitmap, strength: Float): Bitmap {
        val w = src.width; val h = src.height
        val plane = bitmapToPlane(src)
        val out = ComicNeuralBackend.run("restore", plane, w, h) ?: runNetwork(restoreLayers, plane, w, h)
        val detail = restoreDetail(out, w, h)
        // 末层语义（Anime4K v4.0 Restore GLSL：SAVE = conv + HOOKED_tex）：
        // runNetwork 返回的是"卷积增量"，完整输出 = 原图 + 增量，strength 缩放增量。
        // 第六轮第 5 条（视觉终审）：CNN 残差全量应用在噪声底上产生"整体提亮+
        // 线条变薄"的负向观感（代理判定 worse than original）——残差固定 0.6
        // 上限系数，弱化网络对底色的整体重投影，主要效果交给下游线重建。
        val px = IntArray(w * h)
        val original = IntArray(w * h)
        src.getPixels(original, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val o = i * 4
            val d = 0.6f * strength.coerceIn(0f, 1f) * edgeGate(plane, w, h, i % w, i / w)
            val delta = (detail[i] * d).coerceIn(-24f / 255f, 24f / 255f)
                .coerceIn(-minOf(plane[o], plane[o + 1], plane[o + 2]), 1f - maxOf(plane[o], plane[o + 1], plane[o + 2]))
            val r = plane[o] + delta
            val g = plane[o + 1] + delta
            val b = plane[o + 2] + delta
            px[i] = (original[i] and 0xFF000000.toInt()) or
                (clamp255(r) shl 16) or (clamp255(g) shl 8) or clamp255(b)
        }
        val restored = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        restored.setPixels(px, 0, w, 0, 0, w, h)
        return restored
    }

    /** 2× CNN：深度到空间 + EASU 基准。[maxSrcEdge] 是块大小。 */
    fun upscale2x(src: Bitmap, strength: Float, maxSrcEdge: Int = 256): Bitmap =
        tiled(src, 2, maxSrcEdge.coerceIn(32, 256)) { upscalePatch(it, strength) }

    private fun upscalePatch(src: Bitmap, strength: Float): Bitmap {
        val w = src.width; val h = src.height
        val plane = bitmapToPlane(src)
        val conv = ComicNeuralBackend.run("upscale", plane, w, h) ?: runNetwork(upscaleLayers, plane, w, h)
        val ow = w * 2; val oh = h * 2
        val baseline = ComicEdgeUpscaler.upscale(src, ow, oh)
        val base = IntArray(ow * oh)
        try { baseline.getPixels(base, 0, ow, 0, 0, ow, oh) }
        finally { if (baseline !== src) baseline.recycle() }
        val px = base.copyOf()
        val gates = FloatArray(w * h) { edgeGate(plane, w, h, it % w, it / w) }
        fun lum(c: Int) = (c shr 16 and 255) * .299f + (c shr 8 and 255) * .587f + (c and 255) * .114f
        for (oy in 0 until oh) {
            val cy = oy ushr 1
            for (ox in 0 until ow) {
                val cx = ox ushr 1
                val ci = (cy * w + cx) * 4 + ((oy and 1) * 2 + (ox and 1))
                val index = oy * ow + ox; val color = base[index]
                val localDetail = lum(color) - (lum(base[max(0, oy - 1) * ow + ox]) +
                    lum(base[min(oh - 1, oy + 1) * ow + ox]) + lum(base[oy * ow + max(0, ox - 1)]) +
                    lum(base[oy * ow + min(ow - 1, ox + 1)])) * .25f
                val predicted = conv[ci] * 255f * strength.coerceIn(0f, 1f) * gates[cy * w + cx]
                // A video-trained subpixel residual must not reverse manga edges or invent
                // periodic paper texture. Trust only detail supported by the reconstruction.
                val bound = min(12f, kotlin.math.abs(localDetail) * .35f)
                val residual = if (predicted * localDetail > 0f) predicted.coerceIn(-bound, bound) else 0f
                val red = color shr 16 and 255; val green = color shr 8 and 255; val blue = color and 255
                val delta = residual.coerceIn(-minOf(red, green, blue).toFloat(), (255 - maxOf(red, green, blue)).toFloat())
                fun ch(v: Int) = (v + delta).roundToInt().coerceIn(0, 255)
                px[index] = (color and 0xFF000000.toInt()) or (ch(red) shl 16) or (ch(green) shl 8) or ch(blue)
            }
        }
        val outBmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(px, 0, ow, 0, 0, ow, oh)
        return outBmp
    }

    /** Output cores are disjoint; only privately owned patch bitmaps are recycled. */
    private fun tiled(src: Bitmap, scale: Int, edge: Int, infer: (Bitmap) -> Bitmap): Bitmap {
        if (src.width < 8 || src.height < 8) return src
        val output = Bitmap.createBitmap(src.width * scale, src.height * scale, Bitmap.Config.ARGB_8888)
        val halo = 8
        try {
            for (top in 0 until src.height step edge) {
                for (left in 0 until src.width step edge) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("CNN cancelled")
                    val right = min(src.width, left + edge)
                    val bottom = min(src.height, top + edge)
                    val x0 = max(0, left - halo); val y0 = max(0, top - halo)
                    val x1 = min(src.width, right + halo); val y1 = min(src.height, bottom + halo)
                    val patch = Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0)
                    var result: Bitmap? = null
                    try {
                        result = infer(patch)
                        val width = (right - left) * scale
                        val height = (bottom - top) * scale
                        val pixels = IntArray(width * height)
                        result.getPixels(pixels, 0, width, (left - x0) * scale, (top - y0) * scale, width, height)
                        output.setPixels(pixels, 0, width, left * scale, top * scale, width, height)
                    } finally {
                        if (result !== patch && result !== src) result?.recycle()
                        if (patch !== src) patch.recycle()
                    }
                }
            }
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }
}
