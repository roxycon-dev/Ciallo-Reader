package com.example.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.data.PreferencesManager
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnboardingWenkuDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test fun stepsRestoreAndBothBackPathsReturnToThePriorPage() {
        val restoration = StateRestorationTester(compose)
        var finishes = 0
        restoration.setContent {
            MyApplicationTheme {
                OnboardingScreen { finishes++ }
            }
        }
        compose.onNodeWithText("欢迎来到 Ciallo Reader").assertIsDisplayed()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("书库与自定义书源").assertIsDisplayed()
        compose.onNodeWithText("欢迎来到 Ciallo Reader").assertDoesNotExist()
        compose.onNodeWithText("下一步").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("按你的方式阅读").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一步").performClick()
        compose.onNodeWithText("书库与自定义书源").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("欢迎来到 Ciallo Reader").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, finishes) }
        compose.onNodeWithText("跳过").performClick()
        compose.onNodeWithText("跳过").assertIsNotEnabled()
        compose.onNodeWithText("下一步").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, finishes) }
    }

    @Test fun realStartupRoutesToHomeAndPersistsCompletion() {
        val prefs = PreferencesManager(ApplicationProvider.getApplicationContext())
        val previous = prefs.hasSeenOnboarding
        try {
            prefs.hasSeenOnboarding = false
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithTag("onboarding_screen").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("跳过").performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithTag("app_tab_bar_surface").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithTag("onboarding_screen").fetchSemanticsNodes().isEmpty()
                }
                compose.runOnIdle { assertEquals(true, prefs.hasSeenOnboarding) }
            }
        } finally {
            prefs.hasSeenOnboarding = previous
        }
    }

    @Test fun largeTextAndReducedMotionKeepThePrimaryActionReachable() {
        var finishes = 0
        compose.setContent {
            MyApplicationTheme {
                val density = LocalDensity.current.density
                CompositionLocalProvider(
                    LocalDensity provides Density(density, 1.75f),
                    LocalReduceMotion provides true,
                ) {
                    OnboardingScreen { finishes++ }
                }
            }
        }
        repeat(3) { compose.onNodeWithText("下一步").assertIsDisplayed().performClick() }
        compose.onNodeWithText("立即开启阅读之旅").assertIsDisplayed().performClick()
        compose.onNodeWithText("立即开启阅读之旅").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, finishes) }
    }
}
