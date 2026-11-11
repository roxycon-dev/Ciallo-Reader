package com.example.data

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.ui.SettingsTabScreen
import com.example.ui.ThemePaletteScreen
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.components.LocalRenderQuality
import com.example.ui.components.RenderQuality
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
class AdultSourceDefaultsTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val storage get() = context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE)

    @Before fun clearPreferences() { storage.edit().clear().commit() }

    @Test fun freshInstallAlwaysEnablesSources() {
        assertTrue(PreferencesManager(context).showAdultSources)
    }

    @Test fun legacyClosedValuesCannotHideSourcesAndOtherPreferencesAreKept() {
        storage.edit().putBoolean("show_adult_sources", false).putFloat("font_size", 24f).commit()
        val prefs = PreferencesManager(context)
        assertTrue(prefs.showAdultSources)
        assertEquals(24f, prefs.fontSize, 0f)
        prefs.showAdultSources = false
        assertTrue(PreferencesManager(context).showAdultSources)
        assertTrue(storage.getBoolean("show_adult_sources", false))
        // An old backup restoring false must not reintroduce the retired gate.
        storage.edit().putBoolean("show_adult_sources", false).commit()
        assertTrue(prefs.showAdultSources)
        prefs.showAdultSources = true
        assertTrue(PreferencesManager(context).showAdultSources)
    }

    @Test fun palettePersistsIndependentColorsAndRepeatedTapsDoNotChangeSourceAccess() {
        val prefs = PreferencesManager(context)
        val callbacks = mutableListOf<Boolean>()
        var showingPalette by mutableStateOf(false)
        var primary by mutableIntStateOf(prefs.colorPrimaryIndex)
        var secondary by mutableIntStateOf(prefs.colorSecondaryIndex)
        compose.setContent {
            MyApplicationTheme(darkTheme = false, colorPrimaryIndex = primary, colorSecondaryIndex = secondary) {
                CompositionLocalProvider(LocalRenderQuality provides RenderQuality.MINIMAL, LocalReduceMotion provides true) {
                    if (showingPalette) {
                        ThemePaletteScreen(
                            primary, secondary,
                            onPrimaryChange = {
                                prefs.colorPrimaryIndex = it
                                primary = it
                            },
                            onSecondaryChange = {
                                prefs.colorSecondaryIndex = it
                                secondary = it
                            },
                            onBack = { showingPalette = false },
                        )
                    } else {
                        SettingsTabScreen(
                            prefs, BackupManager(context, prefs), emptyList(), {},
                            onAdultSourcesChange = { callbacks.add(it) },
                            onOpenThemePalette = { showingPalette = true },
                        )
                    }
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("主题配色方案"))
        compose.onNodeWithText("主题配色方案").performClick()
        repeat(6) { compose.onNodeWithContentDescription("主色调 · 蓝").performClick() }
        compose.onNodeWithContentDescription("强调色 · 粉").performClick()
        compose.onNodeWithContentDescription("主色调 · 蓝").assertIsSelected()
        compose.onNodeWithContentDescription("强调色 · 粉").assertIsSelected()
        compose.onNodeWithTag("theme_palette_preview_0")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "#2563EB"))
        compose.onNodeWithTag("theme_palette_preview_1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "#EC4899"))
        compose.onNodeWithContentDescription("返回").performClick()
        assertThrows(AssertionError::class.java) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("带你登大郎~~~"))
        }
        assertEquals(0, PreferencesManager(context).colorPrimaryIndex)
        assertEquals(3, PreferencesManager(context).colorSecondaryIndex)
        assertTrue(prefs.showAdultSources)
        assertTrue(callbacks.isEmpty())
    }
}
