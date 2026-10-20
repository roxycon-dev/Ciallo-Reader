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
    @Test fun offscreenRowsPreservePixelBytesAndTruncatedFinalPadding() {
        val rows = PaperPixelBuffer(2, 2)
        for (stride in listOf(8, 16)) {
            val data = java.nio.ByteBuffer.allocateDirect(stride + 8)
            data.put(byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1))
            data.position(stride); data.put(byteArrayOf(0, 0, -1, -1, -128, 64, 32, -128))
            val bitmap = rows.copy(data, stride, 4)
            // Native Robolectric's desktop Skia N32 order differs from Android's
            // RGBA surface. Verify byte preservation, including premultiplied alpha,
            // independently of the host's getPixel channel interpretation.
            val copied = java.nio.ByteBuffer.allocateDirect(16)
            bitmap.copyPixelsToBuffer(copied); copied.rewind()
            val bytes = ByteArray(16); copied.get(bytes)
            assertArrayEquals(byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1,
                0, 0, -1, -1, -128, 64, 32, -128), bytes)
            bitmap.recycle()
        }
    }
    @Test fun firstAndFinalMeshFramesAreExactAtEveryVertex() {
        val anchor = Rect(180f, 700f, 340f, 748f)
        for (row in 0..150) for (col in 0..26) {
            val u = col / 26f; val v = row / 150f
            assertEquals(Offset(anchor.left + u * anchor.width, anchor.top + v * anchor.height), PaperMesh.vertex(u, v, 350f, 550f, anchor, 0f, 1f))
            assertEquals(Offset(u * 350f, v * 550f), PaperMesh.vertex(u, v, 350f, 550f, anchor, 1f, 1f))
            assertEquals(0f, PaperMesh.blend(v, 550f, 550f, 0f), 0f)
            assertEquals(1f, PaperMesh.blend(v, 550f, 550f, 1f), 0f)
        }
    }
    @Test fun endpointsHaveNoHiddenDiscontinuityForSourcesOutsideThePanel() {
        for (anchor in listOf(Rect(100f, -120f, 260f, -72f), Rect(100f, 700f, 260f, 748f), Rect(80f, 200f, 240f, 248f))) {
            for (u in listOf(0f, .5f, 1f)) for (v in listOf(0f, .5f, 1f)) {
                val start = PaperMesh.vertex(u, v, 350f, 550f, anchor, 0f, 1f)
                val nearStart = PaperMesh.vertex(u, v, 350f, 550f, anchor, .00001f, 1f)
                assertTrue((start - nearStart).getDistance() < .1f)
                val end = PaperMesh.vertex(u, v, 350f, 550f, anchor, 1f, 1f)
                val nearEnd = PaperMesh.vertex(u, v, 350f, 550f, anchor, .99999f, 1f)
                assertTrue((end - nearEnd).getDistance() < .1f)
            }
        }
    }
    @Test fun meshHasNoReversedRowsOrNonFiniteVerticesThroughoutOpeningAndOvershoot() {
        val anchor = Rect(130f, 520f, 270f, 568f)
        for (step in 0..108) for (col in 0..26) {
            var last = -Float.MAX_VALUE
            for (row in 0..150) {
                val point = PaperMesh.vertex(col / 26f, row / 150f, 390f, 600f, anchor, step / 100f, 1f)
                assertTrue(point.x.isFinite() && point.y.isFinite())
                assertTrue("Mesh must be continuous and never fold into inverted triangles", point.y >= last - .001f)
                last = point.y
            }
        }
    }
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
    @Test fun optimizedRowsPreserveTheOriginalSoftPaperGeometry() {
        val vertices = FloatArray(27 * 151 * 2)
        val colors = IntArray(27 * 151); val sourceColors = IntArray(colors.size)
        for (source in listOf(Rect(20f, -90f, 150f, -40f), Rect(120f, 540f, 280f, 588f))) {
            for (p in listOf(0f, .1f, .4f, .7f, 1f, 1.07f)) {
                val contributions = fillPaperMesh(vertices, colors, sourceColors, 390f, 620f, source, p, 1.5f)
                val expected = (if (colors.any { it ushr 24 > 0 }) 1 else 0) or
                    (if (sourceColors.any { it ushr 24 > 0 }) 2 else 0)
                assertEquals("Only zero-contribution texture passes may be omitted", expected, contributions)
                for (row in 0..150) for (col in 0..26) {
                    val expected = PaperMesh.vertex(col / 26f, row / 150f, 390f, 620f, source, p, 1.5f)
                    val index = (row * 27 + col) * 2
                    assertEquals(expected.x, vertices[index], .02f)
                    assertEquals(expected.y, vertices[index + 1], .02f)
                }
            }
        }
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

}
