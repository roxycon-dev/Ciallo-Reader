package com.example.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.themeTextContrast
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeMotionPolishTest {
    @get:Rule val compose = createComposeRule()
    @Test fun dayNightIntermediateFramesKeepBodyAndContainerTextReadable() {
        var dark by mutableStateOf(false)
        lateinit var scheme: ColorScheme
        compose.setContent { MyApplicationTheme(darkTheme = dark) {
            val current = MaterialTheme.colorScheme
            SideEffect { scheme = current }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        repeat(2) {
            compose.runOnIdle { dark = !dark }
            repeat(20) { frame ->
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                listOf(scheme.onSurface to scheme.surface, scheme.onBackground to scheme.background,
                    scheme.onPrimaryContainer to scheme.primaryContainer, scheme.onSecondaryContainer to scheme.secondaryContainer,
                    scheme.onErrorContainer to scheme.errorContainer).forEach { (ink, paper) ->
                    assertTrue("Unreadable frame $frame: $ink on $paper", themeTextContrast(ink, paper) >= 4.49f)
                }
            }
        }
    }
}
