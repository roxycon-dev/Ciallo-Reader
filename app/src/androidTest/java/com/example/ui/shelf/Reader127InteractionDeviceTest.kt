package com.example.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.example.ui.comic.*
import net.engawapg.lib.zoomable.ZoomState
import net.engawapg.lib.zoomable.zoomable
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real pointer dispatch through production modifiers, including their parent/child arbitration. */
class Reader127InteractionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test fun androidResolvesAppDetailLinksToTheReaderActivity() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val link = com.example.library.SharedWorkLink.create("js_copy_manga", "baihetianxin", "百合甜心")
        assertEquals(com.example.library.SharedWorkLink.Target("js_copy_manga", "baihetianxin", "百合甜心"),
            com.example.library.SharedWorkLink.parse(android.net.Uri.parse(link)))
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
        val resolved = context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals("com.example.MainActivity", resolved?.activityInfo?.name)
    }

    private fun pinch(firstLift: Int, library: Boolean = false) {
        val custom = ComicZoomState().apply {
            containerSize = Size(360f, 480f); contentSize = containerSize
        }
        val vertical = ZoomState(contentSize = Size(360f, 480f))
        compose.setContent {
            val gestures = if (library) Modifier.zoomable(vertical) else Modifier.comicZoomable(
                custom, ComicReaderConfig(longPressZoom = false), ComicGestureCallbacks({ _, _ -> }, {}, {}, {}),
            )
            Box(Modifier.fillMaxSize().testTag("page")
                .onSizeChanged { custom.contentSize = Size(it.width.toFloat(), it.height.toFloat()) }.then(gestures))
        }
        compose.onNodeWithTag("page").performTouchInput {
            down(0, Offset(width * .35f, height * .35f)); down(1, Offset(width * .65f, height * .65f))
            for (i in 1..8) {
                updatePointerTo(0, Offset(width * (.35f - i * .018f), height * (.35f - i * .018f)))
                updatePointerTo(1, Offset(width * (.65f + i * .018f), height * (.65f + i * .018f)))
                move(16)
            }
        }
        var held = Offset.Zero
        compose.runOnIdle { held = if (library) Offset(vertical.offsetX, vertical.offsetY) else Offset(custom.offsetX, custom.offsetY) }
        compose.onNodeWithTag("page").performTouchInput {
            up(firstLift); advanceEventTime(16); up(1 - firstLift)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val final = if (library) Offset(vertical.offsetX, vertical.offsetY) else Offset(custom.offsetX, custom.offsetY)
            assertTrue("Pinch must not fling after lift: held=$held final=$final", (final - held).getDistance() < 2f)
            assertTrue(if (library) vertical.scale > 1.3f else custom.scale > 1.3f)
        }
    }

    @Test fun pinchDoesNotFlingWhenOriginalFingerLiftsFirst() = pinch(0)
    @Test fun pinchDoesNotFlingWhenOriginalFingerLiftsLast() = pinch(1)
    @Test fun continuousReaderPinchDoesNotFlingWhenCentroidChanges() = pinch(0, library = true)

    @Test fun intentionalSingleFingerPanStillHasInertia() {
        val state = ComicZoomState().apply { scale = 2.5f }
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("page")
                .onSizeChanged { state.contentSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .comicZoomable(state, ComicReaderConfig(longPressZoom = false), ComicGestureCallbacks({ _, _ -> }, {}, {}, {})))
        }
        compose.onNodeWithTag("page").performTouchInput {
            down(center)
            for (i in 1..8) moveTo(center + Offset(i * 10f, 0f), 16)
        }
        var held = 0f
        compose.runOnIdle { held = state.offsetX }
        compose.onNodeWithTag("page").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("An intentional flick should still coast", state.offsetX > held + 5f) }
    }

    private fun bar(key: String, enabled: Boolean = true, padding: Boolean = false) {
        val state = ShelfSelectionState().apply {
            enterSelection(key); aliveKeys = setOf(key); fallbackHit = { key }
        }
        var behindTaps = 0
        var actionTaps = 0
        val sink = ShelfGestureSink().apply { onTap = { behindTaps++; state.toggle(it) } }
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize().testTag("host").shelfGestures(state, 12f, sink, remember { DropSink() })) {
                MultiSelectActionBar(listOf(ShelfAction("分享", Icons.Filled.Share, { actionTaps++ }, enabled)),
                    modifier = Modifier.align(Alignment.BottomCenter).testTag("bar"))
            }
        } }
        compose.onNodeWithTag("bar").performTouchInput {
            click(if (padding) Offset(1f, height * .5f) else center)
        }
        compose.runOnIdle {
            assertEquals("The host must not also toggle the covered book", 0, behindTaps)
            assertEquals(setOf(key), state.selected.toSet())
            assertEquals(if (enabled && !padding) 1 else 0, actionTaps)
        }
    }

    @Test fun bookshelfBarActionDoesNotToggleTheBookBehindIt() = bar("1")
    @Test fun favoritesBarActionDoesNotToggleTheFavoriteBehindIt() = bar("fav::source::42")
    @Test fun disabledBarActionDoesNotLeakThrough() = bar("1", enabled = false)
    @Test fun barOuterPaddingDoesNotLeakThrough() = bar("1", padding = true)

    @Test fun uncoveredBooksStillRespondToLongPressAndSelectionTap() {
        val state = ShelfSelectionState().apply { aliveKeys = setOf("1"); fallbackHit = { "1" } }
        val sink = ShelfGestureSink().apply { onTap = { state.toggle(it) } }
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("host").shelfGestures(state, 12f, sink, remember { DropSink() }))
        }
        compose.onNodeWithTag("host").performTouchInput { down(center); advanceEventTime(500); up() }
        compose.runOnIdle { assertEquals(ShelfPhase.SELECTING, state.phase); assertTrue("1" in state.selected) }
        compose.onNodeWithTag("host").performTouchInput { click(center) }
        compose.runOnIdle { assertFalse("1" in state.selected) }
    }
}
