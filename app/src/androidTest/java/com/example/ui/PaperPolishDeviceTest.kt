package com.example.ui

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
    private val dir get() = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "paper-1.4.14").apply { mkdirs() }
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
}
