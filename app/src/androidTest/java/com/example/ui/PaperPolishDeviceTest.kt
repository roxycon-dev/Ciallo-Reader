package com.example.ui

import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import android.graphics.Bitmap
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.liquidglass.compose.liquidGlassProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.components.*
import com.example.ui.help.LibraryHelpBottomSheet
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real-HWUI regression for the rapid-input failure, not screenshot/mesh happy paths. */
@RunWith(AndroidJUnit4::class)
class PaperPolishDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private var previousDrawing = true
    @Before fun useHardware() {
        previousDrawing = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
        PullOutSources.latest = null
    }
    @After fun restoreHardware() {
        androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(previousDrawing)
    }
    private val dir get() = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "paper-1.4.16").apply { mkdirs() }
    private fun shot(name: String) {
        compose.onAllNodes(isRoot()).onLast().captureToImage()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
    private fun cardPixels(): Bitmap = compose.onNodeWithTag("card-0").captureToImage().asAndroidBitmap()

    @Test fun denseGlassSurvivesFastFlicksRapidTapsAndRepeatedWindows() {
        var taps = 0; var actions = 0; var open by mutableStateOf(false)
        compose.setContent { MyApplicationTheme {
            val backdrop = rememberGlassPanelBackdrop()
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop, LocalRenderQuality provides RenderQuality.MAX) {
                DialogLiquidGlass {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                        AppActionButton("打开稳定性窗口", { open = true }, fullWidth = true)
                        val list = rememberLazyListState(); list.scrollTiltSource()
                        LazyColumn(Modifier.weight(1f).testTag("glass-list"), state = list,
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items((0 until 60).toList(), key = { it }) { index ->
                                GlassCard(Modifier.fillMaxWidth().testTag("card-$index"), onClick = { taps++ }) {
                                    Text("卡片 $index · 固定标题")
                                    Text("快速操作后这行文字仍应保持位置和完整内容。")
                                    Text("玻璃高光、折射、底纹保持原来的外观。")
                                }
                            }
                        }
                    }
                }
                if (open) AcrylicDialog({ open = false }, title = { Text("稳定性窗口") }, text = {
                    Column { repeat(5) { i -> GlassCard(Modifier.fillMaxWidth()) { Text("实时玻璃 $i") } } }
                }, confirmButton = { PullOutTextButton({ actions++ }) { Text("确认关闭") } })
            }
        } }
        compose.waitForIdle()
        val before = cardPixels()
        repeat(6) {
            compose.onNodeWithTag("card-0").performTouchInput { repeat(12) { click(center) } }
            compose.onNodeWithTag("glass-list").performTouchInput {
                swipe(Offset(center.x, height * .85f), Offset(center.x, height * .15f), 100)
            }
            compose.waitForIdle()
            compose.onNodeWithTag("glass-list").performTouchInput {
                swipe(Offset(center.x, height * .2f), Offset(center.x, height * .85f), 100)
            }
            compose.onNodeWithTag("glass-list").performScrollToIndex(0)
            compose.waitForIdle()
        }
        assertEquals(72, taps)
        compose.mainClock.advanceTimeBy(1800); compose.waitForIdle()
        compose.onNodeWithText("卡片 0 · 固定标题").assertIsDisplayed()
        val after = cardPixels()
        assertEquals(before.width, after.width); assertEquals(before.height, after.height)
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width)
            if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
        val fraction = changed.toFloat() / (before.width * before.height)
        // Detect disappeared cards / displaced text, allowing hardware edge rounding.
        assertTrue("Settled card changed $fraction of its pixels", fraction < .03f)
        before.recycle(); after.recycle()
        shot("glass-after-fast-input")
        repeat(6) { cycle ->
            compose.onNodeWithText("打开稳定性窗口").performTouchInput { click(center) }
            compose.waitForIdle()
            compose.onNodeWithText("实时玻璃 4").assertIsDisplayed()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("确认关闭").performClick().performClick()
            compose.mainClock.advanceTimeBy(32)
            assertEquals(cycle, actions)
            compose.mainClock.advanceTimeBy(1100); compose.mainClock.autoAdvance = true
            compose.waitUntil(5000) { !open }
            assertEquals(cycle + 1, actions)
            compose.onNodeWithText("卡片 0 · 固定标题").assertIsDisplayed()
        }
        File(dir, "rapid-input.txt").writeText("fastFlicks=12\nrapidTaps=$taps\nwindowCycles=6\nactions=$actions\nsettledPixelDifference=$fraction\n")
    }

    @Test fun interruptingAndReversingNeverStrandsALiveSurface() {
        val state = MutableTransitionState(false)
        var life: PaperLifecycle? = null
        compose.setContent { MyApplicationTheme {
            Column { PullOutVisibility(state) {
                life = LocalPaperOwner.current
                GlassCard { Text("可逆的实时内容") }
            } }
        } }
        compose.mainClock.autoAdvance = false
        repeat(12) {
            compose.runOnIdle { state.targetState = true }
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle { state.targetState = false }
            compose.mainClock.advanceTimeBy(32)
        }
        compose.runOnIdle { state.targetState = true }
        compose.mainClock.advanceTimeBy(1400)
        compose.runOnIdle {
            assertTrue(life!!.flat); assertFalse(life!!.closed); assertFalse(life!!.inputBlocked)
            assertEquals(1f, life!!.progress, 0f)
        }
        compose.onNodeWithText("可逆的实时内容").assertIsDisplayed()
        compose.runOnIdle { state.targetState = false }
        compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithText("可逆的实时内容").assertDoesNotExist()
        compose.runOnIdle { assertTrue(life!!.closed); assertFalse(life!!.retained) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun realManualAndCacheKeepOneHeaderAndReopenAfterAnEarlyDismiss() {
        var page by mutableIntStateOf(0)
        compose.setContent { MyApplicationTheme { DialogLiquidGlass {
            Column {
                PullOutButton({ page = 1 }) { Text("手册入口") }
                PullOutButton({ page = 2 }) { Text("缓存入口") }
            }
            if (page == 1) LibraryHelpBottomSheet({ page = 0 }, {})
            if (page == 2) CacheManagementScreen { page = 0 }
        } } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("手册入口").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNode(isRoot() and hasAnyDescendant(hasText("书库使用手册"))).performTouchInput { click(Offset(8f, 8f)) }
        compose.mainClock.advanceTimeBy(1200); compose.mainClock.autoAdvance = true
        compose.waitUntil(5000) { page == 0 }
        compose.onNodeWithText("手册入口").performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("关闭帮助").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("关闭面板").assertCountEquals(0)
        shot("manual-one-header")
        compose.onNodeWithContentDescription("关闭帮助").performClick()
        compose.waitUntil(5000) { page == 0 }
        compose.onNodeWithText("缓存入口").performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("关闭").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("关闭面板").assertCountEquals(0)
        shot("cache-one-header")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.waitUntil(5000) { page == 0 }
    }
    @Test fun livePaperUsesOneShaderForOpeningClosingAndRealSourceSheet() {
        PaperWarpStats.reset()
        var page by mutableIntStateOf(0); var actions = 0
        var life: PaperLifecycle? = null
        compose.setContent { MyApplicationTheme {
            Column {
                PullOutButton({ page = 1 }) { Text("打开轻量纸面") }
                PullOutButton({ page = 2 }) { Text("打开实际书源") }
            }
            if (page == 1) AcrylicDialog({ page = 0 }, title = {
                life = LocalPaperOwner.current; Text("轻量纸面窗口")
            }, text = { Column { repeat(5) { GlassCard(Modifier.fillMaxWidth()) { Text("实时毛玻璃与文字 $it") } } } },
                confirmButton = { PullOutTextButton({ actions++ }) { Text("纸面确认") } })
            if (page == 2) com.example.library.SourcePickerSheet(
                aggregateMode = true, aggregateKind = "comic", currentSource = null,
                comicSources = emptyList(), novelSources = emptyList(), multiLanguageSearch = false,
                onToggleMultiLanguageSearch = {}, onlineKeywordLookup = false, onToggleOnlineKeywordLookup = {},
                onSelectAggregate = { actions++ }, onSelectSource = {}, onManageSources = {}, onDismiss = { page = 0 })
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("打开轻量纸面").performTouchInput { click(center) }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            life?.progress?.let { it in .35f.. .65f } == true
        }
        shot("live-paper-opening")
        assertTrue("Live GPU deformation must actually render", PaperWarpStats.frames > 0)
        assertEquals(0, PaperWarpStats.failures)
        compose.mainClock.advanceTimeBy(1800); compose.mainClock.autoAdvance = true; compose.waitForIdle()
        shot("no-corner-light-dialog")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("纸面确认").performClick()
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            life?.progress?.let { it in .35f.. .65f } == true
        }
        shot("live-paper-closing")
        assertEquals(0, actions)
        compose.mainClock.advanceTimeBy(1500); compose.mainClock.autoAdvance = true
        compose.waitUntil(5000) { page == 0 }; assertEquals(1, actions)
        compose.onNodeWithText("打开实际书源").performTouchInput { click(center) }
        compose.waitForIdle(); shot("source-no-corner-light")
        compose.onNodeWithText("聚合小说（全部）").performClick()
        compose.waitUntil(5000) { page == 0 && actions == 2 }
        assertEquals(0, PaperWarpStats.failures)
        File(dir, "live-warp.txt").writeText("shaderFrames=${PaperWarpStats.frames}\nfailures=${PaperWarpStats.failures}\nmaxGeometryMs=${PaperWarpStats.maxGeometryNanos/1e6}\n")
    }

    @Test fun chooserHandsOffToPageWithoutRetractingTheCallerOrItsTabs() {
        var picker by mutableStateOf(false)
        var dismissals = 0; var navigations = 0
        var pageLife: PaperLifecycle? = null
        compose.setContent { MyApplicationTheme {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "caller") {
                composable("caller") {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        PullOutButton({ picker = true }) { Text("选择入口") }
                        Spacer(Modifier.weight(1f))
                        Text("底栏位置不变", Modifier.testTag("stationary-tabs"))
                    }
                    if (picker) com.example.library.SourcePickerSheet(
                        aggregateMode = true, aggregateKind = "comic", currentSource = null,
                        comicSources = emptyList(), novelSources = emptyList(), multiLanguageSearch = false,
                        onToggleMultiLanguageSearch = {}, onlineKeywordLookup = false, onToggleOnlineKeywordLookup = {},
                        onSelectAggregate = {}, onSelectSource = {},
                        onManageSources = { navigations++; nav.navigate("managed") },
                        onDismiss = { dismissals++; picker = false })
                }
                pullOutPage("managed", onDismiss = { nav.popBackStack() }) { close ->
                    pageLife = LocalPaperOwner.current
                    Column(Modifier.fillMaxSize()) {
                        Text("完整管理页")
                        PullOutButton(close) { Text("返回入口") }
                    }
                }
            }
        } }
        compose.onNodeWithText("选择入口").performClick()
        compose.waitForIdle()
        val tabs = compose.onNodeWithTag("stationary-tabs").fetchSemanticsNode().boundsInRoot
        val manage = compose.onNodeWithText("管理书源").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { manage(); manage() }
        // There is no exit-spring wait before the destination is composed.
        compose.mainClock.advanceTimeBy(96)
        compose.onNodeWithText("选择书源").assertDoesNotExist()
        compose.onNodeWithText("完整管理页").assertExists()
        compose.runOnIdle {
            assertEquals(1, dismissals); assertEquals(1, navigations)
            assertTrue(pageLife!!.origin!!.frozenOpening)
            assertTrue(pageLife!!.allowFullViewport)
            assertTrue(pageLife!!.progress < .5f)
        }
        assertEquals(tabs, compose.onNodeWithTag("stationary-tabs").fetchSemanticsNode().boundsInRoot)
        compose.waitUntil(8000) {
            compose.mainClock.advanceTimeByFrame()
            pageLife!!.progress in .35f.. .65f
        }
        shot("chooser-to-full-page")
        compose.mainClock.advanceTimeBy(1600); compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, PaperWarpStats.failures)
        // System back follows the same return spring as the toolbar action.
        compose.mainClock.autoAdvance = false
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("完整管理页").assertExists()
        compose.mainClock.advanceTimeBy(1500); compose.mainClock.autoAdvance = true
        compose.waitUntil(5000) { compose.onAllNodesWithText("完整管理页").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("选择入口").assertIsDisplayed()
        assertEquals(tabs, compose.onNodeWithTag("stationary-tabs").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun realManagementUsesItsOwnGlassBackgroundAndDeduplicatesNavigation() {
        PaperWarpStats.reset()
        val source = com.example.source.SourceViewModel(compose.activity.application)
        var life: PaperLifecycle? = null
        compose.setContent { MyApplicationTheme {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "caller") {
                composable("caller") {
                    PullOutButton({ nav.openPaperPage("sources"); nav.openPaperPage("sources") }) { Text("实际管理入口") }
                }
                pullOutPage("sources", onDismiss = { nav.popBackStack() }) { close ->
                    life = LocalPaperOwner.current
                    Box(Modifier.fillMaxSize().testTag("actual-management")) {
                        com.example.ui.source.SourceManagementScreen(source, close)
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("实际管理入口").performClick()
        compose.waitUntil(10000) {
            compose.mainClock.advanceTimeByFrame()
            life?.progress?.let { it in .35f.. .65f } == true
        }
        shot("real-source-management-opening")
        compose.mainClock.advanceTimeBy(1600); compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onAllNodesWithTag("actual-management").assertCountEquals(1)
        shot("real-source-management-flat")
        assertTrue(life!!.flat); assertTrue(PaperWarpStats.frames > 0); assertEquals(0, PaperWarpStats.failures)
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("actual-management").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("实际管理入口").assertIsDisplayed()
    }

    @Test fun cacheEntryAndDetailsUseTheirActualButtonsAndOnePaperClock() {
        PaperWarpStats.reset()
        val fixture = File(compose.activity.cacheDir, "ehimg/paper-transition-probe").apply {
            parentFile!!.mkdirs(); writeBytes(ByteArray(4096))
        }
        var open by mutableStateOf(false)
        compose.setContent { MyApplicationTheme {
            PullOutButton({ open = true }) { Text("存储入口") }
            if (open) CacheManagementScreen { open = false }
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("存储入口").performClick()
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            PaperWarpStats.frames > 0
        }
        compose.mainClock.advanceTimeBy(180)
        shot("cache-direct-opening")
        compose.mainClock.advanceTimeBy(1600); compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.waitUntil(15000) {
            compose.onAllNodes(hasText("占用 ·", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("网页图片缓存"))
        val beforeDetail = PaperWarpStats.frames
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("网页图片缓存").performClick()
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            PaperWarpStats.frames > beforeDetail
        }
        compose.mainClock.advanceTimeBy(180)
        shot("cache-detail-opening")
        compose.mainClock.advanceTimeBy(1600); compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(PaperWarpStats.frames > beforeDetail)
        compose.onNode(hasContentDescription("关闭") and hasAnyAncestor(
            hasAnyDescendant(hasText("paper-transition-probe")))).performClick()
        compose.waitUntil(8000) {
            compose.onAllNodesWithText("paper-transition-probe").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(open)
        assertTrue(PaperWarpStats.frames > 0)
        assertEquals(0, PaperWarpStats.failures)
        compose.onAllNodesWithContentDescription("关闭").assertCountEquals(1)
        compose.onNodeWithContentDescription("关闭").performTouchInput { click(center) }
        compose.waitUntil(8000) { !open }
        compose.onNodeWithText("存储入口").assertIsDisplayed()
        fixture.delete()
        File(dir, "handoff-warp.txt").writeText("deformationUpdates=${PaperWarpStats.frames}\nfailures=${PaperWarpStats.failures}\n")
    }

    @Test fun novelFooterTextIsNotClippedByTheSheetOrItsButton() {
        val book = com.example.source.SearchBook("layout-probe", "fixture", "小说详情布局检查", "作者",
            description = "详情介绍。".repeat(100))
        val loader = coil.ImageLoader(compose.activity)
        var open by mutableStateOf(false)
        compose.setContent { MyApplicationTheme {
            PullOutButton({ open = true }) { Text("小说详情入口") }
            if (open) com.example.library.NovelDetailSheet(book, "小说聚合", loader, false, null,
                com.example.download.DownloadState.Idle, onDismiss = { open = false }, onDownload = {},
                onRead = {}, onPause = {}, onResume = {}, onCancel = {})
        } }
        compose.onNodeWithText("小说详情入口").performClick()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1600); compose.waitForIdle()
        val node = compose.onNodeWithText("整本下载到书架", useUnmergedTree = true)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val text = layouts.single()
        val visible = node.fetchSemanticsNode().boundsInRoot
        var offsetY = 0
        var navigationBottom = 0
        compose.runOnIdle {
            fun findCompose(view: android.view.View): android.view.View? {
                if (view.javaClass.simpleName == "AndroidComposeView") return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findCompose(view.getChildAt(i))?.let { return it }
                return null
            }
            val window = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }
            val location = IntArray(2)
            checkNotNull(findCompose(window)).getLocationOnScreen(location); offsetY = location[1]
            navigationBottom = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        }
        val display = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val safeBottom = display.height - navigationBottom; display.recycle()
        File(dir, "novel-footer-bounds.txt").writeText("visible=$visible\nlayout=${text.size}\noverflowHeight=${text.didOverflowHeight}\noffsetY=$offsetY\nsafeBottom=$safeBottom\n")
        shot("novel-footer-whole")
        assertTrue("Footer text must remain above the physical navigation area", visible.bottom + offsetY <= safeBottom + 1f)
        assertFalse("Button must allocate the full text line", text.didOverflowHeight)
        assertTrue("Sheet must not clip the lower half of the download label", visible.height + 1f >= text.size.height)
        compose.onNodeWithContentDescription("关闭面板").performClick()
        compose.waitUntil(8000) { !open }
        loader.shutdown()
    }

    @Test fun commonActionButtonsKeepLargeTextWholeInBothRenderingModes() {
        var minimal by mutableStateOf(false)
        compose.setContent { MyApplicationTheme {
            val base = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, 2f),
                LocalRenderQuality provides if (minimal) RenderQuality.MINIMAL else RenderQuality.HIGH) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    for (size in AppButtonSize.values()) AppActionButton("完整按钮文字", {}, buttonSize = size,
                        modifier = Modifier.fillMaxWidth().testTag("large-button-${size.name}"))
                }
            }
        } }
        for (simple in listOf(false, true)) {
            compose.runOnIdle { minimal = simple }; compose.waitForIdle()
            for (node in compose.onAllNodesWithText("完整按钮文字", useUnmergedTree = true).fetchSemanticsNodes()) {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                checkNotNull(node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
                val text = layouts.single()
                assertFalse(text.didOverflowHeight)
                assertTrue(node.boundsInRoot.height + 1f >= text.size.height)
            }
        }
    }

    @Test fun hiddenBackdropCannotPaintWhiteIntoTheWindow() {
        var visible by mutableStateOf(false)
        val background = androidx.compose.ui.graphics.Color(0xff203129)
        compose.setContent {
            val source = dev.liquidglass.compose.rememberLiquidGlassProviderState()
            Box(Modifier.size(160.dp).background(background).testTag("hidden-provider")) {
                Box(Modifier.matchParentSize().liquidGlassProvider(source, visible)
                    .background(androidx.compose.ui.graphics.Color.White))
            }
        }
        compose.waitForIdle()
        fun centerPixel(): Int {
            val image = compose.onNodeWithTag("hidden-provider").captureToImage().asAndroidBitmap()
            return image.getPixel(image.width / 2, image.height / 2)
        }
        assertEquals("A hidden optical source must paint no pixels", 0xff203129.toInt(), centerPixel())
        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        assertEquals(android.graphics.Color.WHITE, centerPixel())

    }

}
