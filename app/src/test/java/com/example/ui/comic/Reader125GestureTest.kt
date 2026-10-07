package com.example.ui.comic

import org.junit.Assert.*
import org.junit.Test

class Reader125GestureTest {
    @Test fun tabletTravelIsBoundedInDp() {
        assertEquals(104f, comicTurnTravel(1280f, 1f), 0.01f)
        assertEquals(104f, comicTurnTravel(2560f, 2f) / 2f, 0.01f)
        assertEquals(70.2f, comicTurnTravel(390f, 1f), 0.01f)
    }
    @Test fun aShortFastFlickTurnsButFingerJitterDoesNot() {
        for (density in listOf(1f, 2f, 3f)) {
            assertEquals(-1, comicTurnIntent(-24f * density, -1100f * density, 1000f * density, density))
            assertEquals(0, comicTurnIntent(-10f * density, -3000f * density, 390f * density, density))
            assertEquals(0, comicTurnIntent(-30f * density, -200f * density, 390f * density, density))
        }
    }
    @Test fun slowTabletDragAndReversalHaveDifferentIntents() {
        assertEquals(-1, comicTurnIntent(-110f, -100f, 1280f, 1f))
        assertEquals(1, comicTurnIntent(110f, 100f, 1280f, 1f))
        assertEquals(0, comicTurnIntent(-110f, 1400f, 1280f, 1f))
        assertEquals(0, comicTurnIntent(Float.NaN, 1000f, 1280f, 1f))
    }
    @Test fun tapSidesRemainIndependentOfSwipeIntent() {
        val ltr = ComicReaderConfig(direction = ComicDirection.LTR)
        val rtl = ltr.copy(direction = ComicDirection.RTL)
        val size = androidx.compose.ui.geometry.Size(1000f, 700f)
        val left = androidx.compose.ui.geometry.Offset(80f, 350f)
        assertEquals(ComicGestureAction.PREV, resolveTapAction(left, size, ltr))
        assertEquals(ComicGestureAction.NEXT, resolveTapAction(left, size, rtl))
        assertEquals(-1, comicTurnIntent(-110f, -100f, size.width, 1f))
    }
}
