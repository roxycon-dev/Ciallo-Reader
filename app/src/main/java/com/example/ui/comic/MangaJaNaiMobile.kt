package com.example.ui.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Bounds neural work using measured tile throughput. Original page/detail never enters this cap. */
internal fun mangaMobileInputSize(width: Int, height: Int, tileBudget: Int): Pair<Int, Int> {
    val scale = minOf(1.0, 1400.0 / maxOf(width, height), sqrt(900_000.0 / (width.toDouble() * height)))
    var w = (width * scale).roundToInt().coerceAtLeast(1)
    var h = (height * scale).roundToInt().coerceAtLeast(1)
    val tiles = tileBudget.coerceAtLeast(1)
    while (((w + MangaJaNai.TILE - 1) / MangaJaNai.TILE).toLong() *
        ((h + MangaJaNai.TILE - 1) / MangaJaNai.TILE) > tiles) {
        w = (w * 0.93).toInt().coerceAtLeast(1)
        h = (h * 0.93).toInt().coerceAtLeast(1)
    }
    return w to h
}

/** Add the low-resolution network's correction to the full-detail original.
 * Only two small row bands are allocated, rather than two more full-resolution pages.
 */
internal fun combineMangaJaNaiDetail(
    original: Bitmap, neuralInput: Bitmap, neuralOutput: Bitmap,
    width: Int, height: Int, strength: Float,
): Bitmap {
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    var lowBand: Bitmap? = null
    var aiBand: Bitmap? = null
    try {
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    Canvas(output).drawBitmap(original, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
    if (strength <= 0f) return output
    val bandHeight = minOf(48, height)
    val lowBitmap = Bitmap.createBitmap(width, bandHeight, Bitmap.Config.ARGB_8888).also { lowBand = it }
    val aiBitmap = Bitmap.createBitmap(width, bandHeight, Bitmap.Config.ARGB_8888).also { aiBand = it }
    val lowCanvas = Canvas(lowBitmap)
    val aiCanvas = Canvas(aiBitmap)
    val detail = IntArray(width * bandHeight)
    val low = IntArray(detail.size)
    val ai = IntArray(detail.size)
    val blend = strength.coerceIn(0f, 1f)
        for (y in 0 until height step bandHeight) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("MangaJaNai cancelled")
            val rows = minOf(bandHeight, height - y)
            lowBitmap.eraseColor(Color.TRANSPARENT)
            aiBitmap.eraseColor(Color.TRANSPARENT)
            val bounds = RectF(0f, -y.toFloat(), width.toFloat(), (height - y).toFloat())
            lowCanvas.drawBitmap(neuralInput, null, bounds, paint)
            aiCanvas.drawBitmap(neuralOutput, null, bounds, paint)
            output.getPixels(detail, 0, width, 0, y, width, rows)
            lowBitmap.getPixels(low, 0, width, 0, 0, width, rows)
            aiBitmap.getPixels(ai, 0, width, 0, 0, width, rows)
            for (i in 0 until width * rows) {
                var pixel = detail[i] and -0x1000000
                for (channel in 0..2) {
                    val shift = 16 - channel * 8
                    val from = (detail[i] shr shift) and 255
                    val delta = ((ai[i] shr shift) and 255) - ((low[i] shr shift) and 255)
                    pixel = pixel or ((from + delta * blend).roundToInt().coerceIn(0, 255) shl shift)
                }
                detail[i] = pixel
            }
            output.setPixels(detail, 0, width, 0, y, width, rows)
        }
        return output
    } catch (error: Throwable) { output.recycle(); throw error }
    finally { lowBand?.recycle(); aiBand?.recycle() }
}
