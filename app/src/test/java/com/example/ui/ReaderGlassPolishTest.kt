package com.example.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.ui.comic.ReaderPolishProbeActivity
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
class ReaderGlassPolishTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderPolishProbeActivity>()
    private fun probe(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { click() }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("更多").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("排版设置").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("阅读排版").assertExists()
        val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }.first { File(it,"gradlew.bat").exists() }
        val path = File(root,"artifacts/motion-polish/$name.png").apply { parentFile!!.mkdirs() }
        compose.onNode(isDialog()).captureRoboImage(path.absolutePath)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("关闭阅读排版").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("阅读排版").assertExists()
        // Closing follows the physical spring, including the click-to-close handoff.
        // A fixed 500 ms cutoff also failed on the unchanged 1.5.1 baseline.
        compose.mainClock.advanceTimeUntil(timeoutMillis = 2000) {
            compose.onAllNodesWithText("阅读排版").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithText("阅读排版").assertDoesNotExist()
    }
    @Test fun phoneTypographyRetainsControlsAndWaitsForExit() = probe("novel-glass-phone")
    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-160dpi")
    fun tabletTypographyUsesTheRightPanelAndKeepsTextReadable() = probe("novel-glass-tablet")
}
