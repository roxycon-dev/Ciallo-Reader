package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.kashif_e.backdrop.BackdropEffectScope
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.backdrops.rememberPreBlurredBackdrop
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.comic.ComicReaderTestActivity
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PreBlurredSourceLifecycleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test fun replacingTheSourceRebuildsEvenWhenThemeSizeAndEffectsStayTheSame() {
        // Test source scheduling independently of the optional capability probe.
        val support = Class.forName("com.kashif_e.backdrop.backdrops.PreBlurSupport")
        val owner = support.getField("INSTANCE").get(null)
        fun get(name: String) = support.getMethod("get$name").invoke(owner) as Boolean
        fun set(name: String, value: Boolean) = support.getMethod("set$name", Boolean::class.javaPrimitiveType).invoke(owner, value)
        val verified = get("Verified")
        val supported = get("Supported")
        set("Verified", true); set("Supported", true)
        try {
            var second by mutableStateOf(false)
            var buildKey by mutableIntStateOf(0)
            val calls = AtomicInteger()
            compose.setContent {
                val a = rememberLayerBackdrop()
                val b = rememberLayerBackdrop()
                val effects: BackdropEffectScope.() -> Unit = remember { { calls.incrementAndGet(); Unit } }
                rememberPreBlurredBackdrop(if (second) b else a, buildKey, effects, settleDelayMs = 0)
                Column {
                    Box(Modifier.size(40.dp).layerBackdrop(a).background(Color.Red))
                    Box(Modifier.size(40.dp).layerBackdrop(b).background(Color.Blue))
                }
            }
            compose.waitForIdle()
            val initial = calls.get()
            compose.runOnIdle { buildKey++ }
            compose.waitUntil(5000) { calls.get() == initial + 1 }
            compose.waitForIdle()
            val beforeReplacement = calls.get()
            compose.runOnIdle { second = true }
            compose.waitUntil(5000) { calls.get() == beforeReplacement + 1 }
            compose.waitForIdle()
            assertEquals(beforeReplacement + 1, calls.get())
        } finally {
            set("Verified", verified); set("Supported", supported)
        }
    }
}
