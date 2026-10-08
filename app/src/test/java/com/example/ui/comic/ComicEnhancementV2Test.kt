package com.example.ui.comic

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import kotlin.math.*
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicEnhancementV2Test {
    private fun pattern(w: Int, h: Int): Bitmap {
        val random = Random(42)
        val pixels = IntArray(w * h) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also {
        b.getPixels(it, 0, b.width, 0, 0, b.width, b.height)
    }

    @Test fun cnnIsDeterministicAcrossWorkersAndTileBoundaries() {
        val src = pattern(137, 173)
        for (upscale in listOf(false, true)) {
            fun run(edge: Int) = if (upscale) Anime4KCnn.upscale2x(src, 0.6f, edge) else Anime4KCnn.restore(src, 0.6f, edge)
            val reference = run(256)
            repeat(3) {
                val tiled = run(32)
                assertArrayEquals("Identical whole-patch/tiled pixels including the seams", pixels(reference), pixels(tiled))
                tiled.recycle()
                val parallel = run(256)
                assertArrayEquals("Repeated parallel inference must be bit-exact", pixels(reference), pixels(parallel))
                parallel.recycle()
            }
            reference.recycle()
        }
        assertFalse(src.isRecycled)
    }

    @Test fun cnnUsesNativeResolutionRegardlessOfTileBudget() {
        val src = pattern(257, 319)
        val restore = Anime4KCnn.restore(src, .6f, maxEdge = 64)
        val up = Anime4KCnn.upscale2x(src, .6f, maxSrcEdge = 64)
        assertEquals(src.width, restore.width); assertEquals(src.height, restore.height)
        assertEquals(src.width * 2, up.width); assertEquals(src.height * 2, up.height)
    }

    @Test fun everyEngineHasZeroStrengthIdentityAndPreservesFlatPaper() {
        val src = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(231, 220, 209)) }
        for (mode in ComicEnhanceMode.entries) {
            assertSame(src, ComicImagePipeline.process(src, ComicImagePipeline.Geometry(),
                ComicImagePipeline.Toning(enhanceMode = mode, enhanceStrength = 0)))
        }
        for (strength in listOf(.01f, .6f, 1f)) {
            val out = ComicImagePipeline.superResolution(src, strength)
            assertTrue(pixels(out).all { it == src.getPixel(0, 0) })
        }
        for (mode in ComicEnhanceMode.entries) {
            val out = ComicImagePipeline.process(src, ComicImagePipeline.Geometry(),
                ComicImagePipeline.Toning(enhanceMode = mode, enhanceStrength = 60))
            for (p in pixels(out)) for (shift in listOf(0, 8, 16)) {
                assertEquals("$mode must preserve paper colour", (src.getPixel(0, 0) ushr shift and 255).toDouble(),
                    (p ushr shift and 255).toDouble(), 3.0)
            }
        }
    }

    @Test fun displayDeadlineProducesRetryableFailureInsteadOfCancellation() = runBlocking {
        try {
            comicDisplayLoadWithTimeout(30) { delay(1000); "unreachable" }
            fail("A stalled source must leave Loading")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("重试"))
        }
        assertEquals("ready", comicDisplayLoadWithTimeout(1000) { "ready" })
    }

    @Test fun realCancellationIsNotConvertedToRetryableFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val work = async {
            comicDisplayLoadWithTimeout(5000) { started.complete(Unit); awaitCancellation() }
        }
        started.await(); work.cancel()
        try { work.await(); fail("Caller cancellation must propagate") }
        catch (_: CancellationException) { assertTrue(work.isCancelled) }
    }

    @Test fun reconstructionIsCenteredAndDoesNotCreateRingingOrChangeAspectRatio() {
        val src = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.rgb(220, 220, 220))
        Canvas(src).drawRect(0f, 0f, 16f, 48f, Paint().apply { color = Color.rgb(40, 40, 40) })
        val out = ComicImagePipeline.superResolution(src, 1f)
        for (p in pixels(out)) assertTrue(Color.red(p) in 40..220)
        assertEquals(64, out.width); assertEquals(96, out.height)
        assertEquals(260.0, (Color.red(out.getPixel(31, 40)) + Color.red(out.getPixel(32, 40))).toDouble(), 2.0)
        for ((w, h) in listOf(1280 to 1890, 1800 to 2700, 2800 to 2800, 200 to 2800)) {
            val (tw, th) = ComicEdgeUpscaler.targetSize(w, h)
            assertTrue(tw >= w && th >= h)
            assertTrue(max(tw, th) <= 3200)
            assertEquals(w.toDouble() / h, tw.toDouble() / th, .002)
            assertTrue(tw.toLong() * th <= max(6_005_000L, w.toLong() * h))
        }
    }

    @Test fun sharpenSuppressesFlatNoiseAndKeepsLinearGradientsAndChroma() {
        val random = Random(9)
        val src = Bitmap.createBitmap(IntArray(96 * 96) {
            val n = random.nextInt(-3, 4); Color.rgb(128 + n, 138 + n, 148 + n)
        }, 96, 96, Bitmap.Config.ARGB_8888)
        val sharpened = ComicEdgeUpscaler.sharpen(src, 1f)
        val original = pixels(src); val actual = pixels(sharpened)
        assertTrue(actual.indices.sumOf { abs(Color.red(actual[it]) - Color.red(original[it])) }.toDouble() / actual.size < .5)
        for (p in actual) {
            assertEquals(10, Color.green(p) - Color.red(p)); assertEquals(10, Color.blue(p) - Color.green(p))
        }
        val ramp = Bitmap.createBitmap(IntArray(64 * 64) { val v = 80 + it % 64; Color.rgb(v, v, v) }, 64, 64, Bitmap.Config.ARGB_8888)
        assertArrayEquals(pixels(ramp), pixels(ComicEdgeUpscaler.sharpen(ramp, 1f)))
    }

    @Test fun lanczosHasSymmetricPixelCentersAndFiltersDownsamplingAliasing() {
        val impulse = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK); setPixel(4, 4, Color.WHITE) }
        val scaled = ComicImagePipeline.lanczosScaleTo(impulse, 18, 18)
        for (x in 0..17) assertEquals(Color.red(scaled.getPixel(x, 8)), Color.red(scaled.getPixel(17 - x, 8)))
        val stripes = Bitmap.createBitmap(IntArray(128 * 128) { if (it % 128 % 2 == 0) Color.BLACK else Color.WHITE }, 128, 128, Bitmap.Config.ARGB_8888)
        val down = ComicImagePipeline.lanczosScaleTo(stripes, 32, 32)
        for (x in 3..28) assertEquals(128.0, Color.red(down.getPixel(x, 16)).toDouble(), 2.0)
        val tall = Bitmap.createBitmap(900, 1800, Bitmap.Config.ARGB_8888)
        val up = ComicImagePipeline.lanczosScale(tall, 2f)
        assertEquals(1600, up.width); assertEquals(3200, up.height)
    }

    @Test fun alphaAndSourceOwnershipSurviveEveryReconstruction() {
        val src = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.argb(120, 40, 80, 100)) }
        for (out in listOf(Anime4KCnn.restore(src, .6f), Anime4KCnn.upscale2x(src, .6f), ComicImagePipeline.superResolution(src, .6f))) {
            assertTrue(pixels(out).all { Color.alpha(it) == 120 })
            assertFalse(src.isRecycled)
        }
    }

    @Test fun regularSharpeningCannotDownsampleAnEnhancedPage() {
        val src = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val out = ComicImagePipeline.process(src, ComicImagePipeline.Geometry(), ComicImagePipeline.Toning(sharpen = 60))
        assertEquals(src.width, out.width); assertEquals(src.height, out.height)
    }

    @Test fun combinedGeometryToningAndEnhancementNeverRecycleOrMutateTheBorrowedSource() {
        val src = pattern(72, 96)
        val before = pixels(src)
        for (mode in ComicEnhanceMode.entries) {
            val out = ComicImagePipeline.process(src,
                ComicImagePipeline.Geometry(half = ComicSplitHalf.LEFT, rotationDeg = 90),
                ComicImagePipeline.Toning(brightness = 8, saturation = 10, enhanceMode = mode, sharpen = 30))
            assertFalse(src.isRecycled); assertFalse(out.isRecycled)
            assertArrayEquals(before, pixels(src))
            assertTrue(out.width >= 96 && out.height >= 36)
            if (out !== src) out.recycle()
        }
    }

    @Test fun nativeInferenceCancellationReleasesTheWorkerAndDoesNotRecycleInput() = runBlocking {
        val src = pattern(1200, 1800)
        val work = launch(Dispatchers.Default) { runInterruptible { Anime4KCnn.restore(src, 1f) } }
        delay(60); withTimeout(3000) { work.cancelAndJoin() }
        assertTrue(work.isCancelled); assertFalse(src.isRecycled)
    }

    @Test fun visibleCurlSourceSurvivesLargeNeighborCachePressureAcrossAllEngines() {
        val page = Bitmap.createBitmap(2180, 3200, Bitmap.Config.ARGB_8888)
        for (mode in ComicEnhanceMode.entries) {
            val refs = List(3) { ComicPageRef.Remote("source-$mode-$it", "https://example.org/$it.jpg") }
            val config = ComicReaderConfig(enhanceMode = mode)
            val layout = ComicLayout(refs.mapIndexed { i, ref -> ComicSpread(i, listOf(ComicSlot(ref, i))) }, emptyMap())
            val controller = ComicHarismController().apply { this.config = config; this.layout = layout; currentSpreadHint = 0 }
            for (spread in layout.spreads) controller.putCache(slotCacheKey(spread.slots[0], config, ComicBookState()), page)
            assertSame("Current source retained for $mode", page, controller.getCache(slotCacheKey(layout.spreads[0].slots[0], config, ComicBookState())))
            assertTrue(controller.cacheSize() <= 2)
        }
    }

    @Test fun edgeReconstructionImprovesDegradedDiagonalAgainstKnownReferenceAndExportsEvidence() {
        val truth = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        truth.eraseColor(Color.WHITE)
        val c = Canvas(truth); val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 35, 35); strokeWidth = 3f }
        for (i in 0..9) c.drawLine(24f + i * 38, 20f, 200f + i * 28, 490f, p)
        p.textSize = 24f; c.drawText("Comic 012345 AaBb", 20f, 260f, p)
        val low = ComicImagePipeline.lanczosScaleTo(truth, 256, 256)
        val bilinear = Bitmap.createScaledBitmap(low, 512, 512, true)
        val enhanced = ComicImagePipeline.superResolution(low, .6f)
        fun mse(a: Bitmap): Double {
            val gt = pixels(truth); val ap = pixels(a)
            return gt.indices.sumOf { (Color.red(gt[it]) - Color.red(ap[it])).toDouble().pow(2) } / gt.size
        }
        val baseline = mse(bilinear); val actual = mse(enhanced)
        println("QUALITY diagonal PSNR bilinear=${10 * log10(65025 / baseline)} enhanced=${10 * log10(65025 / actual)}")
        assertTrue("Reconstruction error must improve, not just sharpness ($actual vs $baseline)", actual < baseline)
        val dir = File(System.getProperty("user.dir"), "../artifacts/enhancement-2026-10-08/quality").apply { mkdirs() }
        for ((name, bmp) in listOf("reference" to truth, "degraded-bilinear" to bilinear, "enhanced-easu" to enhanced))
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
