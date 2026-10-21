package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperAndSliderTest {
    private fun slider(a: Float = .25f, b: Float? = null, gap: Float = 0f, steps: Int = 0): LiquidSliderView {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return LiquidSliderView(context).apply {
            configure(0f..1f, steps, true, false, gap, 0xff417c67.toInt(), false, false, false)
            layout(0, 0, 411, 110); setValues(a, b)
        }
    }
    private fun LiquidSliderView.event(action: Int, x: Float, y: Float = 68f, time: Long = 100L) {
        val e = MotionEvent.obtain(100L, time, action, x, y, 0)
        onTouchEvent(e); e.recycle()
    }
    @Test fun verticalScrollingDoesNotChangeTheValue() {
        val view = slider(); var changes = 0
        view.change = { _, _ -> changes++ }
        view.event(MotionEvent.ACTION_DOWN, 100f)
        view.event(MotionEvent.ACTION_MOVE, 103f, 130f, 200L)
        view.event(MotionEvent.ACTION_CANCEL, 103f, 130f, 220L)
        assertEquals(0, changes)
    }
    @Test fun nearbyThumbPressRetainsRelativeOffset() {
        val view = slider(); var result = .25f
        view.change = { a, _ -> result = a }
        val x = 26f + (411 - 52) * .25f
        view.event(MotionEvent.ACTION_DOWN, x + 15)
        view.event(MotionEvent.ACTION_UP, x + 15, time = 120L)
        assertEquals(.25f, result, .0001f)
    }
    @Test fun emptyTrackTapSelectsTheRequestedPosition() {
        val view = slider(); var result = 0f
        view.change = { a, _ -> result = a }
        view.event(MotionEvent.ACTION_DOWN, 350f); view.event(MotionEvent.ACTION_UP, 350f, time = 150L)
        assertEquals((350f - 26f) / 359f, result, .001f)
    }
    @Test fun trackDownDoesNotMoveTheLensBeforeScrollIntentIsKnown() {
        val view = slider()
        fun image() = Bitmap.createBitmap(411, 110, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        val before = image()
        view.event(MotionEvent.ACTION_DOWN, 350f)
        val during = image()
        assertTrue("DOWN must not pre-seek while a parent scroll is still possible", before.sameAs(during))
        view.event(MotionEvent.ACTION_MOVE, 350f, 145f, 150L)
        view.event(MotionEvent.ACTION_CANCEL, 350f, 145f, 160L)
        repeat(1000) { view.advancePhysics(1f / 120) }
        val after = image()
        assertTrue("Vertical scroll must leave the original lens position intact", before.sameAs(after))
        before.recycle(); during.recycle(); after.recycle()
    }
    @Test fun touchingASettlingLensDoesNotPublishItsLaggingVisualPosition() {
        val view = slider(.25f); var changes = 0; var dragging = 0
        view.change = { _, _ -> changes++ }; view.draggingChanged = { dragging++ }
        view.setValues(.8f, null)
        view.advancePhysics(1f / 120)
        view.event(MotionEvent.ACTION_DOWN, 120f)
        view.event(MotionEvent.ACTION_UP, 120f, time = 140L)
        assertEquals(0, changes); assertEquals(0, dragging)
    }
    @Test fun bubbleSpaceIsNotAHiddenSeekTarget() {
        val view = slider(); var changes = 0
        view.change = { _, _ -> changes++ }
        val e = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, 350f, 15f, 0)
        assertFalse(view.onTouchEvent(e)); e.recycle()
        assertEquals(0, changes)
    }
    @Test fun aSecondFingerCannotTakeOverTheSlider() {
        val view = slider(); var changes = 0
        view.change = { _, _ -> changes++ }
        view.event(MotionEvent.ACTION_DOWN, 115f)
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0 }, MotionEvent.PointerProperties().apply { id = 9 })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 115f; y = 68f }, MotionEvent.PointerCoords().apply { x = 380f; y = 68f })
        val e = MotionEvent.obtain(100L, 120L, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2,
            props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        view.onTouchEvent(e); e.recycle()
        view.event(MotionEvent.ACTION_MOVE, 380f, time = 140L)
        view.event(MotionEvent.ACTION_UP, 380f, time = 150L)
        assertEquals(0, changes)
    }
    @Test fun horizontalIntentAndCompletionNotifyExactlyOneDragLifecycle() {
        val view = slider(); val dragging = mutableListOf<Boolean>(); var finishes = 0
        view.draggingChanged = { dragging += it }; view.finished = { finishes++ }
        view.event(MotionEvent.ACTION_DOWN, 115f)
        assertTrue(dragging.isEmpty())
        view.event(MotionEvent.ACTION_MOVE, 200f, time = 140L)
        view.event(MotionEvent.ACTION_UP, 220f, time = 160L)
        view.event(MotionEvent.ACTION_CANCEL, 220f, time = 170L)
        assertEquals(listOf(true, false), dragging); assertEquals(1, finishes)
    }
    @Test fun rangeLensesCannotCrossOrViolateTheMinimumGap() {
        val view = slider(.2f, .7f, .15f); var result = 0f to 0f
        view.change = { a, b -> result = a to b }
        view.event(MotionEvent.ACTION_DOWN, 98f)
        view.event(MotionEvent.ACTION_MOVE, 400f, time = 200L)
        view.event(MotionEvent.ACTION_UP, 400f, time = 220L)
        assertTrue(result.second - result.first >= .1499f)
        assertEquals(.7f, result.second, .001f)
        repeat(500) { view.advancePhysics(1f / 120) }
    }
    @Test fun discreteSliderOnlyPublishesStopsAndEventuallySleeps() {
        val view = slider(0f, steps = 3); var result = -1f
        view.change = { a, _ -> result = a }
        view.event(MotionEvent.ACTION_DOWN, 235f); view.event(MotionEvent.ACTION_UP, 235f, time = 200L)
        assertTrue(result in setOf(0f, .25f, .5f, .75f, 1f))
        var moving = true
        repeat(2000) { moving = view.advancePhysics(1f / 120) }
        assertFalse("No idle redraw loop", moving)
    }
    @Test fun aCollapsedRangeCanBeExpandedAgain() {
        val view = slider(.5f, .5f); var end = .5f
        view.change = { _, b -> end = b }
        view.event(MotionEvent.ACTION_DOWN, 340f); view.event(MotionEvent.ACTION_UP, 340f, time = 200L)
        assertTrue(end > .5f)
    }
    @Test fun nativeSliderRendersBothThemesAndTheMagnifyingLens() {
        val view = slider(.55f)
        val folder = File("build/outputs/touch-zones-1.4.11").apply { mkdirs() }
        for (dark in listOf(false, true)) {
            view.configure(0f..1f, 4, true, false, 0f, 0xff417c67.toInt(), dark, false, false)
            val image = Bitmap.createBitmap(411, 110, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image); canvas.drawColor(if (dark) 0xff202522.toInt() else 0xfff8faf8.toInt()); view.draw(canvas)
            File(folder, "slider-${if (dark) "dark" else "light"}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertNotEquals(image.getPixel(200, 68), image.getPixel(200, 20))
            image.recycle()
        }
    }

    @Test fun paperOpensWithASmallSpringReboundAndLandsExactlyFlat() {
        val spring = PaperSpring(); var peak = 0f
        repeat(300) { spring.advance(1f, true, 1f/120); peak = maxOf(peak, spring.position) }
        assertTrue(peak in 1.06f..1.09f); assertEquals(1f, spring.position, 0f); assertEquals(0f, spring.velocity, 0f)
    }
    @Test fun closingUsesTheFasterSpringWithoutRebound() {
        val spring = PaperSpring().apply { position = 1f }; var previous = 1f
        repeat(240) { spring.advance(0f, false, 1f/120); assertTrue(spring.position <= previous); assertTrue(spring.position >= 0f); previous = spring.position }
        assertEquals(0f, spring.position, 0f); assertEquals(0f, spring.velocity, 0f)
    }
    @Test fun reversingTheTargetPreservesCurrentPositionAndMomentum() {
        val spring = PaperSpring(); repeat(12) { spring.advance(1f, true, 1f/120) }
        val position = spring.position; val velocity = spring.velocity
        assertTrue(position > 0f && velocity > 0f)
        spring.advance(0f, false, 0f)
        assertEquals(position, spring.position, .00001f); assertEquals(velocity, spring.velocity, .00001f)
        repeat(240) { spring.advance(0f, false, 1f/120) }; assertEquals(0f, spring.position, 0f)
    }
    @Test fun springMotionIsIndependentOfFrameSubdivision() {
        val whole = PaperSpring(); val half = PaperSpring()
        repeat(10) { whole.advance(1f, true, 1f/60); repeat(2) { half.advance(1f, true, 1f/120) } }
        assertEquals(whole.position, half.position, .00001f); assertEquals(whole.velocity, half.velocity, .0001f)
    }
    @Test fun pendingTriggerIsClaimedOnceAndCannotLeakIntoTheNextUnrelatedOverlay() {
        val source = PullOutSource().apply { bounds = Rect(10f, 10f, 80f, 60f) }
        PullOutSources.activate(source)
        assertSame(source, PullOutSources.claim())
        assertNull(PullOutSources.claim())
        PullOutSources.activate(source)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
        assertNull(PullOutSources.claim())
    }
    @Test fun disposedMenuItemReturnsToItsLivingParentTrigger() {
        val parent = PullOutSource().apply { bounds = Rect(10f, 10f, 80f, 60f) }
        val item = PullOutSource().apply { bounds = Rect(40f, 100f, 180f, 150f); this.parent = parent; disposed = true }
        PullOutSources.activate(item)
        assertSame(parent, PullOutSources.claim())
    }

    @Test fun liveWarpEndpointsAreExactAndInverseRowsStayOrderedForExternalSources() {
        val geometry = PaperWarpGeometry()
        val sources = listOf(Rect(120f, -140f, 280f, -100f), Rect(120f, 720f, 280f, 760f), Rect(100f, 260f, 260f, 310f))
        for (source in sources) {
            geometry.update(400f, 600f, source, 0f, 1f); assertEquals(source, geometry.frame)
            geometry.update(400f, 600f, source, 1f, 1f); assertEquals(Rect(0f, 0f, 400f, 600f), geometry.frame)
            for (i in 0..100) {
                geometry.update(400f, 600f, source, i / 100f, 1f)
                assertTrue(geometry.frame.width > 0f && geometry.frame.height > 0f)
                var previous = -1f
                for (row in 0 until PullOutConfig.WARP_ROWS) {
                    val k = row * 4
                    assertTrue(geometry.rows[k] >= previous); previous = geometry.rows[k]
                    assertTrue(geometry.rows[k] in 0f..1f)
                    assertTrue(geometry.rows[k+2] > 0f)
                    repeat(4) { assertTrue(geometry.rows[k+it].isFinite()) }
                }
            }
        }
    }

    @Test fun liveWarpHasANarrowNeckAndReusesItsUniformStorage() {
        val geometry = PaperWarpGeometry(); val data = geometry.rows
        geometry.update(400f, 600f, Rect(140f, 270f, 260f, 310f), .5f, 1f)
        val middle = data[(PullOutConfig.WARP_ROWS / 2) * 4 + 2]
        assertTrue(middle < data[2] * .8f)
        geometry.update(400f, 600f, Rect(140f, 270f, 260f, 310f), 1f, 1f)
        assertSame(data, geometry.rows)
        for (row in 0 until PullOutConfig.WARP_ROWS) {
            assertEquals(.5f, data[row*4+1], .001f)
            assertEquals(.5f, data[row*4+2], .001f)
            assertEquals(1f, data[row*4+3], .001f)
        }
    }

}
