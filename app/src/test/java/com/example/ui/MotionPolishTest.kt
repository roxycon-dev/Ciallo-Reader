package com.example.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.ui.components.HeaderCollapseGate
import com.example.ui.feedback.AppMotion
import com.example.ui.shelf.*
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h780dp-420dpi")
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
class MotionPolishTest {
    @get:Rule val compose = createComposeRule()
    private fun shot(name: String) {
        val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }.first { File(it, "gradlew.bat").exists() }
        val path = File(root, "artifacts/motion-polish/$name.png").apply { parentFile!!.mkdirs() }
        compose.onRoot().captureRoboImage(path.absolutePath)
    }

    @Test fun fracturePiecesCoverTheOriginalWithoutLostPixelsAndStayInsideTheCover() {
        val shards = shelfShards(120, 180, 7)
        val area = shards.sumOf { s ->
            val (a,b,c) = s.points
            abs((b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)).toDouble()/2
        }
        assertEquals(120.0 * 180, area, .05)
        assertTrue(shards.all { it.points.all { p -> p.x in 0f..120f && p.y in 0f..180f } })
        assertTrue(shards.any { it.spin < 0 } && shards.any { it.spin > 0 })
    }

    @Test fun collapseThresholdDoesNotFlapAndReturnsAtTheTop() {
        val gate = HeaderCollapseGate(32f, 8f)
        assertFalse(gate.update(0, 24, false))
        assertTrue(gate.update(0, 33, false))
        repeat(4) { assertTrue(gate.update(0, 20, false)) }
        assertFalse(gate.update(0, 7, false))
        assertTrue(gate.update(0, 0, true))
        assertFalse(gate.update(0, 0, false))
    }

    @Test fun deletionUsesRealCardFragmentsAndSurvivorsTravelBeforeSettling() {
        val books = mutableStateOf(listOf("1", "2", "3", "4", "5", "6"))
        lateinit var state: ShelfMotionState
        lateinit var scope: CoroutineScope
        lateinit var cover: ImageBitmap
        lateinit var grid: LazyGridState
        var calls = 0
        compose.setContent { MyApplicationTheme {
            // Robolectric cannot snapshot nested GPU RenderNodes. Supply actual recorded card pixels;
            // production uses GraphicsLayer.toImageBitmap, while the same fracture renderer runs here.
            state = remember { ShelfMotionState { cover } }; scope = rememberCoroutineScope()
            grid = rememberLazyGridState()
            Box(Modifier.fillMaxSize().background(Color(0xFF10131D))) {
                LazyVerticalGrid(GridCells.Fixed(3), state = grid, contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(books.value, key = { it }) { id ->
                        Box(Modifier.animateItem(placementSpec = AppMotion.reorder(), fadeInSpec = tween(180), fadeOutSpec = tween(150))
                            .shelfCapture(state, id).height(152.dp).fillMaxWidth().testTag("book$id")
                            .background(Brush.linearGradient(listOf(Color(0xFF446A91), Color(0xFFD5BA87))))) {
                            Text("BOOK $id\n星海图书", color = Color.White, modifier = Modifier.padding(10.dp))
                        }
                    }
                }
                ShelfShatterOverlay(state)
            }
        } }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("book2").fetchSemanticsNode().boundsInRoot.left
        shot("shelf-before")
        val coverPath = File.createTempFile("motion-polish-card-", ".png")
        compose.onNodeWithTag("book1").captureRoboImage(coverPath.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        cover = requireNotNull(android.graphics.BitmapFactory.decodeFile(coverPath.absolutePath)).asImageBitmap()
        coverPath.delete()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.remove(listOf("1"), scope, false) { calls++; books.value = books.value.filterNot { it == "1" } } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil(8_000) { calls == 1 }
        compose.mainClock.advanceTimeBy(112)
        compose.waitForIdle()
        assertTrue("The visual must retain the captured cover: ${state.lastCaptureFailure}", state.bursts.isNotEmpty())
        val during = compose.onNodeWithTag("book2").fetchSemanticsNode().boundsInRoot.left
        assertTrue("Survivors must move continuously: $before -> $during", during < before)
        shot("shelf-fracture")
        compose.mainClock.advanceTimeBy(1600)
        compose.waitForIdle()
        val settled = compose.onNodeWithTag("book2").fetchSemanticsNode().boundsInRoot.left
        assertTrue(during > settled)
        assertEquals(1, calls)
        assertTrue(state.bursts.isEmpty())
        assertTrue(state.hidden.isEmpty())
        compose.onNodeWithTag("book1").assertDoesNotExist()
        shot("shelf-after")

        compose.runOnIdle {
            keepShelfViewportForSort(grid)
            books.value = books.value.reversed()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("book6").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, grid.firstVisibleItemIndex) }
        compose.mainClock.advanceTimeBy(112)
        compose.waitForIdle()
        compose.onNodeWithTag("book6").assertIsDisplayed()
        compose.onNodeWithTag("book3").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("6", "5", "4", "3", "2"), grid.layoutInfo.visibleItemsInfo.map { it.key }) }
        val moving = compose.onNodeWithTag("book2").fetchSemanticsNode().boundsInRoot
        assertTrue("Sorting must leave the old position progressively: $moving", moving.top > compose.onNodeWithTag("book4").fetchSemanticsNode().boundsInRoot.top + 4f)
        shot("shelf-reorder")
        compose.mainClock.advanceTimeBy(1600)
        compose.waitForIdle()
        shot("shelf-sort-settled")
        // Interrupt sorting with a filter; animation must continue from its current position.
        compose.runOnIdle {
            keepShelfViewportForSort(grid)
            books.value = books.value.reversed()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(112)
        compose.waitForIdle()
        compose.runOnIdle { books.value = books.value.filter { it == "2" || it == "4" } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1600)
        compose.waitForIdle()
        compose.onNodeWithTag("book6").assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("book2").fetchSemanticsNode().boundsInRoot.top < moving.top)
        assertEquals(1, calls)
        shot("shelf-filtered")
    }

    @Test fun reducedMotionAndOffscreenDeletionStillRunTheBusinessActionOnce() {
        lateinit var state: ShelfMotionState
        lateinit var scope: CoroutineScope
        var calls = 0
        compose.setContent { state = rememberShelfMotionState(); scope = rememberCoroutineScope() }
        compose.runOnIdle {
            state.remove(listOf("offscreen"), scope, true) { calls++ }
            state.remove(listOf("offscreen"), scope, true) { calls++ }
        }
        compose.waitForIdle()
        compose.waitUntil(2_000) { state.hidden.isEmpty() }
        assertEquals(1, calls)
        assertTrue(state.bursts.isEmpty())
        assertTrue(state.hidden.isEmpty())
    }

    @Test fun navigationCancellationCannotLoseAConfirmedDeletion() {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Main.immediate + job)
        val state = ShelfMotionState { awaitCancellation() }
        var calls = 0
        compose.setContent { Box(Modifier.size(120.dp).shelfCapture(state, "1").background(Color.Red)) }
        compose.runOnIdle {
            state.remove(listOf("1"), scope, false) { calls++ }
            assertEquals("The original card stays visible while the snapshot is pending", false, state.hidden["1"])
            assertEquals(0, calls)
            scope.cancel()
        }
        compose.waitForIdle()
        compose.waitUntil(2_000) { state.hidden.isEmpty() }
        assertEquals(1, calls)
        assertTrue(state.bursts.isEmpty())
    }
}
