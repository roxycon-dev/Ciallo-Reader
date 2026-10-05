package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h780dp-420dpi")
class TabBarMotionTest {
    @get:Rule val compose = createComposeRule()
    private fun lineCenter(): Float {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "gradlew.bat").exists() }
        val file = File(root, "artifacts/multilingual-search-v5/tab-probe.png")
        file.parentFile.mkdirs()
        compose.onNodeWithTag("app_tab_bar_surface").captureRoboImage(file.absolutePath)
        val pixels = requireNotNull(android.graphics.BitmapFactory.decodeFile(file.absolutePath))
        val y = 9.coerceAtMost(pixels.height - 1)
        val xs = (0 until pixels.width).filter { x -> Color(pixels.getPixel(x,y)).luminance() < 0.12f }
        assertTrue("The indicator must be visible", xs.isNotEmpty())
        return xs.average().toFloat()
    }
    private fun screenshot(name: String) {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "gradlew.bat").exists() }
        val folder = File(root, "artifacts/multilingual-search-v5").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(folder,name).absolutePath)
    }

    @Test fun indicatorSpringsBetweenEqualSlotsAndStaysAlignedWhenCollapsed() {
        val selected = mutableStateOf(0)
        val collapse = TabBarCollapseState()
        compose.setContent { MyApplicationTheme(darkTheme = false) {
            CompositionLocalProvider(LocalRenderQuality provides RenderQuality.MID) {
                Box(Modifier.fillMaxWidth().background(Color(0xFFEFB8A5))) {
                    AppBottomTabBar(listOf("书库", "书架", "统计", "设置").map { AppTabItem(it, Icons.Default.Home) },
                        selected.value, { selected.value = it }, collapseState = collapse)
                }
            }
        } }
        val before = lineCenter()
        val width = compose.onNodeWithTag("app_tab_bar_surface").fetchSemanticsNode().boundsInRoot.width
        screenshot("tab-before.png")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected.value = 3 }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val moving = lineCenter()
        assertTrue("The line must travel rather than jump: before=$before, moving=$moving, width=$width", moving > before + 1 && moving < width * 0.875f - 1)
        screenshot("tab-moving.png")
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        val settled = lineCenter()
        assertEquals(width * 0.875f, settled, 2f)
        compose.runOnIdle { collapse.connection().onPreScroll(Offset(0f,-12f), NestedScrollSource.UserInput) }
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        val collapsedWidth = compose.onNodeWithTag("app_tab_bar_surface").fetchSemanticsNode().boundsInRoot.width
        assertEquals(width, collapsedWidth, 0.5f)
        assertEquals(settled, lineCenter(), 2f)
        screenshot("tab-collapsed.png")
        compose.mainClock.autoAdvance = true
    }
}
