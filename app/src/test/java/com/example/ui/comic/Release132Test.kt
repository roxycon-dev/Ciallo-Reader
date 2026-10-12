package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.PreferencesManager
import com.example.ui.components.*
import com.example.ui.feedback.LocalReduceMotion
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-420dpi")
class Release132Test {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun perChapterResumeSurvivesNewStoreAndOtherChapterVisits() {
        val store = ComicSettingsStore(context)
        store.saveChapterResume("resume132", "ch1", ComicChapterResume(8, 7, 365), flush = true)
        store.saveChapterResume("resume132", "ch2", ComicChapterResume(2), flush = true)
        val reloaded = ComicSettingsStore(context).loadChapterResume("resume132", "ch1")!!
        assertEquals(8, reloaded.startPage(0, 20, false))
        assertEquals(7, reloaded.startPage(8, 20, true))
        assertEquals(365, reloaded.scrollOffset)
        assertEquals(12, reloaded.startPage(12, 20, true))
        assertEquals(19, reloaded.startPage(Int.MAX_VALUE, 20, true))
        assertEquals(0, reloaded.startPage(0, 0, true))
        assertEquals(reloaded, ComicChapterResume.fromJson(reloaded.toJson()))
    }

    @Test fun renderIdsRemainCompatibleAndMinimalPersists() {
        val prefs = PreferencesManager(context)
        for (id in -1..3) { prefs.renderQuality = id; assertEquals(id, PreferencesManager(context).renderQuality) }
        assertEquals(RenderQuality.MINIMAL, RenderQuality.of(-1))
        assertEquals(RenderQuality.LOW, RenderQuality.of(0))
        assertEquals(RenderQuality.HIGH, RenderQuality.of(2))
        assertFalse(RenderQuality.MINIMAL.realtimeGlass)
        assertEquals("极简", RenderQuality.MINIMAL.label)
    }

    @Test fun legacySuperResolutionMigratesAndMangaJaNaiRemainsSelectable() {
        val old = ComicReaderConfig.fromJson(JSONObject().put("enhanceMode", "WAIFU2X").put("enhanceStrength", 76))
        assertEquals(ComicEnhanceMode.SUPER_RES, old.enhanceMode)
        assertEquals(76, old.enhanceStrength)
        assertFalse(ComicEnhanceMode.visibleModes.contains(ComicEnhanceMode.WAIFU2X))
        assertTrue(ComicEnhanceMode.visibleModes.contains(ComicEnhanceMode.MANGAJANAI))
        assertEquals(ComicEnhanceMode.MANGAJANAI, ComicReaderConfig.fromJson(ComicReaderConfig(enhanceMode = ComicEnhanceMode.MANGAJANAI).toJson()).enhanceMode)
        assertTrue(ComicProcessedDiskCache.eligible(ComicEnhanceMode.MANGAJANAI))
        for ((w, h) in listOf(48 to 64, 1200 to 1800, 2800 to 2000, 100 to 16000)) {
            val (ow, oh) = MangaJaNai.outputSize(w, h)
            assertTrue(ow * oh <= 4_005_000)
            assertTrue(maxOf(ow, oh) <= 3200)
            assertTrue(ow <= w * 2 && oh <= h * 2)
        }
        val invalid = File(context.cacheDir, "model-invalid.onnx").apply { writeText("invalid") }
        assertFalse(MangaJaNai.verify(invalid))
    }

    @Test fun minimalAndMangaWindowsStayBoundedAndDoNotStartInvisibleLoads() = kotlinx.coroutines.runBlocking {
        for ((minimal, mode, limit) in listOf(Triple(true, ComicEnhanceMode.OFF, 12 shl 20), Triple(false, ComicEnhanceMode.MANGAJANAI, 20 shl 20))) {
            val loader = ComicPageLoader(context, minimal = minimal)
            val tone = ComicImagePipeline.Toning(enhanceMode = mode)
            val ref = ComicPageRef.Local("invisible132", "/missing")
            val geo = ComicImagePipeline.Geometry()
            loader.ensureWindowCapacity(List(12) { ComicPageLoader.WindowEntry(ref, "large-$it", geo) }, tone)
            assertTrue(loader.cacheLimitForTest() <= limit)
            assertNull(kotlinx.coroutines.withTimeoutOrNull(100) { loader.loadForDisplay(ref, "uncached132", geo, tone, visible = false) })
            loader.shutdown()
        }
    }

