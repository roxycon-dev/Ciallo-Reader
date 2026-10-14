package com.example.ui.components

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LiquidSegmentPhysicsTest {
    @Test fun springsAndSatellitesStopAtDifferentFrameRates() {
        for (hz in listOf(30, 60, 120)) {
            val p = LiquidSegmentPhysics().apply { resize(360f, 48f, 5, 0); select(4) }
            var moving = true
            var frames = 0
            while (moving && frames++ < hz * 10) {
                moving = p.step(1f / hz)
                assertTrue(p.left.x.isFinite() && p.right.x.isFinite())
                assertTrue(p.satellites.all { it.x.isFinite() && it.r.isFinite() })
            }
            assertFalse("idle renderer must stop at $hz Hz", moving)
            assertEquals(p.left.target, p.left.x, 0f)
            assertEquals(p.right.target, p.right.x, 0f)
            assertTrue(p.satellites.all { it.r == 0f })
        }
    }

    @Test fun rapidDragStretchesShedsDropsAndReturnsFromRubberBand() {
        val p = LiquidSegmentPhysics().apply { resize(360f, 48f, 5, 0); beginDrag() }
        repeat(10) { p.dragBy(38f, 2200f, false); p.step(1f / 60) }
        assertEquals(4, p.index)
        assertTrue(p.right.x - p.left.x > p.slotWidth)
        assertTrue(p.left.x < 4f + 380f) // Rubber-band compression beyond the track.
        assertTrue(p.satellites.any { it.r > 0f })
        p.endDrag()
        repeat(600) { p.step(1f / 60) }
        assertFalse(p.step(1f / 60))
        assertEquals(4f + p.slotWidth * 4, p.left.x, .001f)
    }

    @Test fun stationaryFingerDoesNotRunAnEndlessAnimation() {
        val p = LiquidSegmentPhysics().apply { resize(300f, 48f, 5, 0); beginDrag(); dragBy(150f, 1900f, false) }
        var moving = true
        repeat(600) { moving = p.step(1f / 60) }
        assertFalse(moving)
        assertTrue(p.dragging)
        assertTrue(abs(p.velocity) < 2f)
    }

    @Test fun cancellationAndReducedMotionSnapToAuthoritativeSelection() {
        val p = LiquidSegmentPhysics().apply { resize(300f, 48f, 5, 1); beginDrag(); dragBy(500f, 2400f, false) }
        p.endDrag(i = 1, reduceMotion = true)
        assertEquals(1, p.index)
        assertFalse(p.dragging)
        assertFalse(p.step(1f / 60))
        assertEquals(p.left.target, p.left.x, 0f)
        assertEquals(0f, p.press.x, 0f)
        p.resize(120f, 48f, 2, 100)
        assertEquals(1, p.index)
        assertTrue(p.slotWidth > 0f)
    }
}
