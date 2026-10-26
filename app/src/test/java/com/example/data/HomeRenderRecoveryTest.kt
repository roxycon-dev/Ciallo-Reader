package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeRenderRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE)
    private fun renderFailure(): Throwable = IllegalArgumentException("test render failure").apply {
        stackTrace = arrayOf(StackTraceElement("androidx.compose.ui.node.LayoutNode", "draw", "LayoutNode.kt", 1))
    }

    @Test fun renderCrashPersistsFallbackAndPreservesUserDataForTheNextProcess() {
        prefs.edit().putInt("render_quality", 2).putBoolean("has_seen_onboarding", true)
            .putString("test_reading_progress", "chapter-7").commit()
        HomeRenderRecovery.arm()
        HomeRenderRecovery.onUncaught(context, renderFailure())
        assertEquals(-1, prefs.getInt("render_quality", 2))
        assertTrue(prefs.getBoolean("has_seen_onboarding", false))
        assertEquals("chapter-7", prefs.getString("test_reading_progress", null))
        assertTrue(HomeRenderRecovery.consumeNotice(context))
        assertFalse(HomeRenderRecovery.consumeNotice(context))
    }

    @Test fun normalNavigationAndUnrelatedErrorsDoNotChangeTheQuality() {
        val token = HomeRenderRecovery.arm()
        HomeRenderRecovery.disarm(token)
        HomeRenderRecovery.onUncaught(context, renderFailure())
        assertEquals(2, prefs.getInt("render_quality", 2))
        HomeRenderRecovery.arm()
        HomeRenderRecovery.onUncaught(context, IllegalStateException("database unavailable"))
        assertEquals(2, prefs.getInt("render_quality", 2))
    }

    @Test fun anOldPageCannotDisarmANewerEntry() {
        val old = HomeRenderRecovery.arm()
        HomeRenderRecovery.arm()
        HomeRenderRecovery.disarm(old)
        HomeRenderRecovery.onUncaught(context, renderFailure())
        assertEquals(-1, prefs.getInt("render_quality", 2))
    }

    @Test fun stableSessionsAndFatalMemoryFailuresAreNotClassifiedAsEntryRendering() {
        HomeRenderRecovery.arm()
        ShadowSystemClock.advanceBy(Duration.ofMillis(HomeRenderRecovery.ENTRY_WINDOW_MS + 1))
        HomeRenderRecovery.onUncaught(context, renderFailure())
        assertEquals(2, prefs.getInt("render_quality", 2))
        HomeRenderRecovery.arm()
        val wrapper = renderFailure().apply { initCause(OutOfMemoryError("test")) }
        HomeRenderRecovery.onUncaught(context, wrapper)
        assertEquals(2, prefs.getInt("render_quality", 2))
    }
}
