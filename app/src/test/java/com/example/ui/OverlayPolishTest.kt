package com.example.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.components.AcrylicBottomOverlay
import com.example.ui.components.AcrylicDialog
import com.example.ui.components.LocalOverlayClose
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
@Config(sdk = [34], qualifiers = "w360dp-h780dp-420dpi")
class OverlayPolishTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test fun confirmationWaitsForExitAndRepeatedTapsCannotRepeatTheAction() {
        var open by mutableStateOf(true)
        var actions = 0
        var dismissals = 0
        compose.setContent { MyApplicationTheme {
            if (open) AcrylicDialog(onDismissRequest = { dismissals++; open = false },
                title = { Text("确认操作") }, confirmButton = {
                    val close = LocalOverlayClose.current
                    TextButton(onClick = { close { actions++ } }) { Text("执行") }
                })
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("执行").performClick().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("确认操作").assertExists()
        assertEquals(0, actions)
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("确认操作").assertDoesNotExist()
        assertEquals(1, actions)
        assertEquals(1, dismissals)
    }

    @Test fun tappingOutsideBottomOverlayFinishesItsExitBeforeRemoval() {
        var open by mutableStateOf(true)
        var dismissals = 0
        compose.setContent { MyApplicationTheme {
            if (open) AcrylicBottomOverlay(onDismissRequest = { dismissals++; open = false }) { Text("下载面板") }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNode(isRoot() and hasAnyDescendant(hasText("下载面板"))).performTouchInput { click(Offset(10f, 10f)) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("下载面板").assertExists()
        assertEquals(0, dismissals)
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("下载面板").assertDoesNotExist()
        assertEquals(1, dismissals)
    }
}
