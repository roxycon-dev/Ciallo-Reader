package com.example.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.download.DownloadState
import com.example.ui.feedback.LocalReduceMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadCenterButtonTest {
    @get:Rule val compose = createComposeRule()
    private fun state(value: DownloadState) = downloadIndicatorState(mapOf("book" to value), emptyMap())
    private fun description(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Test fun existingCompletedHistoryNeverShowsSuccessAndStillOpensTheQueue() {
        var clicks = 0
        compose.setContent { MaterialTheme { DownloadCenterButton(state(DownloadState.Success("/book"))) { clicks++ } } }
        compose.onNodeWithTag("download_tasks_entry").assert(description("无进行中的下载")).performClick()
        assertEquals(1, clicks)
        compose.mainClock.advanceTimeBy(2000)
        compose.onNodeWithTag("download_tasks_entry").assert(description("无进行中的下载"))
    }
    @Test fun genuineCompletionIsBriefAndCannotReplayAfterCancellation() {
        var queue by mutableStateOf(state(DownloadState.Downloading(70, 100, .7f)))
        compose.setContent { MaterialTheme { DownloadCenterButton(queue) {} } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { queue = state(DownloadState.Success("/book")) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithTag("download_tasks_entry").assert(description("下载完成"))
        compose.mainClock.advanceTimeBy(1400)
        compose.onNodeWithTag("download_tasks_entry").assert(description("无进行中的下载"))
        compose.runOnIdle { queue = state(DownloadState.Pending) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { queue = DownloadIndicatorState() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithTag("download_tasks_entry").assert(description("无进行中的下载"))
        compose.mainClock.autoAdvance = true
    }
    @Test fun reducedMotionKeepsAccurateStatusAndTheSameNavigationAction() {
        var clicks = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                DownloadCenterButton(state(DownloadState.Paused(20, 100))) { clicks++ }
            }
        } }
        compose.onNodeWithTag("download_tasks_entry").assert(description("1 项暂停")).performClick()
        assertEquals(1, clicks)
    }
}
