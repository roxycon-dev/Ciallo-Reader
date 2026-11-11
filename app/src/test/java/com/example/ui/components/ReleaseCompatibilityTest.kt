package com.example.ui.components

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.MainActivity
import com.example.ui.comic.ComicVolumeKeyBridge
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReleaseCompatibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun closingGlassEffectBeforeApi31UsesTheFallbackSafely() {
        var shown by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalRenderQuality provides RenderQuality.MID) {
                if (shown) GlassDialogWindowEffect(compose.activity, 12f)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
    }

    @Test fun delegatedProducerPublishesAndRestartsWithItsKey() {
        var key by mutableStateOf("first")
        compose.setContent {
            val result by rememberAsyncState("waiting", key) { value = key }
            Text(result)
        }
        compose.onNodeWithText("first").assertExists()
        compose.runOnIdle { key = "second" }
        compose.onNodeWithText("second").assertExists()
    }

    @Test fun publicActivityKeyHooksPreserveReaderPressAndRelease() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val events = mutableListOf<Pair<Boolean, Boolean>>()
        ComicVolumeKeyBridge.handler = { up, down -> events.add(up to down); true }
        try {
            for (code in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
                assertTrue(activity.onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code)))
                assertTrue(activity.onKeyUp(code, KeyEvent(KeyEvent.ACTION_UP, code)))
            }
            assertEquals(listOf(true to true, true to false, false to true, false to false), events)
        } finally { ComicVolumeKeyBridge.handler = null }
    }
}
