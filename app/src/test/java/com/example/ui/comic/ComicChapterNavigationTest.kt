package com.example.ui.comic

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.example.god.GodMomentBinding
import com.example.god.GodMomentContext
import com.example.god.GodPageRef
import com.example.god.rememberGodMomentBinding
import com.github.takahirom.roborazzi.captureRoboImage
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
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night")
class ComicChapterNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    private fun pages(height: Int = 360, count: Int = 3) = (0 until count).map { index ->
        val file = File(compose.activity.cacheDir, "chapter-edge-$index.png")
        val bitmap = Bitmap.createBitmap(240, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(225 - index * 15, 222, 214))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        ComicPageRef.Local("edge-$index", file.absolutePath)
    }

    private fun swipe(direction: ComicDirection, next: Boolean) {
        compose.onNodeWithTag("reader").performTouchInput {
            val start = Offset(width * 0.5f, height * 0.5f)
            val magnitude = if (direction == ComicDirection.TTB) height * 0.3f else width * 0.32f
            val distance = if (direction == ComicDirection.RTL) magnitude else -magnitude
            val signed = if (next) distance else -distance
            val finish = if (direction == ComicDirection.TTB) start + Offset(0f, signed)
                else start + Offset(signed, 0f)
            swipe(start, finish, durationMillis = 400)
        }
        compose.waitForIdle()
    }

    @Test fun pagersAndMagneticNavigateAtBothEdgesFollowingReadingDirection() {
        val store = ComicSettingsStore(compose.activity)
        val refs = pages()
        data class Case(val config: ComicReaderConfig, val next: Boolean)
        val configs = buildList {
            for (direction in ComicDirection.entries) {
                for (animation in listOf(ComicPageAnim.SLIDE, ComicPageAnim.FADE, ComicPageAnim.NONE)) {
                    add(ComicReaderConfig(direction = direction, pageAnim = animation, gestureEdgeSwipe = false))
                }
                add(ComicReaderConfig(mode = ComicMode.DOUBLE, direction = direction, pageAnim = ComicPageAnim.SLIDE,
                    gestureEdgeSwipe = false))
                add(ComicReaderConfig(mode = ComicMode.MAGNETIC, direction = direction, gestureEdgeSwipe = false))
            }
        }
        val cases = configs.flatMap { listOf(Case(it, false), Case(it, true)) }
        cases.forEachIndexed { index, case -> store.saveBookConfig("chapter-case-$index", case.config) }
        var active by mutableStateOf(0)
        var previous = 0
        var next = 0
        var god = 0
        compose.setContent {
            key(active) {
                val case = cases[active]
                MaterialTheme {
                    ComicReaderCore(refs, "交互回归", "第 2 章", "chapter-case-$active",
                        initialPage = if (case.next) refs.lastIndex else 0,
                        modifier = Modifier.testTag("reader"),
                        onPrevChapter = { previous++ }, onNextChapter = { next++ },
                        onGodMoment = { god++ }, onExit = {})
                }
            }
        }
        cases.forEachIndexed { index, case ->
            compose.runOnIdle { active = index }
            compose.waitForIdle()
            val beforePrevious = previous
            val beforeNext = next
            swipe(case.config.direction, case.next)
            compose.runOnIdle {
                val label = "${case.config.mode}/${case.config.direction}/${case.config.pageAnim} next=${case.next}"
                assertEquals(label, beforePrevious + if (case.next) 0 else 1, previous)
                assertEquals(label, beforeNext + if (case.next) 1 else 0, next)
                assertEquals("翻章不能打开神回", 0, god)
            }
        }
    }

    @Test fun scrollModesNavigateOnlyAfterReachingTheActualTopOrBottom() {
        val refs = pages()
        val store = ComicSettingsStore(compose.activity)
        val configs = listOf(ComicMode.WEBTOON, ComicMode.CONTINUOUS).flatMap { mode ->
            listOf(false, true).map { next -> mode to next }
        }
        configs.forEachIndexed { i, (mode, _) -> store.saveBookConfig("scroll-edge-$i",
            ComicReaderConfig(mode = mode, webtoonSnap = false, fit = ComicFit.FIT_PAGE)) }
        var active by mutableStateOf(0)
        var previous = 0
        var next = 0
        compose.setContent { key(active) {
            MaterialTheme { ComicReaderCore(refs, "条漫", "第 2 章", "scroll-edge-$active",
                initialPage = if (configs[active].second) refs.lastIndex else 0,
                modifier = Modifier.testTag("reader"),
                onPrevChapter = { previous++ }, onNextChapter = { next++ }, onExit = {}) }
        } }
        configs.forEachIndexed { index, (_, forward) ->
            compose.runOnIdle { active = index }
            compose.waitForIdle()
            // 最后一项可能高于视口；先完成列表自身滚动，下一次外翻才跨章。
            if (forward) repeat(4) { swipe(ComicDirection.TTB, true) }
            else swipe(ComicDirection.TTB, false)
            compose.runOnIdle {
                assertEquals(index / 2 + 1, previous)
                assertEquals((index + 1) / 2, next)
            }
        }
    }

    @Test fun prefetchedChaptersKeepReportingPagesAndNavigateAtEveryChapterEnd() {
        assertPrefetchedChapterNavigation(ComicReaderConfig(
            direction = ComicDirection.LTR, pageAnim = ComicPageAnim.SLIDE, gestureEdgeSwipe = false))
    }

    @Test fun prefetchedMagneticChaptersKeepReportingPagesAndNavigateAtEveryChapterEnd() {
        assertPrefetchedChapterNavigation(ComicReaderConfig(mode = ComicMode.MAGNETIC,
            direction = ComicDirection.LTR, gestureEdgeSwipe = false))
    }

    @Test fun prefetchedRtlChaptersKeepReportingPagesAndNavigateAtEveryChapterEnd() {
        assertPrefetchedChapterNavigation(ComicReaderConfig(
            direction = ComicDirection.RTL, gestureEdgeSwipe = false))
    }

    private fun assertPrefetchedChapterNavigation(config: ComicReaderConfig) {
        val refs = pages()
        val chapters = (0..4).map { chapter ->
            refs.mapIndexed { page, ref ->
                ComicPageRef.Local("prefetched-$chapter-$page", (ref as ComicPageRef.Local).path)
            }
        }
        val bookKey = "prefetched-chapter-navigation"
        ComicSettingsStore(compose.activity).saveBookConfig(bookKey, config)
        var active by mutableStateOf(0)
        var entryPage by mutableStateOf(0)
        var reportedPage = -1
        var transitions = 0
        var previousTransitions = 0
        compose.setContent {
            MaterialTheme {
                // A prefetch hit replaces the pages directly, without removing the reader
                // for a loading screen or creating a new navigation destination.
                ComicReaderCore(chapters[active], "连续阅读", "第 ${active + 1} 话", bookKey,
                    initialPage = entryPage, modifier = Modifier.testTag("reader"),
                    onPageChanged = { page, _ -> reportedPage = page },
                    onNextChapter = { entryPage = 0; transitions++; active++ },
                    onPrevChapter = { entryPage = Int.MAX_VALUE; previousTransitions++; active-- },
                    onExit = {})
            }
        }
        repeat(4) { chapter ->
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("chapter $chapter starts on page 1", 0, reportedPage) }
            repeat(refs.lastIndex) { page ->
                swipe(config.direction, true)
                compose.runOnIdle {
                    assertEquals("chapter $chapter reports page ${page + 2}", page + 1, reportedPage)
                    assertEquals(chapter, active)
                }
            }
            swipe(config.direction, true)
            compose.runOnIdle {
                assertEquals("each last-page swipe must advance one chapter", chapter + 1, active)
                assertEquals(chapter + 1, transitions)
            }
        }
        repeat(4) { step ->
            swipe(config.direction, false)
            compose.runOnIdle {
                assertEquals("first-page swipe enters the preceding chapter", 3 - step, active)
                assertEquals("previous chapter opens on its last page", refs.lastIndex, reportedPage)
                assertEquals(step + 1, previousTransitions)
            }
            repeat(refs.lastIndex) { page ->
                swipe(config.direction, false)
                compose.runOnIdle {
                    assertEquals("backward pages keep reporting to the active chapter",
                        refs.lastIndex - page - 1, reportedPage)
                }
            }
        }
    }

    @Test fun boundaryGestureCancelsForMultitouchReverseAndCancelEvents() {
        var previous = 0
        var next = 0
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("reader").comicChapterEdgeSwipe(true, ComicDirection.LTR,
                atStart = { true }, atEnd = { true }, onPrevious = { previous++ }, onNext = { next++ }))
        }
        compose.onNodeWithTag("reader").performTouchInput {
            down(Offset(280f, 250f)); moveTo(Offset(100f, 250f)); cancel()
        }
        compose.onNodeWithTag("reader").performTouchInput {
            down(Offset(280f, 250f)); moveTo(Offset(100f, 250f)); moveTo(Offset(270f, 250f)); up()
        }
        compose.onNodeWithTag("reader").performTouchInput {
            down(0, Offset(280f, 250f)); moveTo(0, Offset(100f, 250f))
            down(1, Offset(250f, 250f)); up(1); up(0)
        }
        compose.runOnIdle { assertEquals(0, previous); assertEquals(0, next) }
    }

    @Test fun scrollProgressMovesWithinALongImageAndSliderSeeksToPixelOffsetsInBothModes() {
        val refs = pages(height = 1800, count = 1)
        val modes = listOf(ComicMode.WEBTOON, ComicMode.CONTINUOUS)
        val store = ComicSettingsStore(compose.activity)
        modes.forEach { mode -> store.saveBookConfig("long-progress-$mode",
            ComicReaderConfig(mode = mode, webtoonSnap = false)) }
        var active by mutableStateOf(0)
        compose.setContent { key(active) {
            MaterialTheme { ComicReaderCore(refs, "长图进度", "第 2 章", "long-progress-${modes[active]}",
                initialPage = 0, modifier = Modifier.testTag("reader"), onExit = {}) }
        } }
        fun progress() = compose.onNodeWithContentDescription("阅读进度").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current
        modes.forEachIndexed { index, _ ->
            compose.runOnIdle { active = index }
            compose.waitForIdle()
            compose.waitUntil(5000) {
                compose.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            assertEquals(0f, progress(), 0.001f)
            swipe(ComicDirection.TTB, true)
            val down = progress()
            assertTrue("同一页向下滚动也要更新进度", down > 0f && down < 1f)
            swipe(ComicDirection.TTB, false)
            assertTrue("反向滚动要回退进度", progress() < down)
            compose.onNodeWithText("1 / 1").assertExists()
            compose.onNodeWithContentDescription("阅读进度").performTouchInput {
                click(percentOffset(0.75f, 0.5f))
            }
            compose.waitForIdle()
            assertEquals(0.75f, progress(), 0.015f)
            compose.onNodeWithContentDescription("阅读进度").performTouchInput {
                click(percentOffset(0.99f, 0.5f))
            }
            compose.waitForIdle()
            swipe(ComicDirection.TTB, true)
            assertEquals(1f, progress(), 0.001f)
            compose.onNodeWithContentDescription("阅读进度").performTouchInput {
                click(percentOffset(0f, 0.5f))
            }
            compose.waitForIdle()
            assertEquals(0f, progress(), 0.001f)
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-night")
    fun toolbarOpensGodMomentForCurrentPageAndFitsLargeText() {
        val refs = pages()
        val godPages = refs.map { GodPageRef(it.id, it.path, remote = false) }
        val ctx = GodMomentContext("book", "chapter", "神回入口 · 阅读器视觉验证", "第 13 话", 13)
        var binding: GodMomentBinding? = null
        var currentPage = 1
        compose.setContent {
            val actual = rememberGodMomentBinding(ctx, godPages) { currentPage }
            binding = actual
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                MaterialTheme { ComicReaderCore(refs, ctx.bookTitle, ctx.chapterTitle, "god-toolbar",
                    initialPage = 1, onGodMoment = actual.onOpenCurrent, onExit = {}) }
            }
        }
        compose.onNodeWithContentDescription("标记神回").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一页").assertIsDisplayed()
        compose.onRoot().captureRoboImage("reader_god_toolbar_320dp_font160.png")
        compose.onNodeWithContentDescription("标记神回").performClick()
        compose.runOnIdle {
            assertEquals("chapter", binding!!.request!!.chapterId)
            assertEquals(1, binding!!.request!!.initialPageIndex)
            binding!!.request = null
            currentPage = 2
        }
        compose.onNodeWithContentDescription("标记神回").performClick()
        compose.runOnIdle { assertEquals(2, binding!!.request!!.initialPageIndex) }
    }

    @Test fun toolbarKeepsGodMomentAlignedWithOtherTopBarActions() {
        compose.setContent { MaterialTheme {
            ComicReaderCore(pages(), "新世纪福音战士", "第 13 话", "god-toolbar-regular",
                initialPage = 1, onGodMoment = {}, onExit = {})
        } }
        compose.onNodeWithContentDescription("标记神回").assertIsDisplayed()
        val god = compose.onNodeWithContentDescription("标记神回").fetchSemanticsNode().boundsInRoot
        val toc = compose.onNodeWithContentDescription("目录").fetchSemanticsNode().boundsInRoot
        assertEquals(toc.center.y, god.center.y, 0.5f)
        assertTrue(god.center.x > toc.center.x)
        compose.onRoot().captureRoboImage("reader_god_toolbar.png")
    }
}
