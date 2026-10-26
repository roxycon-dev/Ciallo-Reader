package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.components.AppSwitch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Window and density coverage on Android; does not claim Samsung hinge hardware. */
@RunWith(AndroidJUnit4::class)
class WindowResizeStabilityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { parcel ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel).use { it.readBytes() }
        }
    }

    @Test fun deepReadingAnchorSurvivesRepeatedWindowAndDensityChanges() {
        val text = (1..6500).joinToString("\n") { "第${it}段，正文不能在改变窗口时丢失。Latin text gypq 012345。" }
        val offset = text.length * 4 / 5
        var checked by mutableStateOf(true)
        var latest: List<String> = emptyList()
        var width = 0
        var densitySeen = 0f
        var everComplete = false
        var partialAfterComplete = 0
        var anchorCovered = false
        compose.setContent { MaterialTheme {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val contentWidth = with(density) { (maxWidth - 32.dp).roundToPx() }.coerceAtLeast(60)
                val contentHeight = with(density) { (maxHeight - 120.dp).roundToPx() }.coerceAtLeast(120)
                val pages = rememberChapterPages(text, contentWidth, contentHeight,
                    TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontFamily = FontFamily.SansSerif), 0, false)
                val anchors = remember(pages) { ReaderPageAnchors(pages) }
                val page = anchors.pageAt(offset)
                SideEffect {
                    width = contentWidth
                    densitySeen = density.density
                    latest = pages
                    val complete = pages.sumOf { it.length } == text.length
                    if (everComplete && !complete) partialAfterComplete++
                    if (complete) {
                        everComplete = true
                        val start = anchors.startOf(page)
                        anchorCovered = offset >= start && offset < start + pages[page].length
                    }
                }
                Column(Modifier.padding(16.dp)) {
                    AppSwitch(checked, { checked = it })
                    Text(pages.getOrNull(page) ?: "准备正文")
                }
            }
        } }
        compose.waitUntil(30000) { everComplete }
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        assertFalse(checked)
        val windows = listOf("884x2208", "1768x2208", "1080x1600", "1600x1080", "884x2208", "1768x2208")
        try {
            for (size in windows) {
                val before = width
                val oldPages = latest
                shell("wm size $size")
                shell("input keyevent KEYCODE_WAKEUP")
                shell("wm dismiss-keyguard")
                compose.waitUntil(30000) { width != before }
                compose.waitUntil(30000) { latest !== oldPages && latest.sumOf { it.length } == text.length }
                compose.waitForIdle()
                assertFalse(checked)
                assertEquals(text.length, latest.sumOf { it.length })
                assertTrue(anchorCovered)
            }
            for (dpi in listOf(320, 480, 420)) {
                val before = densitySeen
                val oldPages = latest
                shell("wm density $dpi")
                compose.waitUntil(30000) { densitySeen != before }
                compose.waitUntil(30000) { latest !== oldPages && latest.sumOf { it.length } == text.length }
                compose.waitForIdle()
                assertFalse(checked)
                assertEquals(text.length, latest.sumOf { it.length })
                assertTrue(anchorCovered)
            }
            assertEquals("A deep anchor must never fall back to a partial new layout", 0, partialAfterComplete)
            val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "motion-round2").apply { mkdirs() }
            File(folder, "window-density.json").writeText(JSONObject().put("windowChanges", windows.size)
                .put("densityChanges", 3).put("contentLength", text.length).put("anchorOffset", offset)
                .put("partialAfterComplete", partialAfterComplete).put("inputRetained", !checked).toString(2))
        } finally {
            shell("wm size reset")
            shell("wm density reset")
        }
    }
}
