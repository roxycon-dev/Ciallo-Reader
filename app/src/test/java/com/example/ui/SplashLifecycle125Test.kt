package com.example.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.PreferencesManager
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class SplashLifecycle125Test {
    @get:Rule val compose = createComposeRule()
    private val prefs get() = PreferencesManager(ApplicationProvider.getApplicationContext()).apply {
        splashPureMode = false
    }

    @Test fun disposingTheSplashDuringAnimationDoesNotNavigate() {
        val visible = mutableStateOf(true)
        val calls = AtomicInteger()
        val preferences = prefs
        compose.mainClock.autoAdvance = false
        compose.setContent { MyApplicationTheme { if (visible.value) SplashScreen(preferences) { calls.incrementAndGet() } } }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertEquals(0, calls.get())
    }

    @Test fun skippingThePosterFinishesOnlyOnce() {
        val calls = AtomicInteger()
        val preferences = prefs
        compose.mainClock.autoAdvance = false
        compose.setContent { MyApplicationTheme { SplashScreen(preferences) { calls.incrementAndGet() } } }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("点击跳过").performClick()
        compose.mainClock.advanceTimeBy(2_500)
        compose.waitForIdle()
        assertEquals(1, calls.get())
    }

    @Test fun timedCompletionUsesTheCurrentCallback() {
        val updated = mutableStateOf(false)
        val oldCalls = AtomicInteger()
        val newCalls = AtomicInteger()
        val preferences = prefs
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val current = updated.value
            MyApplicationTheme { SplashScreen(preferences) { if (current) newCalls.incrementAndGet() else oldCalls.incrementAndGet() } }
        }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { updated.value = true }
        compose.mainClock.advanceTimeBy(2_500)
        compose.waitForIdle()
        assertEquals(0, oldCalls.get())
        assertEquals(1, newCalls.get())
    }
}
