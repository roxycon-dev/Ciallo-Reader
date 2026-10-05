package com.example.ui.comic

import org.junit.Assert.*
import org.junit.Test

class ComicScrollMetricsTest {
    private fun metrics(sizes: List<Int>, index: Int = 0, offset: Int = 0, spacing: Int = 0,
        viewport: Int = 600, backward: Boolean = true, forward: Boolean = true) =
        ComicScrollMetrics(sizes, spacing, viewport, index, offset, backward, forward)

    @Test fun singleLongImageProgressChangesWithoutChangingThePage() {
        val start = metrics(listOf(1800), backward = false)
        assertEquals(0f, start.fraction, 0f)
        assertEquals(0.25f, start.copy(firstVisibleItemScrollOffset = 300, canScrollBackward = true).fraction, 0f)
        assertEquals(0.5f, start.copy(firstVisibleItemScrollOffset = 600, canScrollBackward = true).fraction, 0f)
        assertEquals(1f, start.copy(firstVisibleItemScrollOffset = 1200, canScrollBackward = true,
            canScrollForward = false).fraction, 0f)
    }

    @Test fun lastImageDoesNotReportCompletionUntilItsBottomIsVisible() {
        val last = metrics(listOf(600, 1800), index = 1)
        assertEquals(1f / 3f, last.fraction, 0.00001f)
        assertEquals(2f / 3f, last.copy(firstVisibleItemScrollOffset = 600).fraction, 0.00001f)
        assertEquals(1f, last.copy(firstVisibleItemScrollOffset = 1200, canScrollForward = false).fraction, 0f)
    }

    @Test fun mixedHeightsAndSpacingHaveContinuousProgressAcrossItemBoundaries() {
        val before = metrics(listOf(400, 1200, 800), offset = 419, spacing = 20)
        val after = before.copy(firstVisibleItemIndex = 1, firstVisibleItemScrollOffset = 0)
        assertTrue(after.fraction > before.fraction)
        assertEquals(420f / 1840f, after.fraction, 0.00001f)
        assertEquals(ComicScrollTarget(1, 0), before.target(after.fraction))
        assertEquals(ComicScrollTarget(2, 200), before.target(1f))
    }

    @Test fun sliderSeeksWithinOneImageAndCanReturnToEitherBoundary() {
        val scroll = metrics(listOf(1800), backward = false)
        assertEquals(ComicScrollTarget(0, 0), scroll.target(0f))
        assertEquals(ComicScrollTarget(0, 900), scroll.target(0.75f))
        assertEquals(ComicScrollTarget(0, 1200), scroll.target(1f))
        assertEquals(ComicScrollTarget(0, 0), scroll.target(Float.NaN))
        assertEquals(ComicScrollTarget(0, 1200), scroll.target(2f))
    }

    @Test fun emptyAndShortChaptersHaveFiniteProgressAndSafeSeekTargets() {
        for (sizes in listOf(emptyList(), listOf(300), listOf(100, 150))) {
            val scroll = metrics(sizes, backward = false, forward = false)
            assertEquals(0f, scroll.fraction, 0f)
            assertEquals(ComicScrollTarget(0, 0), scroll.target(1f))
        }
    }
}
