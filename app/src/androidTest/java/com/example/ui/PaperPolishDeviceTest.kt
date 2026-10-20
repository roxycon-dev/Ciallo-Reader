package com.example.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.discover.DiscoverFilters
import com.example.discover.DiscoverSourcePicker
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.components.*
import com.example.ui.help.LibraryHelpBottomSheet
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.clickableWithFeedback
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.After
import org.junit.Before
import org.junit.rules.TestName
import org.junit.Test
import org.junit.runner.RunWith

/** Focused real-HWUI regression for the 1.4.13 polish; no network or destructive cache actions. */
@RunWith(AndroidJUnit4::class)
class PaperPolishDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    @get:Rule val name = TestName()
    private var previousDrawing = true
    @Before fun useRealHardwareDrawing() {
        previousDrawing = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
    }
    private val dir get() = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "paper-1.4.13").apply { mkdirs() }
    private fun shot(name: String) {
        // Flush a real window frame, not just Compose's virtual animation clock.
        val window = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        File(dir, "$name-window.png").outputStream().use { window.compress(Bitmap.CompressFormat.PNG, 100, it) }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
    }
    private fun stats(name: String) = File(dir, "$name.txt").writeText(
        "meshFrames=${PaperRenderStats.gpuFrames}\nfallbackFrames=${PaperRenderStats.fallbackFrames}\n" +
            "captureFailures=${PaperRenderStats.captureFailures}\nsnapshots=${PaperRenderStats.snapshots}\n" +
            "buttonCaptures=${PaperRenderStats.buttonCaptures}\nmaxMeshBuildMs=${PaperRenderStats.maxMeshBuildNanos / 1e6}\n" +
            "maxCaptureMs=${PaperRenderStats.maxCaptureNanos / 1e6}\n" +
            "asyncSnapshots=${PaperRenderStats.asyncSnapshots}\nlegacySnapshots=${PaperRenderStats.legacySnapshots}\nreadbackFallbacks=${PaperRenderStats.readbackFallbacks}\n")
    @After fun saveCaptureDiagnostics() {
      try {
        val source = PullOutSources.latest
        var clocks = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val field = Class.forName("com.example.ui.components.PullOutMotionKt").getDeclaredField("paperLifecycles").apply { isAccessible = true }
            val entries = field.get(null) as Map<*, *>
            clocks = entries.entries.mapNotNull { (state, value) ->
                val life = value as PaperLifecycle
                val target = state as? androidx.compose.animation.core.MutableTransitionState<*> ?: return@mapNotNull null
                "target=${target.targetState}, p=${life.progress}, v=${life.spring.velocity}, ready=${life.ready}, closed=${life.closed}, retained=${life.retained}, flat=${life.flat}, registered=${life.registered}, attached=${life.hostAttached}"
            }.joinToString("\n")
        }
        File(dir, "${name.methodName}-state.txt").writeText("phase=${PaperRenderStats.capturePhase}\nrecords=${PaperRenderStats.recordings}\n" +
            "snapshots=${PaperRenderStats.snapshots}\nbuttons=${PaperRenderStats.buttonCaptures}\nmesh=${PaperRenderStats.gpuFrames}\n" +
            "fallback=${PaperRenderStats.fallbackFrames}\nblendLayers=${PaperRenderStats.blendLayers}\ntexturePasses=${PaperRenderStats.texturePasses}\n" +
            "asyncSnapshots=${PaperRenderStats.asyncSnapshots}\nlegacySnapshots=${PaperRenderStats.legacySnapshots}\nreadbackFallbacks=${PaperRenderStats.readbackFallbacks}\n" +
            "source=${source?.bounds}, hidden=${source?.hidden}, disposed=${source?.disposed}, attached=${source?.view?.get()?.isAttachedToWindow}\n$clocks\n")
        shot("${name.methodName}-screen")
      } finally { androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(previousDrawing) }
    }

    @Test fun denseGlassKeepsItsMeshAndSourceAcrossEightOpenCloseCycles() {
        PaperRenderStats.reset(); PullOutSources.latest = null
        var open by mutableStateOf(false)
        var actions = 0
        var contentDraws = 0
        var activePaper: PaperLifecycle? = null
        compose.setContent { MyApplicationTheme {
            val backdrop = rememberGlassPanelBackdrop()
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                DialogLiquidGlass {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(6) { GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) { Text("背景玻璃层 $it") } }
                        AppActionButton("打开毛玻璃测试", { open = true }, fullWidth = true)
                    }
                }
                if (open) AcrylicDialog(onDismissRequest = { open = false }, title = { activePaper = LocalPaperOwner.current; Text("完整玻璃纸面") },
                    text = { Column(Modifier.drawWithContent { contentDraws++; drawContent() }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(6) { GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
                            PullOutVisibility(true) { Text("折射、高光和实时模糊 $it", style = MaterialTheme.typography.bodyMedium) }
                        } }
                    } }, confirmButton = { AppActionButton("收回纸面", { actions++ }) })
            }
        } }
        repeat(8) { cycle ->
            val before = PaperRenderStats.gpuFrames
            if (cycle == 0) compose.mainClock.autoAdvance = false
            compose.onNodeWithText("打开毛玻璃测试").performTouchInput { click(center) }
            compose.waitUntil(30_000) {
                if (cycle == 0) compose.mainClock.advanceTimeByFrame()
                PullOutSources.latest?.hidden == true && PaperRenderStats.gpuFrames > before &&
                    (cycle != 0 || activePaper?.progress?.let { it in .35f.. .85f } == true)
            }
            if (cycle == 0) {
                shot("glass-opening-mesh")
                compose.mainClock.advanceTimeBy(1800); compose.mainClock.autoAdvance = true
            }
            compose.waitForIdle()
            val source = PullOutSources.latest
            assertTrue("Source owned during cycle $cycle", source?.hidden == true)
            compose.onNodeWithText("完整玻璃纸面").assertExists()
            if (cycle == 0) shot("glass-flat")
            val closingSnapshot = PaperRenderStats.snapshots
            if (cycle == 0) compose.mainClock.autoAdvance = false
            compose.onNodeWithText("收回纸面").performClick()
            if (cycle == 0) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitUntil(30_000) {
                    compose.mainClock.advanceTimeByFrame()
                    PaperRenderStats.snapshots > closingSnapshot && activePaper?.ready == true &&
                        activePaper?.progress?.let { it in .25f.. .8f } == true
                }
                shot("glass-closing-mesh")
                compose.mainClock.advanceTimeBy(1800); compose.mainClock.autoAdvance = true
            }
            compose.waitUntil(30_000) { !open }
            compose.waitForIdle()
            assertFalse("Source returned during cycle $cycle", source?.hidden == true)
            assertEquals(cycle + 1, actions)
        }
        stats("dense-glass")
        File(dir, "dense-glass-content-draws.txt").writeText("contentDraws=$contentDraws\nmeshFrames=${PaperRenderStats.gpuFrames}\n")
        assertTrue("Moving paper must not repeatedly redraw its live glass content", contentDraws < PaperRenderStats.gpuFrames / 4)
        assertTrue(PaperRenderStats.gpuFrames > 80)
        assertEquals(0, PaperRenderStats.fallbackFrames)
        assertEquals(0, PaperRenderStats.captureFailures)
        assertEquals(16, PaperRenderStats.snapshots)
        assertEquals(16, PaperRenderStats.buttonCaptures)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            assertEquals("Every source and card must use asynchronous readback", 32, PaperRenderStats.asyncSnapshots)
            assertEquals(0, PaperRenderStats.readbackFallbacks)
        }
    }

    @Test fun realManualAndCacheHaveOnlyOneHeaderAndCloseThroughThePaper() {
        PaperRenderStats.reset(); PullOutSources.latest = null
        var page by mutableIntStateOf(0)
        compose.setContent { MyApplicationTheme { DialogLiquidGlass {
            Column {
                GlassCard(Modifier.fillMaxWidth().clickableWithFeedback { page = 1 }) { Text("手册入口") }
                GlassCard(Modifier.fillMaxWidth().clickableWithFeedback { page = 2 }) { Text("缓存入口") }
            }
            if (page == 1) LibraryHelpBottomSheet({ page = 0 }, {})
            if (page == 2) CacheManagementScreen { page = 0 }
        } } }
        compose.onNodeWithText("手册入口").performTouchInput { click(center) }
        compose.waitUntil(30_000) { PullOutSources.latest?.hidden == true && PaperRenderStats.gpuFrames > 0 }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("关闭帮助").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("关闭面板").assertCountEquals(0)
        shot("manual-one-header")
        val step = with(compose.density) { 3.dp.toPx() }
        val beforeCapture = PaperRenderStats.snapshots
        val beforeHeaderDrag = PaperRenderStats.gpuFrames
        compose.onNodeWithText("书库使用手册").performTouchInput { down(center) }
        compose.onNodeWithText("书库使用手册").performTouchInput { moveBy(Offset(0f, step * 5), 32) }
        compose.waitUntil(30_000) { PaperRenderStats.snapshots > beforeCapture && PaperRenderStats.gpuFrames > beforeHeaderDrag }
        compose.waitForIdle() // Finish the one capture of the current, edited content.
        val framesBeforeDrag = PaperRenderStats.gpuFrames
        compose.mainClock.autoAdvance = false
        repeat(7) {
            compose.onNodeWithText("书库使用手册").performTouchInput { moveBy(Offset(0f, step), 16) }
            compose.mainClock.advanceTimeByFrame()
        }
        assertTrue("The spring must advance while MOVE events are still arriving", PaperRenderStats.gpuFrames > framesBeforeDrag + 1)
        shot("manual-drag-in-progress")
        compose.onNodeWithText("书库使用手册").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1600); compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("A short header drag springs back without dismissing", 1, page)
        compose.onNodeWithContentDescription("关闭帮助").performClick()
        compose.waitUntil(30_000) { page == 0 }
        compose.onNodeWithText("缓存入口").performTouchInput { click(center) }
        compose.waitUntil(30_000) { PullOutSources.latest?.hidden == true }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("关闭").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("关闭面板").assertCountEquals(0)
        shot("cache-one-header")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.waitUntil(30_000) { page == 0 }
        stats("manual-and-cache")
        assertEquals(0, PaperRenderStats.captureFailures)
        assertEquals(0, PaperRenderStats.fallbackFrames)
        assertEquals(5, PaperRenderStats.snapshots)
    }

    @Test fun discoverSourceSelectorHasARealOriginAndWaitsForItsExit() {
        PaperRenderStats.reset(); PullOutSources.latest = null
        var open by mutableStateOf(false); var selections = 0
        compose.setContent { MyApplicationTheme {
            DiscoverFilters(listOf("漫画" to true), {}, "全部书源", { open = true }, emptySet(), {}, {}, {}, false, {},
                emptyList(), {}, emptyList(), "全部", onStatus = {})
            if (open) DiscoverSourcePicker(emptyList(), emptyMap(), "aggregate", { selections++; open = false }, { open = false }, {})
        } }
        compose.onNodeWithText("全部书源").performTouchInput { click(center) }
        compose.waitUntil(30_000) { PullOutSources.latest?.hidden == true && PaperRenderStats.gpuFrames > 0 }
        compose.waitForIdle()
        assertTrue(PullOutSources.latest?.hidden == true)
        shot("discover-source-paper")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("聚合 · 全部已启用书源").performClick()
        compose.mainClock.advanceTimeBy(144)
        assertEquals(0, selections)
        compose.mainClock.advanceTimeBy(1800); compose.mainClock.autoAdvance = true
        // Window disposal follows the external visibility update. The selected
        // action deliberately runs only after that window has actually retired.
        compose.waitUntil(30_000) { !open && selections == 1 }
        assertEquals(1, selections)
        stats("discover")
        assertTrue(PaperRenderStats.gpuFrames > 0)
        assertEquals(0, PaperRenderStats.fallbackFrames)
    }

    @Test fun verticalScrollAcrossASliderDoesNotSeekButHorizontalDragStillDoes() {
        var value by mutableFloatStateOf(.25f)
        var changes = 0; var scroll: androidx.compose.foundation.ScrollState? = null
        compose.setContent { MyApplicationTheme {
            val state = rememberScrollState(); scroll = state
            Column(Modifier.fillMaxSize().verticalScroll(state).padding(16.dp)) {
                Spacer(Modifier.height(200.dp))
                LiquidSlider(value, { value = it; changes++ }, Modifier.testTag("polish-slider"))
                Spacer(Modifier.height(1200.dp))
            }
        } }
        val slider = compose.onNodeWithTag("polish-slider")
        slider.performTouchInput { swipe(Offset(width * .85f, height * .618f), Offset(width * .85f, -height * .9f), 500) }
        compose.waitForIdle()
        assertEquals(.25f, value, 0f); assertEquals(0, changes)
        assertTrue(scroll!!.value > 0)
        slider.performScrollTo()
        slider.performTouchInput { swipe(Offset(width * .25f, height * .618f), Offset(width * .7f, height * .618f), 500) }
        compose.waitForIdle()
        assertTrue(value > .5f); assertTrue(changes > 0)
        shot("slider-scroll-and-drag")
    }

    @Test fun dismissingDuringOpeningRestoresItsSourceAndTheNextOpeningStillWorks() {
        PullOutSources.latest = null
        var open by mutableStateOf(false); var second by mutableStateOf(false); var dismissals = 0
        compose.setContent { MyApplicationTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PullOutButton({ open = true }) { Text("快速打开") }
                if (open) AcrylicDialog({ open = false; dismissals++ }, title = { Text(if (second) "第二窗口" else "可打断纸面") },
                    confirmButton = { PullOutTextButton({ second = true; open = true }) { Text("下一窗口") } })
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("快速打开").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNode(isRoot() and hasAnyDescendant(hasText("可打断纸面"))).performTouchInput { click(Offset(8f, 8f)) }
        compose.mainClock.advanceTimeBy(2200); compose.mainClock.autoAdvance = true
        compose.waitUntil(30_000) { !open }
        assertEquals(1, dismissals)
        assertFalse(PullOutSources.latest?.hidden == true)
        compose.onNodeWithText("快速打开").performTouchInput { click(center) }
        compose.waitUntil(30_000) { PullOutSources.latest?.hidden == true }
        compose.waitForIdle()
        compose.onNodeWithText("可打断纸面").assertExists()
        assertTrue(PullOutSources.latest?.hidden == true)
        val originalSource = PullOutSources.latest
        val captures = PaperRenderStats.snapshots
        compose.onNodeWithText("下一窗口").performClick()
        compose.waitUntil(30_000) { second && PaperRenderStats.snapshots >= captures + 2 &&
            PullOutSources.latest === originalSource && originalSource?.hidden == true }
        compose.waitForIdle()
        compose.onNodeWithText("第二窗口").assertExists()
        assertEquals(2, dismissals)
    }
}
