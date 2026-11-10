package com.example.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.example.ui.glasskit.GlassKitCard
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
@OptIn(ExperimentalRoborazziApi::class)
class GlassCardTweaksTest {
    @get:Rule val compose = createComposeRule()

    @Test fun minimalQualityStillAppliesCornerTintAndOpacity() = verifyMinimalCard { modifier ->
        GlassCard(modifier) { }
    }

    @Test fun glassKitMinimalQualityAppliesCornerTintAndOpacity() = verifyMinimalCard { modifier ->
        GlassKitCard(modifier) { }
    }

    private fun verifyMinimalCard(card: @Composable (Modifier) -> Unit) {
        val settings = mutableStateOf(CardTweaks(cornerRadiusDp = 2f, tintMix = 0f))
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color.Red, surface = Color.White)) {
                CompositionLocalProvider(
                    LocalRenderQuality provides RenderQuality.MINIMAL,
                    LocalCardTweaks provides settings.value
                ) {
                    Box(Modifier.size(110.dp).background(Color.Blue).testTag("sample"), contentAlignment = Alignment.Center) {
                        card(Modifier.size(100.dp))
                    }
                }
            }
        }
        val before = pixels()
        compose.runOnIdle { settings.value = CardTweaks(cornerRadiusDp = 40f, tintMix = .3f, cardAlpha = .5f) }
        val after = pixels()
        assertTrue(before.first.green > .95f && before.second.green > .95f)
        assertTrue(after.first.red < .65f && after.first.blue > .75f && after.first.green < .5f)
        assertTrue(after.second.red < .05f && after.second.blue > .95f)
    }

    private fun pixels(): Pair<Color, Color> {
        val file = File.createTempFile("ciallo-card-tweaks", ".png")
        try {
            compose.onNodeWithTag("sample").captureRoboImage(
                file.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)
            )
            val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
            try {
                return Color(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) to
                    Color(bitmap.getPixel(bitmap.width * 7 / 100, bitmap.height * 7 / 100))
            } finally {
                bitmap.recycle()
            }
        } finally {
            file.delete()
        }
    }
}
