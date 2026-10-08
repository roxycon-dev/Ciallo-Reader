package com.example.ui.comic

import android.graphics.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicEnhancementVisibilityTest {
    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test fun blurredInkCentreIsRestoredInsteadOfClampedBackToItsOriginalValue() {
        val profile = intArrayOf(220, 219, 214, 192, 147, 120, 147, 192, 214, 219, 220)
        val src = Bitmap.createBitmap(IntArray(96 * 96) {
            val x = it % 96
            val v = if (x in 43..53) profile[x - 43] else 220
            Color.rgb(v, v, v)
        }, 96, 96, Bitmap.Config.ARGB_8888)
        val out = ComicEdgeUpscaler.sharpen(src, .6f)
        val centre = Color.red(out.getPixel(48, 48))
        println("VISIBILITY blurred ink centre: original=120 enhanced=$centre")
        assertTrue("Default sharpening must restore the ink centre, which is a local minimum", centre <= 112)
        assertEquals("Flat paper must remain unchanged", 220, Color.red(out.getPixel(20, 48)))
    }

    private fun reference(): Bitmap {
        val b = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(242, 238, 230)) }
        val canvas = Canvas(b)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 34, 30); style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawRect(22f, 20f, 578f, 880f, ink)
        canvas.drawRect(38f, 44f, 560f, 420f, ink)
        canvas.drawOval(170f, 88f, 430f, 352f, ink)
        for (i in 0..18) canvas.drawLine(144f + i * 15, 82f, 185f + i * 9, 208f, ink)
        canvas.drawOval(226f, 214f, 264f, 230f, ink)
        canvas.drawOval(336f, 214f, 374f, 230f, ink)
        canvas.drawArc(260f, 258f, 340f, 294f, 0f, 165f, false, ink)
        ink.strokeWidth = 1.5f
        for (i in 0..17) canvas.drawLine(48f, 462f + i * 15, 190f + i * 8, 712f + i * 7, ink)
        ink.style = Paint.Style.FILL; ink.textSize = 23f
        canvas.drawText("The original fine lines and text.", 38f, 780f, ink)
        canvas.drawText("0123456789  ABC abc  + x = ?", 38f, 824f, ink)
        canvas.drawText("A readable page at normal size.", 38f, 864f, ink)
        ink.color = Color.rgb(156, 185, 208)
        canvas.drawRect(382f, 470f, 553f, 717f, ink)
        ink.color = Color.rgb(62, 89, 110); ink.strokeWidth = 2.5f
        for (i in 0..12) canvas.drawLine(394f + i * 11, 484f, 400f + i * 11, 700f, ink)
        return b
    }

    private fun softened(src: Bitmap): Bitmap {
        val p = pixels(src); val out = p.copyOf(); val w = src.width; val h = src.height
        val weights = intArrayOf(1, 4, 6, 4, 1)
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            var r = 0; var g = 0; var b = 0
            for (dy in -2..2) for (dx in -2..2) {
                val c = p[(y + dy) * w + x + dx]; val weight = weights[dy + 2] * weights[dx + 2]
                r += Color.red(c) * weight; g += Color.green(c) * weight; b += Color.blue(c) * weight
            }
            out[y * w + x] = Color.rgb(r / 256, g / 256, b / 256)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    @Test fun allFourModesAndRegularSharpeningImproveThePageAtReadingSizeAndExportComparisons() {
        val truth = reference(); val src = softened(truth)
        val width = 480; val height = 720
        fun display(b: Bitmap) = Bitmap.createScaledBitmap(b, width, height, true)
        val gt = pixels(display(truth)); val original = display(src); val raw = pixels(original)
        fun error(p: IntArray) = p.indices.sumOf { (Color.red(p[it]) - Color.red(gt[it])).toDouble().pow(2) } / p.size
        val baseline = error(raw)
        val dir = File(System.getProperty("user.dir"), "../artifacts/enhancement-visibility-2026-10-09/quality").apply { mkdirs() }
        fun save(name: String, b: Bitmap) { File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        save("reference", display(truth)); save("off", original)
        val summaries = mutableListOf<String>()
        val settings = ComicEnhanceMode.entries.filter { it != ComicEnhanceMode.OFF }.map {
            it.name to ComicImagePipeline.Toning(enhanceMode = it, enhanceStrength = 60)
        } + ("REGULAR_SHARPEN" to ComicImagePipeline.Toning(sharpen = 60))
        for ((name, tone) in settings) {
            val result = ComicImagePipeline.process(src, ComicImagePipeline.Geometry(), tone)
            val shown = display(result); val actual = pixels(shown)
            val reduction = 100 * (1 - error(actual) / baseline)
            val changed = actual.indices.count { abs(Color.red(actual[it]) - Color.red(raw[it])) >= 5 }
            val centre = Bitmap.createBitmap(shown, 26, 570, 420, 116)
            save(name, shown); save("$name-detail", Bitmap.createScaledBitmap(centre, 840, 232, false))
            val line = "VISIBILITY $name normal-size error-reduction=$reduction% changed>=5=$changed/${actual.size}"
            summaries += line; println(line)
        }
        File(dir.parentFile, "metrics.txt").writeText(summaries.joinToString("\n"))
        // Evaluate the whole set before failing so every mode has visual/numeric evidence.
        for ((name, tone) in settings) {
            val actual = pixels(display(ComicImagePipeline.process(src, ComicImagePipeline.Geometry(), tone)))
            assertTrue("$name needs a meaningful improvement at normal reading size", error(actual) < baseline * .90)
        }
    }

    @Test fun detailPreviewUsesOriginalPixelsAndSharedGeometryWithoutApplyingItTwice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val src = reference()
        val file = File(context.cacheDir, "native-detail-preview.png")
        file.outputStream().use { src.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val loader = ComicPageLoader(context)
        try {
            val ref = ComicPageRef.Local("native-detail-preview", file.absolutePath)
            val geo = ComicImagePipeline.Geometry(cropMode = ComicCropMode.AUTO,
                manualCrop = listOf(.1f, .1f, .9f, .9f), rotationDeg = 90)
            val geometry = ComicImagePipeline.process(src, geo, ComicImagePipeline.Toning())
            val w = min(480, geometry.width); val h = min(480, geometry.height)
            val expected = Bitmap.createBitmap(geometry, (geometry.width - w) / 2, (geometry.height - h) / 2, w, h)
            val original = loader.loadPreview(ref, geo, ComicImagePipeline.Toning(), detail = true)!!
            assertArrayEquals("Native crop must preserve source pixels, including rotation and crop", pixels(expected), pixels(original))
            val tone = ComicImagePipeline.Toning(enhanceMode = ComicEnhanceMode.CAS)
            val enhanced = loader.loadPreview(ref, geo, tone, detail = true)!!
            assertArrayEquals(pixels(ComicImagePipeline.process(expected, ComicImagePipeline.Geometry(), tone)), pixels(enhanced))
            assertArrayEquals("Processing cannot mutate the shared original", pixels(expected), pixels(original))
        } finally { loader.shutdown(); file.delete() }
    }

    @Test fun realPageLoadingRetiresItsUnenhancedPreviewAfterTheFinalImage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val src = softened(reference())
        val file = File(context.cacheDir, "enhancement-final-preview.png")
        file.outputStream().use { src.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val loader = ComicPageLoader(context)
        try {
            val ref = ComicPageRef.Local("enhancement-final-preview", file.absolutePath)
            val config = ComicReaderConfig(enhanceMode = ComicEnhanceMode.SUPER_RES)
            val key = slotCacheKey(ComicSlot(ref, 0), config, ComicBookState())
            val result = loader.loadForDisplay(ref, key, ComicImagePipeline.Geometry(), toneOf(config), visible = true)
            assertNull(result.error)
            assertTrue("The load did publish a readable preview", loader.previewEpoch.value > 0)
            assertNull("Final completion must retire the raw preview", loader.peekReadingPreview(key))
            assertSame(result.bitmap, loader.peekProcessed(key))
            assertTrue(result.bitmap.width > src.width)
        } finally { loader.shutdown(); file.delete() }
    }
}
