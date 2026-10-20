package com.example.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.test.core.app.ApplicationProvider
import com.example.ui.comic.*
import com.example.ui.reader.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TouchZoneSystemTest {
    @Test fun tapAndSwipeAreSeparate() {
        val zone = TouchZone(tap = TouchAction.CONTROLS, swipe = SwipeAction.PAGE, distanceDp = 72f)
        val restored = TouchZone.decode(TouchZone.encode(listOf(zone)))!!.single()
        assertEquals(zone, restored)
    }
    @Test fun noMovementCanOnlyBeAClick() {
        val decision = TouchGestureDecision(8f, 60f)
        decision.update(3f, -2f)
        assertTrue(decision.isTap()); assertFalse(decision.isSwipe(false))
    }
    @Test fun shortSwipeNeverFallsBackToAClick() {
        val decision = TouchGestureDecision(8f, 60f)
        decision.update(25f, 3f)
        assertFalse(decision.isTap()); assertFalse(decision.isSwipe(false))
        decision.update(0f, 0f)
        assertFalse("Returning to DOWN does not undo gesture classification", decision.isTap())
    }
    @Test fun configuredThresholdHasAnInclusiveBoundary() {
        val decision = TouchGestureDecision(8f, 72f)
        decision.update(-71.99f, 0f); assertFalse(decision.isSwipe(false))
        decision.update(-72f, 0f); assertTrue(decision.isSwipe(false)); assertFalse(decision.isTap())
    }
    @Test fun diagonalAndCornerMotionUsesTheFullTwoDimensionalDistance() {
        val decision = TouchGestureDecision(8f, 60f)
        decision.update(6f, 6f)
        assertFalse("Diagonal motion over native radial slop cannot become a click", decision.isTap())
        decision.update(65f, 100f)
        assertTrue(decision.isSwipe(false)); assertTrue(decision.isSwipe(true)); assertFalse(decision.isTap())
    }
    @Test fun calibratedDistanceOnlyOverridesFastReleaseAndNeverSlowNativeCornerSettlement() {
        val shortCorner = Offset(-30f, -40f)
        for (native in listOf(false, true)) {
            assertEquals(native, fastPageCommit(200f, shortCorner, Offset(-100f, -100f), 1f, native))
            assertEquals(native, fastPageCommit(null, shortCorner, Offset(-2000f, -2000f), 1f, native))
        }
        assertFalse(fastPageCommit(51f, shortCorner, Offset(-1000f, -1000f), 1f, true))
        assertTrue(fastPageCommit(50f, shortCorner, Offset(-1000f, -1000f), 1f, false))
        assertFalse("A quick return gesture must not finish the page", fastPageCommit(12f, shortCorner, Offset(1000f, 1000f), 1f, true))
        assertTrue(fastPageCommit(50f, Offset(0f, -100f), Offset(0f, -2000f), 2f, false))
        assertFalse(fastPageCommit(51f, Offset(-60f, -80f), Offset(-2000f, -2000f), 2f, true))
    }
    @Test fun forwardAndBackwardGuidesPointInOppositeDirectionsIncludingRtl() {
        assertEquals(-1f, swipeGuideSign(TouchAction.NEXT, false))
        assertEquals(1f, swipeGuideSign(TouchAction.PREVIOUS, false))
        assertEquals(1f, swipeGuideSign(TouchAction.NEXT, true))
        assertEquals(-1f, swipeGuideSign(TouchAction.PREVIOUS, true))
    }
    @Test fun pinchCancelsTheWholeGestureEvenAfterOneFingerLifts() {
        val decision = TouchGestureDecision(8f, 60f)
        decision.update(10f, 0f, 2); decision.update(200f, 0f, 1)
        assertTrue(decision.cancelled); assertFalse(decision.isSwipe(false)); assertFalse(decision.isTap())
    }
    @Test fun aConsumedChildGestureCannotAlsoTurnAPage() {
        val decision = TouchGestureDecision(8f, 60f)
        decision.update(100f, 0f, consumed = true)
        assertFalse(decision.isSwipe(false)); assertFalse(decision.isTap())
    }
    @Test fun topmostRectangleBlocksBothBehaviorsWithoutFallThrough() {
        val lower = TouchZone(left = 0f, top = 0f, right = 1f, bottom = 1f, tap = TouchAction.NEXT)
        val upper = lower.copy(id = "upper", tap = TouchAction.CONTROLS, swipe = SwipeAction.NONE)
        assertSame(upper, TouchZone.hit(listOf(lower, upper), .5f, .5f))
        assertEquals(SwipeAction.NONE, TouchZone.hit(listOf(lower, upper), .5f, .5f)!!.swipe)
        assertNull(TouchZone.hit(listOf(upper.copy(right = .3f)), .8f, .5f))
    }
    @Test fun malformedPreferencesFallBackToLegacyAndEmptyIsIntentional() {
        assertNull(TouchZone.decode("{broken")); assertNull(TouchZone.decode(null))
        assertEquals(emptyList<TouchZone>(), TouchZone.decode("[]"))
    }
    @Test fun importedRectanglesAndThresholdsAreBounded() {
        val fixed = TouchZone(left = Float.NaN, top = -3f, right = 15f, bottom = Float.POSITIVE_INFINITY, distanceDp = -100f).normalized()
        assertTrue(fixed.left.isFinite()); assertTrue(fixed.right <= 1f); assertTrue(fixed.top >= 0f)
        assertTrue(fixed.bottom - fixed.top >= .08f); assertEquals(12f, fixed.distanceDp)
    }
    @Test fun novelSettingsSurviveAStoreRecreationAndRestoreRemovesTheOverride() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val zones = listOf(TouchZone(distanceDp = 123f))
        NovelTouchZoneStore(context).save(zones)
        assertEquals(zones, NovelTouchZoneStore(context).load())
        NovelTouchZoneStore(context).save(null)
        assertNull(NovelTouchZoneStore(context).load())
    }
    @Test fun comicZonesRoundTripThroughPresetsAndPerBookConfig() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ComicSettingsStore(context)
        val config = ComicReaderConfig(touchZones = listOf(TouchZone(distanceDp = 64f)))
        store.saveBookConfig("test-zone", config)
        assertEquals(config.touchZones, store.effectiveConfig("test-zone").config.touchZones)
        assertEquals(config.touchZones, ComicReaderConfig.fromJson(config.toJson()).touchZones)
    }
    @Test fun legacyComicJsonDoesNotGainANewDefaultLayout() {
        val old = ComicReaderConfig.fromJson(JSONObject("{\"gestureTapLeft\":\"TOC\",\"gestureSwipe\":false}"))
        assertNull(old.touchZones); assertFalse(old.gestureSwipe)
        assertEquals(ComicGestureAction.TOC, old.gestureTapLeft)
    }
    @Test fun restoreUsesTheOriginalRtlAndVerticalResolvers() {
        val size = Size(900f, 1600f)
        assertEquals(ComicGestureAction.NEXT, resolveTapAction(Offset(10f, 500f), size, ComicReaderConfig()))
        assertEquals(ComicGestureAction.PREV, resolveTapAction(Offset(10f, 500f), size, ComicReaderConfig(direction = ComicDirection.LTR)))
        assertEquals(ComicGestureAction.NEXT, resolveTapAction(Offset(450f, 1500f), size, ComicReaderConfig(mode = ComicMode.WEBTOON)))
    }
    @Test fun customTapCoordinatesArePhysicalAndDoNotFlipWithReadingDirection() {
        val zone = TouchZone(left = 0f, top = 0f, right = .4f, bottom = 1f, tap = TouchAction.CONTROLS)
        for (direction in ComicDirection.entries) {
            val config = ComicReaderConfig(direction = direction, touchZones = listOf(zone))
            assertEquals(ComicGestureAction.TOGGLE_CONTROLS, resolveTapAction(Offset(10f, 500f), Size(900f, 1600f), config))
            assertEquals(ComicGestureAction.NONE, resolveTapAction(Offset(850f, 500f), Size(900f, 1600f), config))
        }
    }
    @Test fun webtoonSwipeTurnsAViewportWithoutSkippingTheRestOfATallImage() {
        val metrics = ComicScrollMetrics(listOf(6000, 1200), 0, 800, 0, 0, false, true)
        val next = metrics.target(metrics.pageFraction(true))
        assertEquals(0, next.index)
        assertEquals(720, next.offset)
        val middle = metrics.copy(firstVisibleItemScrollOffset = 1500, canScrollBackward = true)
        assertEquals(780, middle.target(middle.pageFraction(false)).offset)
    }
}
