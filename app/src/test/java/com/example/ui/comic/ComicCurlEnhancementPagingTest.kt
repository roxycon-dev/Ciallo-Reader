package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import fi.harism.curl.CurlPage
import fi.harism.curl.CurlView
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicCurlEnhancementPagingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val state = ComicBookState()

    private fun bitmap(color: Int) = Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(color) }

    // Hold the real processing gate so the regression does not depend on CPU timing.
    private fun processingGate(loader: ComicPageLoader): Semaphore =
        ComicPageLoader::class.java.getDeclaredField("pixelOpGate").let {
            it.isAccessible = true
            it.get(loader) as Semaphore
        }

    private fun controller(loader: ComicPageLoader, cfg: ComicReaderConfig, slots: List<ComicSlot>) =
        ComicHarismController(loader).apply {
            config = cfg
            layout = ComicLayout(slots.mapIndexed { i, slot -> ComicSpread(i, listOf(slot)) },
                slots.indices.associateWith { it })
            currentSpreadHint = 0
        }

    private fun assertInk(expected: Int, texture: Bitmap?) {
        assertNotNull(texture)
        texture!!
        assertEquals(expected, texture.getPixel(texture.width / 2, texture.height / 2))
    }

    @Test fun nextPageUsesAReadablePreviewWhileEveryEnhancementModeIsStillProcessing() = runBlocking {
        val file = File(context.cacheDir, "curl-next-preview.png")
        file.outputStream().use { bitmap(Color.BLUE).compress(Bitmap.CompressFormat.PNG, 100, it) }
        try {
            for (mode in ComicEnhanceMode.entries.filter { it != ComicEnhanceMode.OFF }) {
                val loader = ComicPageLoader(context)
                val gate = processingGate(loader)
                gate.acquire()
                val cfg = ComicReaderConfig(enhanceMode = mode)
                val current = ComicSlot(ComicPageRef.Local("curl-current-${mode.name}", "/unused"), 0)
                val next = ComicSlot(ComicPageRef.Local("curl-next-${mode.name}", file.absolutePath), 1)
                val key = slotCacheKey(next, cfg, state)
                loader.putProcessedForTest(slotCacheKey(current, cfg, state), bitmap(Color.RED))
                val ctrl = controller(loader, cfg, listOf(current, next))
                val load = async { loader.load(next.ref, key, ComicImagePipeline.Geometry(), toneOf(cfg)) }
                try {
                    withTimeout(8000) { loader.previewEpoch.first { loader.peekReadingPreview(key) != null } }
                    assertFalse("Enhancement is deliberately blocked", load.isCompleted)
                    assertNull("The collector has not staged the neighbor yet", ctrl.getCache(key))
                    // PageProvider can run before Compose receives the notification.
                    assertInk(Color.BLUE, ctrl.composeSpread(1, 96, 144, mirrorForRenderer = false))
                    assertTrue(ctrl.syncReadingWindow(listOf(0, 1), cfg, state))
                    assertNotNull("The neighbor preview is staged before the turn", ctrl.getCache(key))
                    assertFalse(ctrl.hasFinalBitmap(key))
                    val previewEpoch = loader.previewEpoch.value
                    gate.release()
                    val result = withTimeout(8000) { load.await() }
                    assertNull(result.error)
                    assertTrue("Final preload results also notify the texture collector",
                        loader.previewEpoch.value > previewEpoch)
                    assertTrue(ctrl.syncReadingWindow(listOf(0, 1), cfg, state))
                    assertSame(result.bitmap, ctrl.getCache(key))
                    assertTrue(ctrl.hasFinalBitmap(key))
                    assertFalse("Repeated notifications do not keep rebuilding textures",
                        ctrl.syncReadingWindow(listOf(0, 1), cfg, state))
                    assertFalse("Late previews cannot replace the final page",
                        ctrl.putPreviewCache(key, bitmap(Color.GREEN)))
                } finally {
                    load.cancel()
                    loader.shutdown()
                }
            }
        } finally { file.delete() }
    }

    @Test fun sharedFinalBitmapWinsOverAStaleCurlPreviewBeforeTheCollectorRuns() {
        val loader = ComicPageLoader(context)
        val cfg = ComicReaderConfig(enhanceMode = ComicEnhanceMode.SUPER_RES)
        val slot = ComicSlot(ComicPageRef.Local("curl-shared-final", "/unused"), 0)
        val ctrl = controller(loader, cfg, listOf(slot))
        val key = slotCacheKey(slot, cfg, state)
        try {
            ctrl.putPreviewCache(key, bitmap(Color.BLUE))
            val final = bitmap(Color.RED)
            loader.putProcessedForTest(key, final)
            assertInk(Color.RED, ctrl.composeSpread(0, 96, 144, mirrorForRenderer = false))
            assertTrue(ctrl.syncReadingWindow(listOf(0), cfg, state))
            assertSame(final, ctrl.getCache(key))
        } finally { loader.shutdown() }
    }

    @Test fun doublePageNeighborsKeepTheirSplitRotationAndRtlIdentityDuringEnhancement() = runBlocking {
        val source = Bitmap.createBitmap(192, 144, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) for (x in 0 until source.width)
            source.setPixel(x, y, if (x < 96) Color.RED else Color.BLUE)
        val file = File(context.cacheDir, "curl-double-preview.png")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val loader = ComicPageLoader(context)
        val gate = processingGate(loader)
        gate.acquire()
        val cfg = ComicReaderConfig(mode = ComicMode.DOUBLE, direction = ComicDirection.RTL,
            enhanceMode = ComicEnhanceMode.SUPER_RES, bookRotation = 90)
        val ref = ComicPageRef.Local("curl-double-next", file.absolutePath)
        val slots = listOf(ComicSlot(ref, 1, ComicSplitHalf.RIGHT), ComicSlot(ref, 1, ComicSplitHalf.LEFT))
        val ctrl = ComicHarismController(loader).apply {
            config = cfg
            twoPage = true
            reversed = true
            layout = ComicLayout(listOf(ComicSpread(0, emptyList()), ComicSpread(1, slots)), mapOf(1 to 1))
            flatUnits = buildCurlFlatUnits(layout!!)
            currentSpreadHint = 0
        }
        val loads = slots.map { slot -> async {
            loader.load(ref, slotCacheKey(slot, cfg, state),
                ComicImagePipeline.Geometry(half = slot.half, rotationDeg = 90), toneOf(cfg))
        } }
        try {
            withTimeout(8000) { loader.previewEpoch.first {
                slots.all { loader.peekReadingPreview(slotCacheKey(it, cfg, state)) != null }
            } }
            assertTrue(loads.none { it.isCompleted })
            assertTrue(ctrl.syncReadingWindow(listOf(0, 1), cfg, state))
            val h = ctrl.toHarism(1)
            assertInk(Color.BLUE, ctrl.composeUnit(h - 1, 144, 96, mirrorForRenderer = false))
            assertInk(Color.RED, ctrl.composeUnit(h, 144, 96, mirrorForRenderer = false))
            slots.forEach { assertFalse(ctrl.hasFinalBitmap(slotCacheKey(it, cfg, state))) }
        } finally {
            loads.forEach { it.cancel() }
            loader.shutdown()
            file.delete()
        }
    }

    @Test fun neighborTextureRefreshWaitsForTheTurnAndIgnoresAnOldSpreadCallback() {
        val loader = ComicPageLoader(context)
        val cfg = ComicReaderConfig(enhanceMode = ComicEnhanceMode.SUPER_RES)
        val slots = List(2) { ComicSlot(ComicPageRef.Local("curl-deferred-$it", "/unused"), it) }
        val ctrl = controller(loader, cfg, slots)
        slots.forEach { ctrl.putCache(slotCacheKey(it, cfg, state), bitmap(Color.BLUE)) }
        val host = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val view = ComicCurlView(host.get())
        ctrl.view = view
        var textureUpdates = 0
        try {
            host.get().setContentView(view)
            view.layout(0, 0, 96, 144)
            view.setPageProvider(object : CurlView.PageProvider {
                override fun getPageCount() = 2
                override fun updatePage(page: CurlPage, width: Int, height: Int, index: Int) {
                    textureUpdates++
                    page.setTexture(ctrl.composeSpread(index, width, height), CurlPage.SIDE_FRONT)
                }
            })
            view.onPageSizeChanged(96, 144)
            val initialUpdates = textureUpdates
            view.autoFlipping = true
            ctrl.refreshDisplay(0)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertEquals("Moving meshes must keep their textures", initialUpdates, textureUpdates)
            ctrl.currentSpreadHint = 1
            ctrl.refreshDisplay(1)
            ctrl.refreshDisplay(0) // A stale callback cannot cancel the new page's pending refresh.
            view.autoFlipping = false
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertTrue("The idle scene receives the new pixels", textureUpdates > initialUpdates)
            assertEquals(1, view.currentIndex)
        } finally {
            ctrl.clearCache()
            ctrl.view = null
            host.pause().stop().destroy()
            loader.shutdown()
        }
    }
}
