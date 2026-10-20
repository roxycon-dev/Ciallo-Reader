package com.example.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.comic.ComicReaderCore
import com.example.ui.comic.ComicReaderConfig
import com.example.ui.comic.ComicSettingsStore
import com.example.ui.comic.ComicDirection
import com.example.ui.comic.ComicPageAnim
import com.example.ui.comic.ComicFit
import com.example.ui.comic.ComicPageRef
import com.example.ui.components.*
import com.example.ui.pageturn.PageTurnContainer
import com.example.ui.reader.*
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TouchZoneInteractionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private fun shot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "touch-zones-1.4.11").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @Test fun customReaderTapDoesNotFlipAndShortSwipeCannotBecomeATap() {
        var pages = 0; var bars = 0
        val zone = TouchZone(left = 0f, top = 0f, right = 1f, bottom = 1f, tap = TouchAction.CONTROLS, distanceDp = 100f)
        compose.setContent { MyApplicationTheme {
            PageTurnContainer(pageTurnMode = 2, touchBinding = ReaderTouchBinding(listOf(zone)) { if (it == TouchAction.CONTROLS) bars++ },
                currentContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) }, nextContent = {}, prevContent = {},
                onNextPage = { pages++ }, onPrevPage = { pages-- }, onClickCenter = { bars++ }, onClickLeft = { pages-- }, onClickRight = { pages++ },
                modifier = Modifier.testTag("reader"))
        } }
        compose.onNodeWithTag("reader").performTouchInput { click(center) }
        compose.waitForIdle(); assertEquals(1, bars); assertEquals(0, pages)
        compose.onNodeWithTag("reader").performTouchInput { swipe(center, center - Offset(55f, 0f), 200) }
        compose.waitForIdle(); assertEquals(1, bars); assertEquals(0, pages)
        compose.onNodeWithTag("reader").performTouchInput { swipe(Offset(width - 40f, center.y), Offset(40f, center.y), 500) }
        compose.waitForIdle(); assertEquals(1, bars); assertEquals(1, pages)
    }

    @Test fun scrollingReaderHasOneVerticalGestureOwnerAndNoHorizontalPageTurn() {
        var verticalTurns = 0; var outerTurns = 0; var taps = 0
        val zones = listOf(TouchZone(left = 0f, top = 0f, right = 1f, bottom = 1f, distanceDp = 60f))
        compose.setContent { MyApplicationTheme {
            PageTurnContainer(pageTurnMode = 4, touchBinding = ReaderTouchBinding(zones),
                currentContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                    .touchZoneGestures(zones, { 60f }, { when(it) { TouchAction.NEXT -> verticalTurns++; TouchAction.CONTROLS -> taps++; else -> Unit } }, vertical = true)) },
                nextContent = {}, prevContent = {}, onNextPage = { outerTurns++ }, onPrevPage = { outerTurns-- },
                onClickCenter = {}, onClickLeft = {}, onClickRight = {}, modifier = Modifier.testTag("vertical-reader"))
        } }
        compose.onNodeWithTag("vertical-reader").performTouchInput { swipe(Offset(center.x, height * .7f), Offset(center.x, height * .3f), 500) }
        compose.waitForIdle(); assertEquals(1, verticalTurns); assertEquals(0, outerTurns); assertEquals(0, taps)
        compose.onNodeWithTag("vertical-reader").performTouchInput { click(center) }
        compose.waitForIdle(); assertEquals(1, taps); assertEquals(1, verticalTurns)
    }

    @Test fun editorSavesIndependentActionsAndCanReturnToTheExistingDefaults() {
        var saved: List<TouchZone>? = null
        var dismissed = false
        compose.setContent { MyApplicationTheme {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp)) {
                    Text("第一章 · 风从书页间经过", style = MaterialTheme.typography.titleLarge)
                    repeat(18) { Text("窗外的光落在书页上。故事沿着熟悉的行距，保持着原本的大小与位置。", modifier = Modifier.padding(vertical = 8.dp)) }
                }
                TouchZoneEditor(listOf(TouchZone(distanceDp = 80f)), listOf(TouchZone()), 64f,
                    onSave = { saved = it }, onDismiss = { dismissed = true })
            }
        } }
        compose.onNodeWithText("自定义触控分区").assertExists()
        shot("editor-novel")
        compose.onNodeWithText("添加分区").performClick()
        compose.onNodeWithText("试着用自己最舒适的方式滑动一页").assertExists()
        shot("calibration-guide")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { dismissed }
        assertEquals(2, saved!!.size)
        assertEquals(TouchAction.CONTROLS, saved!!.last().tap)
        assertEquals(SwipeAction.PAGE, saved!!.last().swipe)
    }
    @Test fun calibrationStoresThePhysicalFingerDistanceAndCanBeManuallyAdjusted() {
        var saved: List<TouchZone>? = null
        var dismissed = false
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        compose.setContent { MyApplicationTheme {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp)) {
                    Text("第一章 · 风从书页间经过", style = MaterialTheme.typography.titleLarge)
                    repeat(18) { Text("窗外的光落在书页上。故事沿着熟悉的行距，保持着原本的大小与位置。", modifier = Modifier.padding(vertical = 8.dp)) }
                }
                TouchZoneEditor(listOf(TouchZone(distanceDp = 80f)), listOf(TouchZone()), 64f,
                    onSave = { saved = it }, onDismiss = { dismissed = true })
            }
        } }
        compose.onNodeWithText("重新校准").performClick()
        var measured = 0f
        compose.onNodeWithContentDescription("真实阅读区域：拖动区域移动，四角缩放；校准时在选中区域滑动").performTouchInput {
            val length = minOf(width * .52f, 50f * density)
            measured = length / density
            val start = Offset(width * .23f, height * .42f)
            swipe(start, start + Offset(length, 0f), 500)
        }
        compose.onNodeWithText("✓ 已记住你的手势 · 可继续微调").assertExists()
        compose.onNodeWithText("触发距离 ${kotlin.math.round(measured).toInt()} dp").assertExists()
        shot("calibration-complete")
        compose.onNodeWithText("设置功能").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { set -> set(96f) }
        compose.onNodeWithText("触发距离 96 dp").assertExists()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { dismissed }
        assertEquals(96f, saved!!.single().distanceDp!!, .01f)
    }

    @Test fun editorUsesTheEngineViewportAndTryModeTurnsTheRealPageWithoutResizing() {
        var editing by mutableStateOf(true)
        var preview by mutableStateOf<List<TouchZone>?>(null)
        var previewActive by mutableStateOf(false)
        var viewport by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
        var pages = 0
        var saved: List<TouchZone>? = null
        val original = listOf(TouchZone(left = 0f, top = 0f, right = 1f, bottom = 1f, distanceDp = 60f))
        compose.setContent { MyApplicationTheme {
            Box(Modifier.fillMaxSize()) {
                PageTurnContainer(pageTurnMode = 2,
                    touchBinding = ReaderTouchBinding(if (previewActive) preview else original, onViewport = { viewport = it }),
                    currentContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { Text("原尺寸的真实阅读页面", Modifier.padding(24.dp)) } },
                    nextContent = {}, prevContent = {}, onNextPage = { pages++ }, onPrevPage = { pages-- },
                    onClickCenter = {}, onClickLeft = {}, onClickRight = {}, modifier = Modifier.testTag("real-page"))
                if (editing) TouchZoneEditor(original, original, 60f, viewportInWindow = viewport,
                    onPreview = { zones, active -> preview = zones; previewActive = active },
                    onSave = { saved = it }, onDismiss = { editing = false })
            }
        } }
        val pageBefore = compose.onNodeWithTag("real-page").fetchSemanticsNode().boundsInRoot
        val editorBounds = compose.onNodeWithContentDescription("真实阅读区域：拖动区域移动，四角缩放；校准时在选中区域滑动").fetchSemanticsNode().boundsInRoot
        assertEquals(pageBefore, editorBounds)
        compose.onNodeWithText("试读").performClick()
        compose.onNodeWithText("正在真实试读").assertExists()
        compose.onNodeWithTag("real-page").performTouchInput { swipe(Offset(width * .8f, center.y), Offset(width * .2f, center.y), 500) }
        compose.waitForIdle(); assertEquals(1, pages)
        assertEquals(pageBefore, compose.onNodeWithTag("real-page").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("返回编辑").performClick()
        compose.onNodeWithContentDescription("取消编辑").performClick()
        compose.waitForIdle(); assertFalse(editing); assertFalse(previewActive); assertNull(saved)
    }

    @Test fun paperModalKeepsTheTriggerHiddenUntilTheIndependentClosingSpringEnds() {
        var open by mutableStateOf(false)
        var actions = 0
        compose.setContent { MyApplicationTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                PullOutButton(onClick = { open = true }) { Text("书库使用手册") }
                if (open) AcrylicDialog(onDismissRequest = { open = false }, title = { Text("书库使用手册", style = MaterialTheme.typography.titleLarge) },
                    text = { Text("从书源搜索书籍，下载到书架后离线阅读。整张纸面包含标题、正文和按钮。") },
                    confirmButton = { PullOutTextButton(onClick = { actions++ }) { Text("完成") } })
            }
        } }
        compose.onNodeWithText("书库使用手册").performTouchInput { click() }
        val originalSource = PullOutSources.latest
        compose.onNodeWithText("完成").assertExists()
        compose.waitUntil(5000) { originalSource?.hidden == true }
        compose.mainClock.advanceTimeBy(1400)
        compose.waitForIdle(); shot("paper-open")
        assertTrue("The original center-aligned trigger must be hidden", originalSource?.hidden == true)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("完成").performClick()
        compose.mainClock.advanceTimeBy(144)
        compose.waitForIdle()
        assertEquals(0, actions)
        shot("paper-closing")
        compose.mainClock.advanceTimeBy(2000)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5000) { !open }
        assertEquals(1, actions)
        assertFalse(originalSource?.hidden == true)
        compose.onNodeWithText("书库使用手册").assertExists()
    }
    @Test fun rangeAndDiscreteLensesRemainUsableWithARealAndroidCanvas() {
        var low by mutableFloatStateOf(18f); var high by mutableFloatStateOf(64f)
        var size by mutableFloatStateOf(2f)
        compose.setContent { MyApplicationTheme {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(18.dp)) {
                Text("下载章节 $low – $high")
                LiquidSlider(low, {}, rangeEnd = high, minGap = 1f, valueRange = 1f..100f,
                    onRangeChange = { a, b -> low = a; high = b }, modifier = Modifier.testTag("range"), formatLabel = { "第 ${it.toInt()} 章" })
                Text("字号档位 $size")
                LiquidSlider(size, { size = it }, valueRange = 0f..4f, steps = 3, modifier = Modifier.testTag("steps"), formatLabel = { listOf("小", "标准", "中", "大", "特大")[it.toInt().coerceIn(0, 4)] })
            }
        } }
        compose.onNodeWithTag("range").performTouchInput { swipe(Offset(width * .2f, height * .62f), Offset(width * .95f, height * .62f), 600) }
        compose.waitForIdle(); assertTrue(high - low >= .999f)
        compose.onNodeWithTag("steps").performTouchInput { click(Offset(width * .75f, height * .62f)) }
        compose.waitForIdle(); assertEquals(3f, size, 0f)
        shot("sliders-range-and-steps")
    }

    private fun mountComic(config: ComicReaderConfig): java.util.concurrent.atomic.AtomicInteger {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "touch-device-${System.nanoTime()}"
        val pages = (0..2).map { index ->
            val file = File(context.cacheDir, "touch-comic-$index.png")
            val bitmap = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(listOf(0xffd6e7de.toInt(), 0xffe7ddd0.toInt(), 0xffd2dde9.toInt())[index])
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xff244335.toInt(); textSize = 38f }
            canvas.drawText("PAGE ${index + 1}", 38f, 80f, paint)
            repeat(5) { row -> canvas.drawRoundRect(30f, 120f + row * 125f, 450f, 230f + row * 125f, 12f, 12f, paint) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            ComicPageRef.Local("touch-$index", file.absolutePath)
        }
        ComicSettingsStore(context).saveBookConfig(key, config.copy(fit = ComicFit.FIT_PAGE, doubleTapZoom = false, longPressZoom = false,
            gesturePinchClose = false, touchZones = listOf(TouchZone(left = 0f, top = 0f, right = 1f, bottom = 1f, tap = TouchAction.CONTROLS, distanceDp = 80f))))
        val current = java.util.concurrent.atomic.AtomicInteger(-1)
        compose.setContent { MyApplicationTheme {
            ComicReaderCore(pages, "触控漫画验收", null, key, 0, modifier = Modifier.testTag("comic"),
                onPageChanged = { page, _ -> current.set(page) }, onExit = {})
        } }
        compose.waitUntil(30_000) { current.get() == 0 }
        compose.waitForIdle()
        // The existing comic reader intentionally starts with its controls visible.
        compose.onNodeWithTag("comic").performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onNodeWithText("触控漫画验收").assertDoesNotExist()
        return current
    }

    @Test fun mangaUsesCustomTapAndSwipeActionsOnThePagedReader() {
        android.util.Log.i("PaperProbe", "mount")
        val page = mountComic(ComicReaderConfig(direction = ComicDirection.LTR, pageAnim = ComicPageAnim.SLIDE))
        android.util.Log.i("PaperProbe", "mounted")
        compose.onNodeWithTag("comic").performTouchInput { click(center) }
        compose.waitForIdle(); assertEquals(0, page.get())
        compose.onNodeWithText("触控漫画验收").assertExists()
        compose.onNodeWithTag("comic").performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("comic").performTouchInput { swipe(Offset(width * .8f, center.y), Offset(width * .2f, center.y), 500) }
        compose.waitUntil(15_000) { page.get() == 1 }
        shot("manga-paged-custom-zones")
        val originalBounds = compose.onNodeWithTag("comic").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("comic").performTouchInput { click(center) }
        if (InstrumentationRegistry.getArguments().getString("isolatePaper") == "true") {
            compose.runOnIdle { com.example.ui.components.PullOutSources.latest = null }
        }
        android.util.Log.i("PaperProbe", "opening settings")
        compose.onNodeWithContentDescription("阅读设置").performTouchInput { click(center) }
        android.util.Log.i("PaperProbe", "settings clicked")
        compose.onNodeWithText("手势").performScrollTo().performClick()
        android.util.Log.i("PaperProbe", "gesture tab clicked")
        compose.onNodeWithText("可视化编辑功能分区").performScrollTo().performClick()
        compose.onNodeWithText("自定义触控分区").assertExists()
        val editingBounds = compose.onNodeWithContentDescription("真实阅读区域：拖动区域移动，四角缩放；校准时在选中区域滑动").fetchSemanticsNode().boundsInRoot
        assertEquals(originalBounds, editingBounds)
        compose.onNodeWithText("漫画阅读设置").assertDoesNotExist()
        shot("editor-manga-real-page")
        compose.onNodeWithContentDescription("取消编辑").performClick()
    }

    @Test fun mangaPinchAndPanTakePriorityOverCustomPageSwipes() {
        val page = mountComic(ComicReaderConfig(direction = ComicDirection.LTR, pageAnim = ComicPageAnim.SLIDE))
        compose.onNodeWithTag("comic").performTouchInput {
            pinch(Offset(width * .4f, center.y), Offset(width * .6f, center.y),
                Offset(width * .15f, center.y), Offset(width * .85f, center.y), 600)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("comic").performTouchInput { swipe(Offset(width * .8f, center.y), Offset(width * .2f, center.y), 500) }
        compose.waitForIdle(); assertEquals(0, page.get())
        shot("manga-zoom-pan-priority")
    }

    @Test fun rtlNativeCurlHonorsTheSameCustomDistanceAndDirection() {
        val page = mountComic(ComicReaderConfig(direction = ComicDirection.RTL, pageAnim = ComicPageAnim.CURL))
        compose.onNodeWithTag("comic").performTouchInput { swipe(center, center + Offset(55f, 0f), 200) }
        compose.waitForIdle(); assertEquals(0, page.get())
        compose.onNodeWithTag("comic").performTouchInput { swipe(Offset(width * .2f, center.y), Offset(width * .8f, center.y), 500) }
        compose.waitUntil(15_000) { page.get() == 1 }
        shot("manga-native-curl-rtl")
    }
}
