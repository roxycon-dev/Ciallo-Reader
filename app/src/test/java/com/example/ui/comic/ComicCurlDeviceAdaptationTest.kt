package com.example.ui.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicCurlDeviceAdaptationTest {
    @Test fun `every native layout input invalidates geometry but gesture-only settings do not`() {
        val config = ComicReaderConfig()
        val first = curlLayoutInputs(config, 1f)
        val changed = listOf(
            config.copy(fit = ComicFit.CUSTOM),
            config.copy(customFitBase = ComicFit.FILL),
            config.copy(customFitScale = 1.4f),
            config.copy(doubleAlign = ComicDoubleAlign.BOTTOM),
            config.copy(doubleGapDp = 13f),
            config.copy(doubleShiftXDp = 4f),
            config.copy(doubleShiftYDp = -6f),
        )
        changed.forEach { assertNotEquals(first, curlLayoutInputs(it, 1f)) }
        assertNotEquals(first, curlLayoutInputs(config, 2f))
        assertEquals(first, curlLayoutInputs(config.copy(doubleTapZoom = !config.doubleTapZoom), 1f))
    }

    private fun front(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (y in 0 until h) for (x in 0 until w) bitmap.setPixel(x, y,
            android.graphics.Color.rgb((x * 37 + y * 11) % 256, (x * 17 + y * 19) % 256, (x * 7 + y * 23) % 256))
    }

    /** Frozen pre-optimization two-resample pipeline, including RGB565 quantization. */
    private fun reference(front: Bitmap, w: Int, h: Int): Bitmap {
        val scale = min(1f, min(1024f / w, 1800f / h))
        val bw = max(8, (w * scale).toInt())
        val bh = max(8, (h * scale).toInt())
        val result = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
        val canvas = Canvas(result)
        canvas.drawColor(0xFFFAFAF7.toInt())
        val small = Bitmap.createScaledBitmap(front, max(2, bw / 6), max(2, bh / 6), true)
        val soft = Bitmap.createScaledBitmap(small, bw, bh, true)
        canvas.drawBitmap(soft, 0f, 0f, Paint().apply {
            isFilterBitmap = true
            alpha = (0.20f * 255).toInt()
            isDither = true
        })
        if (soft !== small && soft !== front) soft.recycle()
        if (small !== front) small.recycle()
        return result
    }

    @Test fun `releasing private back-face intermediates preserves all rendered pixels and source ownership`() {
        val controller = ComicHarismController()
        for ((w, h) in listOf(8 to 8, 240 to 360, 1040 to 1850)) {
            val source = front(64, 96)
            val expected = reference(source, w, h)
            val actual = controller.composeBackTexture(source, w, h)
            try {
                val a = IntArray(actual.width * actual.height)
                val b = IntArray(expected.width * expected.height)
                actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
                expected.getPixels(b, 0, expected.width, 0, 0, expected.width, expected.height)
                assertArrayEquals("back face $w x $h", b, a)
                assertFalse(source.isRecycled)
            } finally { actual.recycle(); expected.recycle(); source.recycle() }
        }
        val tinySource = front(2, 2)
        val result = controller.composeBackTexture(tinySource, 8, 8)
        assertFalse("createScaledBitmap can return the caller's original bitmap", tinySource.isRecycled)
        result.recycle()
        tinySource.recycle()
    }
}
