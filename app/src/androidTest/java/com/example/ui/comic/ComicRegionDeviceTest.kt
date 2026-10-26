package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Android BitmapRegionDecoder; no Robolectric decoder shadow. */
@RunWith(AndroidJUnit4::class)
class ComicRegionDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun image(): File = File.createTempFile("comic-region-device-", ".png", context.cacheDir).also { file ->
        val bitmap = Bitmap.createBitmap(512, 768, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(44, 97, 151))
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun foldedToUnfoldedTargetKeepsNativeResolutionAndLifecycle() = runBlocking {
        val file = image()
        val loader = ComicPageLoader(context, minimal = true)
        val ref = ComicPageRef.Local("native-window-page", file.path)
        val rect = RectF(0f, 0f, 512f, 768f)
        try {
            val small = loader.decodeRegion(ref, rect, 192)!!
            val large = loader.decodeRegion(ref, rect, 768)!!
            assertEquals(128, small.width)
            assertEquals(192, small.height)
            assertEquals(512, large.width)
            assertEquals(768, large.height)
            assertNotSame(small, large)
            assertSame(large, loader.decodeRegion(ref, rect, 768))
            assertEquals(small.getPixel(60, 90), large.getPixel(240, 360))
            loader.clearProcessedCache()
            assertNotSame(large, loader.decodeRegion(ref, rect, 768))
            assertFalse(large.isRecycled)
            loader.shutdown()
            assertNull(loader.decodeRegion(ref, rect, 768))
        } finally { loader.shutdown(); file.delete() }
    }

    @Test fun simultaneousNativeRequestsReuseTheCompletedTile() = runBlocking {
        val file = image()
        val loader = ComicPageLoader(context, minimal = true)
        val ref = ComicPageRef.Local("native-shared-region", file.path)
        try {
            val bitmaps = (0 until 12).map {
                async(Dispatchers.Default) { loader.decodeRegion(ref, RectF(0f, 0f, 512f, 768f), 768)!! }
            }.awaitAll()
            bitmaps.forEach { assertSame(bitmaps.first(), it) }
        } finally { loader.shutdown(); file.delete() }
    }
}
