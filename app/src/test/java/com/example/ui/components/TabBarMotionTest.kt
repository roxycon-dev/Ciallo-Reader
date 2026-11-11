package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.wenku.WenkuPlaneTabSwitcher
import com.example.ui.wenku.wenkuPlaneLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
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
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
class TabBarMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aTransparentIncomingPaneRevealsWallpaperInsteadOfTheOldPage() {
        compose.setContent {
            MyApplicationTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Box(Modifier.fillMaxSize()
                        .wenkuPlaneLayer({ .27f }, incoming = false, occludeOutgoing = true)
                        .background(Color.Red))
                    Box(Modifier.fillMaxSize()
                        .wenkuPlaneLayer({ .27f }, incoming = true)
                        .background(Color.Blue.copy(alpha = .5f)))
                }
            }
        }
        val capture = File.createTempFile("wenku-tab-plane", ".png")
        val pixels = try {
            compose.onRoot().captureRoboImage(capture.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            requireNotNull(android.graphics.BitmapFactory.decodeFile(capture.absolutePath))
        } finally {
            capture.delete()
        }
        val revealed = Color(pixels.getPixel(pixels.width / 8, pixels.height * 2 / 3))
        val leaving = Color(pixels.getPixel(pixels.width * 9 / 10, pixels.height * 2 / 3))
        assertTrue("The incoming plane should blend with white wallpaper, found $revealed", revealed.green > .35f && revealed.blue > .9f)
        assertTrue("The outgoing plane remains on the other side of the cut, found $leaving", leaving.red > .9f && leaving.blue < .15f)
    }

    @Test fun visitedPaneKeepsItsCompositionAndPausesWhileOffstage() {
        val selected = mutableIntStateOf(0)
        val mounts = mutableListOf<Int>()
        val paneLifecycles = mutableMapOf<Int, Lifecycle>()
        compose.setContent {
            MyApplicationTheme {
                WenkuPlaneTabSwitcher(selected.intValue, Modifier.fillMaxSize(), { from, to -> to - from }) { tab ->
                    val lifecycle = LocalLifecycleOwner.current.lifecycle
                    DisposableEffect(tab, lifecycle) {
                        mounts += tab
                        paneLifecycles[tab] = lifecycle
                        onDispose { }
                    }
                    Text("Pane " + tab)
                }
            }
        }
        compose.onNodeWithText("Pane 0").assertIsDisplayed()
        compose.runOnIdle { selected.intValue = 1 }
        compose.onNodeWithText("Pane 1").assertIsDisplayed()
        compose.onNodeWithText("Pane 0").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(Lifecycle.State.CREATED, paneLifecycles.getValue(0).currentState)
            selected.intValue = 0
        }
        compose.onNodeWithText("Pane 0").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(0, 1), mounts)
            assertTrue(paneLifecycles.getValue(0).currentState.isAtLeast(Lifecycle.State.STARTED))
        }
    }

    private fun lineCenter(): Float {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "gradlew.bat").exists() }
        val file = File(root, "artifacts/multilingual-search-v5/tab-probe.png")
        file.parentFile.mkdirs()
        compose.onNodeWithTag("app_tab_bar_surface").captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
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
    @Test fun allFiveIconsMoveContinuouslyUntilLabelSpaceReachesZero() {
        val collapse = TabBarCollapseState()
        val labels = listOf("书库", "发现", "书架", "统计", "设置")
        compose.setContent { MyApplicationTheme {
            CompositionLocalProvider(LocalRenderQuality provides RenderQuality.MID) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                    AppBottomTabBar(labels.map { AppTabItem(it, Icons.Default.Home) }, 4, {}, collapseState = collapse)
                }
            }
        } }
        compose.waitForIdle(); compose.mainClock.autoAdvance = false
        fun positions() = labels.map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot.center }
        var previous = positions(); val original = previous
        compose.runOnIdle { collapse.connection().onPreScroll(Offset(0f, -12f), NestedScrollSource.UserInput) }
        val limit = with(compose.density) { 3.dp.toPx() }
        repeat(100) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            val now = positions()
            now.indices.forEach { index ->
                assertEquals(original[index].x, now[index].x, .5f)
                assertTrue("Collapse frame $it, icon $index jumped: ${now[index].y - previous[index].y}",
                    kotlin.math.abs(now[index].y - previous[index].y) < limit)
            }
            previous = now
        }
        compose.runOnIdle { collapse.connection().onPreScroll(Offset(0f, 12f), NestedScrollSource.UserInput) }
        repeat(100) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            val now = positions()
            now.indices.forEach { index -> assertTrue(kotlin.math.abs(now[index].y - previous[index].y) < limit) }
            previous = now
        }
        compose.mainClock.autoAdvance = true
    }

}
