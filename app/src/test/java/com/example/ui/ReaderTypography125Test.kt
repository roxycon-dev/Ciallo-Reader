package com.example.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.example.ui.comic.ReaderPolishProbeActivity
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
class ReaderTypography125Test {
    @get:Rule val compose = createAndroidComposeRule<ReaderPolishProbeActivity>()
    private fun openAndCheck(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage("${name}_before.png")
        compose.onRoot().performTouchInput { click() }
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("更多").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("排版设置").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("阅读排版").assertExists()
        compose.onNode(isDialog()).captureRoboImage("${name}.png")
        compose.onNodeWithContentDescription("关闭阅读排版").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("阅读排版").assertDoesNotExist()
    }
    @Test fun parchmentPhoneRetainsTypographyControls() = openAndCheck("reader125_novel_phone")
    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-160dpi")
    fun parchmentTabletUsesBoundedSidePanel() = openAndCheck("reader125_novel_tablet")
}
