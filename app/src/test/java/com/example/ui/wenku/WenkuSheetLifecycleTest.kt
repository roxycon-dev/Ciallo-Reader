package com.example.ui.wenku

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import com.example.data.ReadingRecord
import com.example.ui.components.LocalOverlayClose
import com.example.ui.components.WeeklyReadingChart
import com.example.ui.components.paperOpen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h780dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WenkuSheetLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readingHistoryMenuOpensDetailsAndKeepsDeletionExplicit() {
        val record = ReadingRecord(1, null, "阅读菜单夹具", "2026-11-09", 720)
        val deleted = mutableListOf<ReadingRecord>()
        compose.setContent {
            MyApplicationTheme {
                WeeklyReadingChart(
                    minutesPerDay = listOf(12, 0, 0, 0, 0, 0, 0),
                    dayRecords = listOf(listOf(record)),
                    todayIndex = 0,
                    onDeleteRecord = { deleted += it }
                )
            }
        }
        compose.onNodeWithText(record.bookTitle).performTouchInput { longClick() }
        compose.onNodeWithText("阅读详情").performClick()
        compose.onNodeWithText("阅读日期：2026-11-09").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<ReadingRecord>(), deleted) }
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText(record.bookTitle).performTouchInput { longClick() }
        compose.onNodeWithText("删除阅读历史").performClick()
        compose.runOnIdle { assertEquals(listOf(record), deleted) }
    }

    @Test fun menuKeepsItsAnchorContentWhileClosingBeforeOpeningDestination() {
        val visible = mutableStateOf(true)
        val events = mutableListOf<String>()
        compose.setContent {
            MyApplicationTheme {
                WenkuMenuHost(
                    visible = visible.value,
                    anchorBounds = Rect(80f, 400f, 480f, 560f),
                    onDismiss = { events += "dismiss"; visible.value = false }
                ) {
                    DisposableEffect(Unit) { onDispose { events += "dispose" } }
                    val action = paperOpen { events += "destination" }
                    TextButton(onClick = { action(); action() }) { Text("Open details") }
                }
            }
        }
        compose.onNodeWithText("Open details").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Open details").performClick()
        compose.mainClock.advanceTimeBy(128)
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
        compose.mainClock.advanceTimeBy(360)
        compose.onNodeWithText("Open details").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("dismiss", "destination", "dispose"), events) }
    }

    @Test fun reverseKeepsContentUntilDismissAndRunsFirstActionOnce() {
        val visible = mutableStateOf(true)
        val events = mutableListOf<String>()
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            MyApplicationTheme {
                back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                Box(Modifier.fillMaxSize()) {
                    WenkuSheetHost(visible.value, anchorBounds = null, onDismiss = {
                        events += "dismiss"
                        visible.value = false
                    }) {
                        DisposableEffect(Unit) {
                            onDispose { events += "dispose" }
                        }
                        val close = LocalOverlayClose.current
                        TextButton(
                            modifier = Modifier.height(64.dp),
                            onClick = {
                                close { events += "follow-up" }
                                close { events += "duplicate" }
                            }
                        ) { Text("Open destination") }
                    }
                }
            }
        }
        compose.onNodeWithText("Open destination").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Open destination").performClick()
        compose.mainClock.advanceTimeBy(112)
        compose.runOnIdle {
            assertEquals(emptyList<String>(), events)
            back.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(360)
        compose.onNodeWithText("Open destination").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("dismiss", "follow-up", "dispose"), events) }
    }

    @Test fun busySheetConsumesBackAndStillAllowsExplicitSuccessToClose() {
        val visible = mutableStateOf(true)
        val events = mutableListOf<String>()
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            MyApplicationTheme {
                back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                WenkuSheetHost(
                    visible = visible.value,
                    anchorBounds = null,
                    dismissGesturesEnabled = false,
                    onDismiss = { events += "closed"; visible.value = false }
                ) {
                    val close = LocalOverlayClose.current
                    TextButton(onClick = { close { events += "success" } }) { Text("Verified") }
                }
            }
        }
        compose.onNodeWithText("Verified").assertIsDisplayed()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("Verified").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
        compose.onNodeWithText("Verified").performClick()
        compose.onNodeWithText("Verified").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("closed", "success"), events) }
    }
}
