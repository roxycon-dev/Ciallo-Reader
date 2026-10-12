package com.example.ui.comic

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.example.data.BackupManager
import com.example.data.PreferencesManager
import com.example.ui.SettingsTabScreen
import com.example.ui.components.LocalRenderQuality
import com.example.ui.components.RenderQuality
import com.example.ui.feedback.LocalReduceMotion
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-420dpi")
class Release133Test {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test fun driverWithWrongOrNonFiniteOutputIsRejected() {
        assertTrue(MangaJaNaiRuntime.acceptableError(0.000002f, 0.0000003))
        assertFalse(MangaJaNaiRuntime.acceptableError(0.05f, 0.0000003))
        assertFalse(MangaJaNaiRuntime.acceptableError(0.001f, 0.01))
        assertFalse(MangaJaNaiRuntime.acceptableError(Float.NaN, 0.0))
        assertFalse(MangaJaNaiRuntime.acceptableError(0f, Double.POSITIVE_INFINITY))
    }

    @Test fun aboutUsesAlignedEqualSizeControlsInMinimalMode() = checkCard(RenderQuality.MINIMAL, "minimal")
    @Test fun aboutUsesAlignedEqualSizeControlsInHighMode() = checkCard(RenderQuality.HIGH, "high")

    private fun checkCard(quality: RenderQuality, suffix: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferencesManager(context)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalRenderQuality provides quality, LocalReduceMotion provides true) {
                SettingsTabScreen(prefs, BackupManager(context, prefs), emptyList(), {})
            }
        } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("software-info"))
        compose.onNodeWithText("Ciallo Reader").assertIsDisplayed()
        compose.onNodeWithText("检查更新").assertIsDisplayed()
        val history = compose.onNodeWithText("更新目录").fetchSemanticsNode().boundsInRoot
        val github = compose.onNodeWithText("GitHub").fetchSemanticsNode().boundsInRoot
        assertEquals(history.top, github.top, 1f)
        assertEquals(history.height, github.height, 1f)
        compose.onNodeWithTag("software-info").captureRoboImage(
            File("../artifacts/release-1.3.3/about-$suffix.png").absolutePath)
        compose.onNodeWithText("更新目录").performClick()
        compose.onNodeWithText("1.3.2").assertIsDisplayed()
    }
}
