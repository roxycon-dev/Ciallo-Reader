package com.example.ui.comic

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.ui.components.LocalRenderQuality
import com.example.ui.components.RenderQuality
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
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
                            visible = true, panel = ComicPanel.NONE, panelVisibility = remember { MutableTransitionState(false) },
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
}
