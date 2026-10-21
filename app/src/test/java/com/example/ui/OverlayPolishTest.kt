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

    @org.junit.Before fun resetSource() { com.example.ui.components.PullOutSources.latest = null }

    @Test fun confirmationWaitsForExitAndRepeatedTapsCannotRepeatTheAction() {
        var open by mutableStateOf(true)
        var actions = 0
        var dismissals = 0
        var paper: com.example.ui.components.PaperLifecycle? = null
        compose.setContent { MyApplicationTheme {
            if (open) AcrylicDialog(onDismissRequest = { dismissals++; open = false },
                title = { paper = com.example.ui.components.LocalPaperOwner.current; Text("确认操作") }, confirmButton = {
                    val close = LocalOverlayClose.current
                    TextButton(onClick = { close { actions++ } }) { Text("执行") }
                })
        } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1800)
        compose.runOnIdle { check(checkNotNull(paper).flat) }
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
    @Test fun dismissalCannotEraseThePanelOpenedByTheSelectedAction() {
        var panel by mutableStateOf("first")
        var actions = 0
        compose.setContent { MyApplicationTheme {
            if (panel.isNotEmpty()) AcrylicDialog(onDismissRequest = { panel = "" },
                title = { Text(if (panel == "first") "旧弹层" else "新弹层") }, confirmButton = {
                    if (panel == "first") {
                        val close = LocalOverlayClose.current
                        TextButton(onClick = { close { actions++; panel = "second" } }) { Text("打开下一层") }
                    }
                })
        } }
        compose.waitForIdle()
        compose.onNodeWithText("打开下一层").performClick()
        compose.mainClock.advanceTimeBy(1800)
        compose.waitForIdle()
        compose.onNodeWithText("旧弹层").assertDoesNotExist()
        compose.onNodeWithText("新弹层").assertExists()
        assertEquals(1, actions)
    }
    @Test fun explicitCloseCannotInvokeTheHostDismissCallbackTwice() {
        var open by mutableStateOf(true); var dismissals = 0
        val dismiss: () -> Unit = { dismissals++; open = false }
        compose.setContent { MyApplicationTheme {
            if (open) AcrylicBottomOverlay(onDismissRequest = dismiss) {
                TextButton(onClick = com.example.ui.components.paperDismiss(dismiss)) { Text("关闭一次") }
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithText("关闭一次").performClick()
        compose.mainClock.advanceTimeBy(1800); compose.waitForIdle()
        assertEquals(1, dismissals)
    }

}
