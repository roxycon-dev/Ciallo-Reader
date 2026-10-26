package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Color
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

/**
 * Loader cache/lifecycle tests with a controlled decoder seam. Robolectric 4.14.1's
 * built-in region shadow lacks API31 newInstance(path) and ignores inSampleSize.
 * Real BitmapRegionDecoder resolution is separately covered by ComicRegionDeviceTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ComicRegionLifecycleTest.RegionDecoderShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicRegionLifecycleTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun resetDecoderSeam() {
        RegionDecoderShadow.pause = null
        RegionDecoderShadow.decoded.set(0)
        RegionDecoderShadow.recycled.set(0)
        RegionDecoderShadow.lastDecodedBitmap = null
    }

    @Implements(BitmapRegionDecoder::class)
    class RegionDecoderShadow {
        private var source: Bitmap? = null
        class Pause(val entered: CountDownLatch = CountDownLatch(1), val proceed: CountDownLatch = CountDownLatch(1))
        companion object {
            @Volatile var pause: Pause? = null
            val decoded = AtomicInteger()
            val recycled = AtomicInteger()
            @Volatile var lastDecodedBitmap: Bitmap? = null

            @JvmStatic @Implementation
            fun newInstance(path: String): BitmapRegionDecoder {
                val decoder = ReflectionHelpers.callConstructor(BitmapRegionDecoder::class.java,
                    ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType!!, 0L))
                Shadow.extract<RegionDecoderShadow>(decoder).source = BitmapFactory.decodeFile(path)
                    ?: error("Test image decode failed")
                return decoder
            }
        }

        @Implementation fun getWidth(): Int = source!!.width
        @Implementation fun getHeight(): Int = source!!.height
        @Implementation fun isRecycled(): Boolean = source == null
        @Implementation fun recycle() {
            source?.let { it.recycle(); recycled.incrementAndGet() }
            source = null
        }
        @Implementation fun decodeRegion(rect: Rect, options: BitmapFactory.Options): Bitmap {
            decoded.incrementAndGet()
            pause?.let {
                it.entered.countDown()
                check(it.proceed.await(10, TimeUnit.SECONDS)) { "Test did not release region decode" }
            }
            val image = source!!
            val crop = Bitmap.createBitmap(image, rect.left, rect.top, rect.width(), rect.height())
            val scaled = Bitmap.createScaledBitmap(crop,
                (rect.width() / options.inSampleSize).coerceAtLeast(1),
                (rect.height() / options.inSampleSize).coerceAtLeast(1), true)
            // A native region decoder returns independent pixels, not its source storage.
            val result = scaled.copy(Bitmap.Config.ARGB_8888, false)
            lastDecodedBitmap = result
            if (scaled !== crop && scaled !== image) scaled.recycle()
            if (crop !== image) crop.recycle()
            return result
        }
    }

    private fun image(orientation: Int = 1): File = File.createTempFile("comic-region-", if (orientation == 1) ".png" else ".jpg", context.cacheDir).also { file ->
        val bitmap = Bitmap.createBitmap(512, 768, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(44, 97, 151))
        Canvas(bitmap).drawRect(256f, 384f, 512f, 768f, Paint().apply { color = Color.RED })
        file.outputStream().use { bitmap.compress(if (orientation == 1) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        if (orientation != 1) {
            val jpeg = file.readBytes()
            val exif = byteArrayOf(69, 120, 105, 102, 0, 0, 73, 73, 42, 0, 8, 0, 0, 0,
                1, 0, 18, 1, 3, 0, 1, 0, 0, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0, 0)
            val length = exif.size + 2
            val segment = byteArrayOf(255.toByte(), 225.toByte(), (length shr 8).toByte(), length.toByte())
            file.writeBytes(jpeg.copyOfRange(0, 2) + segment + exif + jpeg.copyOfRange(2, jpeg.size))
        }
    }

    @Test fun `unfolded resolution cannot reuse a smaller folded tile`() = runBlocking {
        val file = image()
        val loader = ComicPageLoader(context, minimal = true)
        val ref = ComicPageRef.Local("window-page", file.path)
        val rect = RectF(0f, 0f, 512f, 768f)
        try {
            val small = loader.decodeRegion(ref, rect, targetEdge = 192)!!
            val large = loader.decodeRegion(ref, rect, targetEdge = 768)!!
            assertEquals(128, small.width)
            assertEquals(192, small.height)
            assertEquals(512, large.width)
            assertEquals(768, large.height)
            assertNotSame(small, large)
            assertSame(large, loader.decodeRegion(ref, rect, targetEdge = 768))
            assertEquals(small.getPixel(60, 90), large.getPixel(240, 360))
            assertEquals(2, RegionDecoderShadow.decoded.get())
        } finally { loader.shutdown(); file.delete() }
    }

    @Test fun `concurrent identical region requests share one decoded bitmap`() = runBlocking {
        val file = image()
        val loader = ComicPageLoader(context, minimal = true)
        val ref = ComicPageRef.Local("shared-region", file.path)
        try {
            val result = (0 until 12).map {
                async(Dispatchers.Default) { loader.decodeRegion(ref, RectF(0f, 0f, 512f, 768f), 768)!! }
            }.awaitAll()
            result.forEach { assertSame(result.first(), it) }
            assertEquals(1, RegionDecoderShadow.decoded.get())
        } finally { loader.shutdown(); file.delete() }
    }

    @Test fun `clearing drops regions and shutdown cannot open a new decoder`() = runBlocking {
        val file = image()
        val loader = ComicPageLoader(context, minimal = true)
        val ref = ComicPageRef.Local("lifecycle-page", file.path)
        val rect = RectF(0f, 0f, 512f, 768f)
        try {
            val first = loader.decodeRegion(ref, rect, 768)!!
            loader.clearProcessedCache()
            val second = loader.decodeRegion(ref, rect, 768)!!
            assertNotSame(first, second)
            assertFalse("A caller may still be drawing old pixels", first.isRecycled)
            loader.shutdown()
            assertNull(loader.decodeRegion(ref, rect, 768))
            // Repeated lifecycle teardown is safe.
            loader.shutdown()
            loader.clearProcessedCache()
            assertNull(loader.decodeRegion(ref, rect, 768))
        } finally { loader.shutdown(); file.delete() }
    }


    @Test fun `clearing during a native borrow releases its decoder after use and rejects old publication`() = runBlocking {
        val file = image()
        val loader = ComicPageLoader(context, minimal = true)
        val ref = ComicPageRef.Local("busy-clear", file.path)
        val pause = RegionDecoderShadow.Pause()
        RegionDecoderShadow.pause = pause
        try {
            val request = async(Dispatchers.Default) { loader.decodeRegion(ref, RectF(0f, 0f, 512f, 768f), 768) }
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS))
            loader.clearProcessedCache()
            assertEquals("A decoder must stay alive until decodeRegion returns", 0, RegionDecoderShadow.recycled.get())
            pause.proceed.countDown()
            assertNull("Old generation cannot publish pixels after clearing", request.await())
            assertTrue("Unpublished stale pixels are exclusively owned and must be released", RegionDecoderShadow.lastDecodedBitmap!!.isRecycled)
            assertEquals("Final borrower must drain the deferred reset without another request", 1, RegionDecoderShadow.recycled.get())
            RegionDecoderShadow.pause = null
            assertNotNull(loader.decodeRegion(ref, RectF(0f, 0f, 512f, 768f), 768))
            assertEquals(2, RegionDecoderShadow.decoded.get())
        } finally { pause.proceed.countDown(); loader.shutdown(); file.delete() }
    }

    @Test fun `shutdown during native decode closes the borrowed decoder without recycling a live operation`() = runBlocking {
        val file = image()
        val loader = ComicPageLoader(context, minimal = true)
        val pause = RegionDecoderShadow.Pause()
        RegionDecoderShadow.pause = pause
        try {
            val request = async(Dispatchers.Default) {
                loader.decodeRegion(ComicPageRef.Local("busy-close", file.path), RectF(0f, 0f, 512f, 768f), 768)
            }
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS))
            loader.shutdown()
            assertEquals(0, RegionDecoderShadow.recycled.get())
            pause.proceed.countDown()
            assertNull(request.await())
            assertTrue(RegionDecoderShadow.lastDecodedBitmap!!.isRecycled)
            assertEquals(1, RegionDecoderShadow.recycled.get())
        } finally { pause.proceed.countDown(); loader.shutdown(); file.delete() }
    }

    @Test fun `queued requests freeze mutable crop coordinates before window updates`() = runBlocking {
        val file = image()
        val loader = ComicPageLoader(context, minimal = true)
        val ref = ComicPageRef.Local("moving-crop", file.path)
        val pause = RegionDecoderShadow.Pause()
        RegionDecoderShadow.pause = pause
        try {
            val active = async(Dispatchers.Default) { loader.decodeRegion(ref, RectF(0f, 0f, 512f, 768f), 768) }
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS))
            val crop = RectF(0f, 0f, 256f, 384f)
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { loader.decodeRegion(ref, crop, 768) }
            crop.set(256f, 384f, 512f, 768f)
            pause.proceed.countDown()
            assertNotNull(active.await())
            val result = waiting.await()!!
            assertEquals(Color.rgb(44, 97, 151), result.getPixel(100, 100))
            assertSame(result, loader.decodeRegion(ref, RectF(0f, 0f, 256f, 384f), 768))
        } finally { pause.proceed.countDown(); loader.shutdown(); file.delete() }
    }


    @Test fun `EXIF rotation releases only its private raw intermediate and keeps published pixels alive`() = runBlocking {
        val file = image(orientation = 6)
        val loader = ComicPageLoader(context, minimal = true)
        val ref = ComicPageRef.Local("rotated-region", file.path)
        val rect = RectF(0f, 0f, 768f, 512f)
        try {
            assertEquals(6, loader.exifOrientationOf(file.path))
            val output = loader.decodeRegion(ref, rect, 768)!!
            val raw = RegionDecoderShadow.lastDecodedBitmap!!
            assertNotSame(raw, output)
            assertTrue("EXIF raw intermediate is no longer needed after rotation", raw.isRecycled)
            assertFalse(output.isRecycled)
            assertEquals(768, output.width)
            assertEquals(512, output.height)
            assertSame(output, loader.decodeRegion(ref, rect, 768))
            loader.clearProcessedCache()
            loader.shutdown()
            assertFalse("Published output can still be drawn after the loader closes", output.isRecycled)
        } finally { loader.shutdown(); file.delete() }
    }

    @Test fun `invalid targets and nonfinite crop coordinates return without decoding`() = runBlocking {
        val loader = ComicPageLoader(context, minimal = true)
        val ref = ComicPageRef.Local("invalid", "/missing-region.png")
        try {
            assertNull(loader.decodeRegion(ref, RectF(0f, 0f, 256f, 512f), 0))
            assertNull(loader.decodeRegion(ref, RectF(0f, 0f, 256f, 512f), -1))
            assertNull(loader.decodeRegion(ref, RectF(Float.NaN, 0f, 256f, 512f)))
            assertNull(loader.decodeRegion(ref, RectF(0f, 0f, Float.POSITIVE_INFINITY, 512f)))
        } finally { loader.shutdown() }
    }
}
