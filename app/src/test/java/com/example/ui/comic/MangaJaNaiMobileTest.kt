package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MangaJaNaiMobileTest {
    @Test fun neuralBudgetCapsTilesAreaAndEdgeForLargeAndExtremePages() {
        for ((w, h) in listOf(1 to 1, 1200 to 1800, 2800 to 2000, 50 to 32000, 12000 to 50)) {
            for (budget in listOf(1, 2, 8, 30, 3000)) {
                val (iw, ih) = mangaMobileInputSize(w, h, budget)
                assertTrue(iw in 1..w && ih in 1..h)
                assertTrue(iw.toLong() * ih <= 902000)
                assertTrue(maxOf(iw, ih) <= 1400)
                assertTrue(((iw + MangaJaNai.TILE - 1) / MangaJaNai.TILE).toLong() *
                    ((ih + MangaJaNai.TILE - 1) / MangaJaNai.TILE) <= budget)
            }
        }
    }

    @Test fun zeroNeuralCorrectionPreservesFineOriginalStrokesExactly() {
        val original = Bitmap.createBitmap(128, 160, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(128 * 160) { i -> if (i % 5 == 0 || (i / 128) % 11 == 0) -0x1000000 else -1 }
        original.setPixels(pixels, 0, 128, 0, 0, 128, 160)
        val reduced = Bitmap.createScaledBitmap(original, 16, 20, true)
        val output = combineMangaJaNaiDetail(original, reduced, reduced, 128, 160, 1f)
        try {
            val result = IntArray(pixels.size)
            output.getPixels(result, 0, 128, 0, 0, 128, 160)
            assertArrayEquals(pixels, result) // Not merely an upscaled 16x20 thumbnail.
        } finally { output.recycle(); reduced.recycle(); original.recycle() }
    }

    @Test fun correctionUsesCurrentStrengthAndPreservesAlpha() {
        val original = Bitmap.createBitmap(30, 77, Bitmap.Config.ARGB_8888).apply { eraseColor(0x80646464.toInt()) }
        val low = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff646464.toInt()) }
        val ai = Bitmap.createBitmap(8, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff787878.toInt()) }
        val full = combineMangaJaNaiDetail(original, low, ai, 30, 77, 1f)
        val half = combineMangaJaNaiDetail(original, low, ai, 30, 77, 0.5f)
        try {
            for (y in listOf(0, 47, 48, 76)) {
                val f = full.getPixel(12, y)
                val h = half.getPixel(12, y)
                assertEquals(128, f ushr 24)
                assertEquals(128, h ushr 24)
                assertTrue(kotlin.math.abs((f and 255) - 120) <= 1)
                assertTrue(kotlin.math.abs((h and 255) - 110) <= 1)
            }
        } finally { full.recycle(); half.recycle(); original.recycle(); low.recycle(); ai.recycle() }
    }

    @Test fun bundledModelIsVerifiedOfflineAndMigratesOnlyObsoletePrivateWeights() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "mangajanai").apply { mkdirs() }
        val obsolete = File(directory, "2x-1200p-v1.onnx").apply { writeText("obsolete") }
        val unrelated = File(directory, "reader-data.txt").apply { writeText("keep") }
        MangaJaNai.configure(context)
        val model = MangaJaNai.model()
        assertTrue(MangaJaNai.verify(model))
        assertFalse(obsolete.exists())
        assertEquals("keep", unrelated.readText())
        assertEquals(MangaJaNai.MODEL_BYTES, model.length())
    }

    @Test fun newModelInvalidatesOnlyItsOwnPipelineCache() {
        val manga = ComicReaderConfig(enhanceMode = ComicEnhanceMode.MANGAJANAI).imagePipelineFingerprint()
        val normal = ComicReaderConfig().imagePipelineFingerprint()
        assertTrue(manga.startsWith("enhance-v5-janai-span-mobile-residual"))
        assertTrue(normal.startsWith("enhance-v4-mangajanai1200p"))
        assertNotEquals(manga, ComicReaderConfig(enhanceMode = ComicEnhanceMode.MANGAJANAI, enhanceStrength = 30).imagePipelineFingerprint())
    }
}
