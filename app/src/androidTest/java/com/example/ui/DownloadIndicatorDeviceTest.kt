package com.example.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.download.DownloadState
import com.example.library.*
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class DownloadIndicatorDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private fun state(value: DownloadState) = downloadIndicatorState(mapOf("book" to value), emptyMap())
    private fun expected(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Test fun historyStaysAnArrowAndLiveStatesMorphThenReturnToNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val oldDrawing = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
        var queue by mutableStateOf(state(DownloadState.Success("/old-history")))
        var clicks = 0
        try {
            compose.mainClock.autoAdvance = false
            compose.setContent { MyApplicationTheme(darkTheme = false) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding().padding(20.dp)) {
                    Text("书库", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    DownloadCenterButton(queue) { clicks++ }
                }
            } }
            fun image(name: String) {
                compose.waitForIdle()
                val rendered = compose.onAllNodes(isRoot()).onLast().captureToImage()
                val bounds = compose.onNodeWithTag("download_tasks_entry").fetchSemanticsNode().boundsInRoot
                val bitmap = rendered.asAndroidBitmap()
                val margin = 16
                val left = (bounds.left.roundToInt() - margin).coerceAtLeast(0)
                val top = (bounds.top.roundToInt() - margin).coerceAtLeast(0)
                val right = (bounds.right.roundToInt() + margin).coerceAtMost(bitmap.width)
                val bottom = (bounds.bottom.roundToInt() + margin).coerceAtMost(bitmap.height)
                val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                val folder = File(instrumentation.targetContext.filesDir, "download-indicator").apply { mkdirs() }
                File(folder, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                crop.recycle(); bitmap.recycle()
            }
            fun change(value: DownloadState, milliseconds: Long = 360) {
                compose.runOnIdle { queue = state(value) }
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(milliseconds)
            }
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("download_tasks_entry").assert(expected("无进行中的下载")).performClick()
            assertEquals(1, clicks)
            image("idle-history")
            change(DownloadState.Pending, 120)
            image("starting-morph")
            change(DownloadState.Downloading(25, 100, .25f))
            image("running-25")
            val buttonBounds = compose.onNodeWithTag("download_tasks_entry").fetchSemanticsNode().boundsInRoot
            fun assertBadgeFits() {
                val badge = compose.onNodeWithTag("download_count_badge").fetchSemanticsNode().boundsInRoot
                assertTrue(badge.left >= buttonBounds.left && badge.right <= buttonBounds.right)
                assertTrue(badge.top >= buttonBounds.top && badge.bottom <= buttonBounds.bottom)
            }
            assertBadgeFits()
            change(DownloadState.Downloading(72, 100, .72f))
            image("running-72")
            for (count in listOf(12, 124)) {
                compose.runOnIdle { queue = downloadIndicatorState((0 until count).associate {
                    "task:$it" to DownloadState.Downloading(72, 100, .72f)
                }, emptyMap()) }
                compose.waitForIdle(); compose.mainClock.advanceTimeBy(500)
                assertBadgeFits()
                image("count-$count")
            }
            change(DownloadState.Paused(72, 100))
            image("paused")
            change(DownloadState.Error("fixture"))
            image("failed")
            change(DownloadState.Downloading(95, 100, .95f))
            change(DownloadState.Success("/book"), 320)
            compose.onNodeWithTag("download_tasks_entry").assert(expected("下载完成"))
            image("completed-brief")
            compose.mainClock.advanceTimeBy(1400)
            compose.onNodeWithTag("download_tasks_entry").assert(expected("无进行中的下载"))
            image("idle-after-completion")
            change(DownloadState.Pending)
            compose.runOnIdle { queue = DownloadIndicatorState() }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("download_tasks_entry").assert(expected("无进行中的下载"))
        } finally {
            compose.mainClock.autoAdvance = true
            androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(oldDrawing)
        }
    }
}
