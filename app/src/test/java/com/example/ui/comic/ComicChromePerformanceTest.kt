package com.example.ui.comic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.example.ui.components.LocalRenderQuality
import com.example.ui.components.RenderQuality
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.wenku.WenkuReaderChromeLayer
import com.example.ui.wenku.WenkuReaderChromePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w1280dp-h800dp-160dpi")
class ComicChromePerformanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    private fun checkProgress(quality: RenderQuality) {
        val fraction = mutableFloatStateOf(0f)
        var compositions = 0
        val loader = ComicPageLoader(compose.activity, minimal = true)
        val store = ComicSettingsStore(compose.activity)
        val config = ComicReaderConfig(mode = ComicMode.WEBTOON, direction = ComicDirection.TTB)
        try {
            compose.setContent {
                SideEffect { compositions++ }
                CompositionLocalProvider(LocalRenderQuality provides quality, LocalReduceMotion provides true) {
                    MyApplicationTheme {
                        ComicReaderChrome(
                            visible = true, panel = ComicPanel.NONE,
                            onPanelChange = {}, title = "性能回归漫画", chapterTitle = "第一话", currentPage = 1,
                            totalPages = 5000, spreadCount = 5000, currentSpread = 0, config = config, store = store,
                            perBookConfig = false, onPerBookConfigChange = {}, onConfigChange = {},
                            onJumpToSpread = {}, spreadFirstRaw = { it }, onJumpToRawPage = {},
                            onGoPrev = {}, onGoNext = {}, autoRead = false, onAutoReadToggle = {},
                            continuousFractionSource = { fraction.floatValue }, onJumpToScrollFraction = {},
                            toc = emptyList(), currentChapterIndex = 0, onJumpToChapter = null,
                            onPrevChapter = null, onNextChapter = null, chapterNavLabel = "目录",
                            pages = emptyList(), loader = loader, bookState = ComicBookState(), onBookStateChange = {},
                            onToggleControls = {}, onExit = {},
                        )
                    }
                }
            }
            compose.waitForIdle()
            val before = compositions
            repeat(60) { index ->
                compose.runOnIdle { fraction.floatValue = (index + 1) / 100f }
                compose.waitForIdle()
                compose.mainClock.advanceTimeByFrame()
            }
            compose.waitForIdle()
            assertEquals("Continuous progress must not recompose its parent", before, compositions)
            compose.onNodeWithText("· 60%").assertExists()
        } finally { loader.shutdown(); loader.clearProcessedCache() }
    }

    @Test fun minimalTabletKeepsContinuousProgressInsideChrome() = checkProgress(RenderQuality.MINIMAL)
    @Test fun highQualityTabletKeepsContinuousProgressInsideChrome() = checkProgress(RenderQuality.HIGH)

    @Test fun staggeredCapsulesPlaceInOrderWithoutMeasuringAgain() {
        val phase = mutableFloatStateOf(0f)
        var measurements = 0
        compose.setContent {
            Column {
                WenkuReaderChromeLayer(phase, visible = true, part = WenkuReaderChromePart.Progress) {
                    Box(Modifier.size(120.dp, 44.dp).layout { measurable, constraints ->
                        measurements++
                        val child = measurable.measure(constraints)
                        layout(child.width, child.height) { child.place(0, 0) }
                    }) { Text("Progress") }
                }
                WenkuReaderChromeLayer(phase, visible = true, part = WenkuReaderChromePart.Dock) {
                    Box(Modifier.size(120.dp, 44.dp)) { Text("Dock") }
                }
            }
        }
        compose.onNodeWithText("Progress").assertDoesNotExist()
        compose.onNodeWithText("Dock").assertDoesNotExist()
        val hiddenMeasurements = compose.runOnIdle { measurements }
        assertTrue(hiddenMeasurements > 0)
        compose.runOnIdle { phase.floatValue = .05f }
        compose.onNodeWithText("Dock").assertExists()
        compose.onNodeWithText("Progress").assertDoesNotExist()
        compose.runOnIdle { phase.floatValue = .55f }
        compose.onNodeWithText("Progress").assertExists()
        compose.runOnIdle { phase.floatValue = .05f }
        compose.onNodeWithText("Dock").assertExists()
        compose.onNodeWithText("Progress").assertDoesNotExist()
        compose.runOnIdle { phase.floatValue = 0f }
        compose.onNodeWithText("Dock").assertDoesNotExist()
        assertEquals(hiddenMeasurements, compose.runOnIdle { measurements })
    }
}
