package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MangaJaNaiPrefetchTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val tone = ComicImagePipeline.Toning(enhanceMode = ComicEnhanceMode.MANGAJANAI)
    private val geo = ComicImagePipeline.Geometry()

    private fun entry(marker: Int, visible: Boolean = false, ahead: Boolean = true): ComicPageLoader.WindowEntry {
        val file = File(context.cacheDir, "prefetch135-$marker.png")
        Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(-0x1000000 or marker)
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
        return ComicPageLoader.WindowEntry(ComicPageRef.Local("prefetch135-$marker", file.absolutePath),
            "manga-135-$marker", geo, visible, ahead)
    }

    @Test fun waitsForCurrentPageThenGeneratesOnlyThreeForwardPagesInOrder() = runBlocking {
        val calls = CopyOnWriteArrayList<Int>()
        val loader = ComicPageLoader(context, processImage = { bitmap, _, _ ->
            calls.add(bitmap.getPixel(0, 0) and 255)
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        })
        val entries = listOf(entry(0, visible = true), entry(1), entry(2), entry(3), entry(4), entry(5, ahead = false))
        try {
            loader.preloadWindow(entries, tone)
            delay(100)
            assertTrue(calls.isEmpty())
            loader.loadForDisplay(entries[0].ref, entries[0].cacheKey, geo, tone, visible = true)
            withTimeout(5000) { while (loader.peekProcessed(entries[3].cacheKey) == null) delay(10) }
            assertEquals(listOf(0, 1, 2, 3), calls.toList())
            assertNull(loader.peekProcessed(entries[4].cacheKey))
            assertNull(loader.peekProcessed(entries[5].cacheKey))
            assertTrue(loader.cacheLimitForTest() <= 64 shl 20)
        } finally { loader.shutdown() }
    }

    @Test fun promotingInFlightNextPageReusesOneGenerationAndDoesNotDeadlock() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<Int>()
        val loader = ComicPageLoader(context, processImage = { bitmap, _, _ ->
            val marker = bitmap.getPixel(0, 0) and 255
            calls.add(marker)
            if (marker == 1) { started.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        })
        val current = entry(0, visible = true)
        val next = entry(1)
        val later = entry(2)
        try {
            loader.putProcessedForTest(current.cacheKey, Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888))
            loader.preloadWindow(listOf(current, next, later), tone)
            assertTrue(started.await(3, TimeUnit.SECONDS))
            loader.preloadWindow(listOf(entry(1, visible = true), later, entry(3)), tone)
            val visible = async(Dispatchers.Default) { loader.loadForDisplay(next.ref, next.cacheKey, geo, tone, visible = true) }
            finish.countDown()
            withTimeout(5000) { visible.await() }
            assertEquals(1, calls.count { it == 1 })
            assertNotNull(loader.peekProcessed(next.cacheKey))
        } finally { finish.countDown(); loader.shutdown() }
    }

    @Test fun minimalModeStillNeverStartsNeuralPrefetch() = runBlocking {
        val calls = CopyOnWriteArrayList<Int>()
        val loader = ComicPageLoader(context, minimal = true, processImage = { bitmap, _, _ ->
            calls.add(1); bitmap.copy(Bitmap.Config.ARGB_8888, false)
        })
        try {
            loader.preloadWindow(listOf(entry(1), entry(2), entry(3)), tone)
            delay(100)
            assertTrue(calls.isEmpty())
            assertTrue(loader.activePreloadKeysForTest().isEmpty())
        } finally { loader.shutdown() }
    }
}