    @Test fun mangaNeighborsGetSmallOriginalPreviewsWithoutEnhancedResults() = kotlinx.coroutines.runBlocking {
        val file = File(context.cacheDir, "manga-neighbor132.png")
        Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
        val loader = ComicPageLoader(context)
        val next = ComicPageRef.Local("manga-small-next132", file.absolutePath)
        val geo = ComicImagePipeline.Geometry()
        val tone = ComicImagePipeline.Toning(enhanceMode = ComicEnhanceMode.MANGAJANAI)
        try {
            loader.preloadWindow(listOf(ComicPageLoader.WindowEntry(next, "manga-small-preview132", geo)), tone)
            kotlinx.coroutines.withTimeout(5000) {
                while (loader.peekReadingPreview("manga-small-preview132") == null) kotlinx.coroutines.delay(20)
            }
            val preview = loader.peekReadingPreview("manga-small-preview132")!!
            assertTrue(maxOf(preview.width, preview.height) <= 480)
            assertNull(loader.peekProcessed("manga-small-preview132"))
            assertEquals(android.graphics.Color.BLUE, preview.getPixel(0, 0))
        } finally { loader.shutdown() }
    }

    @Test fun cancellingWhileWaitingForMangaModelLockReturnsImmediately() {
        val lock = MangaJaNai::class.java.getDeclaredField("lock").let {
            it.isAccessible = true; it.get(MangaJaNai) as java.util.concurrent.locks.ReentrantLock
        }
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val started = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        lock.lock()
        val thread = Thread {
            started.countDown()
            try { MangaJaNai.enhance(bitmap, 1f) }
            catch (_: InterruptedException) { cancelled.countDown() }
        }
        try {
            thread.start()
            assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS))
            thread.interrupt()
            assertTrue(cancelled.await(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally { lock.unlock(); thread.join(2000); bitmap.recycle() }
    }

    @Test fun changingImageIdsAndReopeningChapterKeepsItsPage() {
        val store = ComicSettingsStore(context)
        val book = "stable-chapter132"
        store.saveBookConfig(book, ComicReaderConfig(pageAnim = ComicPageAnim.NONE, direction = ComicDirection.LTR))
        store.saveChapterResume(book, "stable-chapter", ComicChapterResume(page = 3), flush = true)
        val file = File(context.cacheDir, "resume132.png")
        Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
        var epoch by mutableIntStateOf(0)
        var open by mutableStateOf(true)
        var reported = -1
        compose.setContent { MaterialTheme {
            if (open) key(epoch) {
                ComicReaderCore(List(6) { ComicPageRef.Local("url-epoch-$epoch-$it", file.absolutePath) }, "续读", "第1话", book,
                    initialPage = 0, toc = listOf(ComicTocEntry("stable-chapter", "第1话")), currentChapterIndex = 0,
                    onPageChanged = { page, _ -> reported = page }, onExit = {})
            }
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(3, reported); open = false }
        compose.waitForIdle()
        compose.runOnIdle { epoch++; reported = -1; open = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(3, reported) }
    }

    @Test fun yearGridUsesSquareCellsAndEqualRowAndColumnGaps() {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalRenderQuality provides RenderQuality.MINIMAL, LocalReduceMotion provides true) {
                ReadingCalendarCard(emptyMap(), {}, Modifier.testTag("calendar132"))
            }
        } }
        compose.onNodeWithText("年").performClick()
        compose.waitForIdle()
        val nodes = compose.onAllNodesWithTag("year_heat_cell", useUnmergedTree = true).fetchSemanticsNodes()
        nodes.forEach { assertEquals(it.size.width, it.size.height) }
        val bounds = nodes.filter { it.boundsInRoot.width >= it.size.width - 1 && it.boundsInRoot.height >= it.size.height - 1 }.map { it.boundsInRoot }
        assertTrue(bounds.size > 7)
        bounds.forEach { assertEquals(it.width, it.height, 1f) }
        val row = bounds.groupBy { it.top }.values.first { it.size > 2 }.sortedBy { it.left }
        val column = bounds.groupBy { it.left }.values.first { it.size > 2 }.sortedBy { it.top }
        val gapX = row[1].left - row[0].right
        val gapY = column[1].top - column[0].bottom
        assertTrue(gapX > 0)
        assertEquals(gapX, gapY, 1f)
        val screenshot = File("../artifacts/release-1.3.2/heatmap132.png").apply { parentFile.mkdirs() }
        compose.onNodeWithTag("calendar132").captureRoboImage(screenshot.absolutePath)
    }

    @Test fun webtoonRestoresOffsetAfterImageHeightArrivesAndAfterReopening() {
        val store = ComicSettingsStore(context)
        val book = "webtoon-offset132"
        store.saveBookConfig(book, ComicReaderConfig(mode = ComicMode.WEBTOON, direction = ComicDirection.TTB, pageAnim = ComicPageAnim.NONE))
        store.saveChapterResume(book, "webtoon-chapter", ComicChapterResume(page = 1, scrollPage = 1, scrollOffset = 450), flush = true)
        val file = File(context.cacheDir, "webtoon-offset132.png")
        Bitmap.createBitmap(360, 1800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.LTGRAY)
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
        var open by mutableStateOf(true)
        var epoch by mutableIntStateOf(0)
        compose.setContent { MaterialTheme {
            if (open) key(epoch) {
                ComicReaderCore(List(4) { ComicPageRef.Local("webtoon-$epoch-$it", file.absolutePath) }, "条漫", "第1话", book,
                    initialPage = 0, toc = listOf(ComicTocEntry("webtoon-chapter", "第1话")), currentChapterIndex = 0, onExit = {})
            }
        } }
        fun awaitRestored() {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("第 2 页").fetchSemanticsNodes().any { it.positionInRoot.y in -451f..-449f }
            }
        }
        awaitRestored()
        compose.runOnIdle { open = false }
        compose.waitForIdle()
        assertEquals(450, ComicSettingsStore(context).loadChapterResume(book, "webtoon-chapter")!!.scrollOffset)
        compose.runOnIdle { epoch++; open = true }
        awaitRestored()
    }

    @Test fun updateHistoryHasShortRowsAndClosesThroughCommonExit() {
        var open by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalRenderQuality provides RenderQuality.MINIMAL, LocalReduceMotion provides true) {
                if (open) ReleaseHistoryDialog { open = false }
            }
        } }
        compose.onNodeWithText("更新目录").assertExists()
        compose.onNodeWithText("1.3.2").assertExists()
        compose.onNodeWithTag("release-history").captureRoboImage(File("../artifacts/release-1.3.2/history132.png").absolutePath)
        compose.onNodeWithText("关闭").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("更新目录").assertDoesNotExist()
    }

    @Test fun minimalSettingsAboutHasClearActionsAndOpensHistory() {
        val prefs = PreferencesManager(context)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalRenderQuality provides RenderQuality.MINIMAL, LocalReduceMotion provides true) {
                com.example.ui.SettingsTabScreen(prefs, com.example.data.BackupManager(context, prefs), emptyList(), {})
            }
        } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("software-info"))
        compose.onNodeWithText("Ciallo Reader").assertIsDisplayed()
        compose.onNodeWithText("检查更新").assertIsDisplayed()
        compose.onNodeWithTag("software-info").captureRoboImage(File("../artifacts/release-1.3.2/about132.png").absolutePath)
        compose.onNodeWithText("更新目录").performClick()
        compose.onNodeWithText("1.3.2").assertIsDisplayed()
    }

    @Test fun minimalNovelTurnsWithoutComposingNeighborsAndMenuBlocksTurns() {
        var next = 0
        var previous = 0
        var center = 0
        var neighbors = 0
        var menu by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalRenderQuality provides RenderQuality.MINIMAL) {
                com.example.ui.pageturn.PageTurnContainer(
                    pageTurnMode = 0, currentContent = {}, nextContent = { neighbors++ }, prevContent = { neighbors++ },
                    onNextPage = { next++ }, onPrevPage = { previous++ }, onClickCenter = { center++ },
                    onClickLeft = { previous++ }, onClickRight = { next++ }, menuVisible = menu,
                    modifier = Modifier.testTag("minimal-reader"),
                )
            }
        } }
        compose.onNodeWithTag("minimal-reader").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, next); assertEquals(0, neighbors) }
        compose.onNodeWithTag("minimal-reader").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(1, previous); menu = true }
        compose.onNodeWithTag("minimal-reader").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("minimal-reader").performTouchInput { click(centerRight) }
        compose.runOnIdle { assertEquals(1, next); assertEquals(1, center); assertEquals(0, neighbors) }
    }
}
