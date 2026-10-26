package com.example.ui.components

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class ScrollTiltSchedulingTest {
    @Test fun activeIntegrationRetainsTheOriginalFloatTrajectory() {
        val controller = ScrollTiltController()
        val random = Random(7413)
        var expected = 0f
        var target = 0f
        var lastEmit = 0L
        var now = 1_000_000_000L
        repeat(10_000) { frame ->
            val dt = listOf(8.333333f, 16.7f, 33.333332f, 83f)[frame % 4]
            now += (dt * 1_000_000f).toLong()
            if (frame % 181 < 40) {
                val velocity = random.nextFloat() * 16f - 8f
                controller.submitVelocity(velocity)
                controller.lastEmitNanos = now
                target = (target * .5f + (velocity / 2.2f) * 4.5f * .5f).coerceIn(-4.5f, 4.5f)
                lastEmit = now
            }
            val idle = now - lastEmit > 120_000_000L
            if (idle) target = 0f
            val k = 1f - exp(-dt / if (idle) 260f else 55f)
            var next = expected + (target - expected) * k
            if (target == 0f && abs(next) < .02f) next = 0f
            expected = next
            controller.advance(now, dt)
            assertEquals("frame $frame", expected.toRawBits(), controller.tiltDeg.floatValue.toRawBits())
        }
    }

    @Test fun idleRequestsNoFramesAndNewInputWakesWithoutAnExtraFrame() = runTest {
        val clock = BroadcastFrameClock()
        val controller = ScrollTiltController()
        val job = launch(clock) { controller.runFrames() }
        runCurrent()
        assertFalse("An unused MAX host must not request a frame", clock.hasAwaiters)
        var now = System.nanoTime()
        repeat(3) { burst ->
            controller.submitVelocity(if (burst % 2 == 0) 2.2f else -2.2f)
            controller.lastEmitNanos = now
            runCurrent()
            assertTrue(clock.hasAwaiters)
            now += 16_666_667L
            clock.sendFrame(now)
            runCurrent()
            assertTrue("First requested frame must already move", controller.tiltDeg.floatValue != 0f)
            repeat(160) {
                now += 16_666_667L
                clock.sendFrame(now)
                runCurrent()
            }
            assertEquals(0f, controller.tiltDeg.floatValue, 0f)
            assertFalse("Settled motion must release its frame-clock subscription", clock.hasAwaiters)
        }
        job.cancelAndJoin()
        assertFalse(clock.hasAwaiters)
    }

    @Test fun resettingDiscardsPendingMotionBeforeTheHostRestarts() = runTest {
        val controller = ScrollTiltController()
        controller.submitVelocity(3f)
        controller.reset()
        val clock = BroadcastFrameClock()
        val job = launch(clock) { controller.runFrames() }
        runCurrent()
        assertEquals(0f, controller.tiltDeg.floatValue, 0f)
        assertFalse(clock.hasAwaiters)
        job.cancelAndJoin()
    }
}
