package com.example.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.ui.HomeScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeEntryCompatibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun legacyCardEntryDoesNotExposeUnavailablePlatformTypes() {
        val methods = Class.forName("com.example.ui.components.GlassCardKt").declaredMethods
        assertTrue(methods.isNotEmpty())
        assertTrue(methods.none { method -> method.parameterTypes.any { it.name == "android.graphics.RuntimeShader" } })
        assertNull(createCardDentEffect("unsupported shader", 0f, 0f, 0.8f))
    }

    @Test fun unsupportedSystemsDoNotRecordRealtimeBackdropLayers() {
        assertFalse(RenderQuality.HIGH.realtimeGlass)
        assertFalse(RenderQuality.MAX.realtimeGlass)
        assertEquals(2, RenderQuality.HIGH.id)
    }

    @Config(sdk = [35])
    @Test fun capableSystemsKeepRealtimeGlassAtHighQuality() {
        assertTrue(RenderQuality.HIGH.realtimeGlass)
        assertTrue(RenderQuality.MAX.realtimeGlass)
        assertFalse(RenderQuality.MID.realtimeGlass)
    }

    @Config(sdk = [35])
    @Test fun rejectedCardShadersFallBackWithoutTerminatingComposition() {
        assertNull(createCardDentEffect("this is not valid AGSL", 0f, -0.6f, 0.8f))
    }

    @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    @Test fun defaultQualityCanComposeTheEmptyBookshelf() {
        compose.setContent {
            MaterialTheme {
                HomeScreen(
                    books = emptyList(), categories = emptyList(),
                    onBookClick = {}, onImportClick = {}, onAddCategory = {},
                    onSettingsClick = {}, onNavigateToShelf = {}, onNavigateToStats = {},
                    todayReadSecondsFlow = MutableStateFlow(0L),
                    streakDaysFlow = MutableStateFlow(0),
                    onDeleteBook = {}, onMoveBook = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("我的书架").assertExists()
        compose.onNodeWithText("「书架空空如也」").assertExists()
    }

    @Test fun defaultQualityCanComposeCardsAndBottomTabs() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    GlassCard { Text("Compatibility card") }
                    AppBottomTabBar(
                        items = listOf(AppTabItem("书架", Icons.Default.Book)),
                        selectedIndex = 0, onTabSelected = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Compatibility card").assertExists()
        compose.onNodeWithText("书架").assertExists()
    }
}
