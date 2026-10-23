package com.example.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.ui.comic.ComicReaderTestActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-160dpi")
class ReaderProgressEffectTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private lateinit var scroll: ScrollState
    private var compositions = 0
    private var topVisible = false
    private val saves = mutableListOf<Pair<Int, Int>>()
    private var chapter by mutableIntStateOf(0)
    private var enabled by mutableStateOf(true)
    private var callbackVersion by mutableIntStateOf(0)

    private fun setup() {
        compose.setContent {
            scroll = rememberScrollState()
            val visible = rememberReaderScrollTopVisible(scroll, enabled).value
            SideEffect { compositions++; topVisible = visible }
            val version = callbackVersion
            ReaderScrollProgressEffect(scroll, enabled, 7, chapter) { saves += version to it }
            Box(Modifier.size(100.dp).verticalScroll(scroll)) { Box(Modifier.height(2_000.dp)) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun scrollTo(offset: Int) {
        compose.runOnIdle { runBlocking { scroll.scrollTo(offset) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test fun continuousScrollDoesNotRecomposeParentAndSavesOnlySettledOffset() {
        setup()
        val before = compositions
        repeat(60) { scrollTo(it + 1) }
        assertEquals(before, compositions)
        assertTrue(saves.isEmpty())
        compose.mainClock.advanceTimeBy(1_600)
        compose.waitForIdle()
        assertEquals(listOf(0 to 60), saves)
        scrollTo(800)
        assertTrue("offset=${scroll.value} max=${scroll.maxValue} compositions=$compositions", topVisible)
        assertEquals(before + 1, compositions)
        val afterThreshold = compositions
        scrollTo(900)
        assertEquals(afterThreshold, compositions)
    }

    @Test fun debounceUsesLatestCallbackAndCancelsWhenChapterChangesOrDisabled() {
        setup()
        scrollTo(10)
        compose.runOnIdle { callbackVersion = 1 }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_600)
        compose.waitForIdle()
        assertEquals(listOf(1 to 10), saves)
        saves.clear()
        scrollTo(20)
        compose.mainClock.advanceTimeBy(800)
        compose.runOnIdle { chapter = 1 }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(800)
        assertTrue("Old chapter debounce must be cancelled", saves.isEmpty())
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        scrollTo(30)
        compose.mainClock.advanceTimeBy(2_000)
        assertTrue(saves.isEmpty())
    }
}
