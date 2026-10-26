package com.example.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.MainActivity
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeEntryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var original: Map<String, *> = emptyMap<String, Any>()

    @Before fun prepare() {
        original = prefs.all.filterKeys { it in setOf("render_quality", "has_seen_onboarding") }
        prefs.edit().putInt("render_quality", 2).putBoolean("has_seen_onboarding", false).commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("onboarding_skip_button").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After fun restore() {
        scenario?.close()
        val editor = prefs.edit().remove("render_quality").remove("has_seen_onboarding")
        original.forEach { (key, value) -> when(value) {
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
        } }
        editor.commit()
    }

    private fun assertHomeAndRelaunch() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("我的书架").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("「书架空空如也」").assertExists()
        assertTrue(prefs.getBoolean("has_seen_onboarding", false))
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("我的书架").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("「书架空空如也」").assertExists()
        compose.onNodeWithTag("onboarding_screen").assertDoesNotExist()
    }

    @Test fun skipOpensTheHomepageAndTheNextLaunchStillWorks() {
        compose.onNodeWithTag("onboarding_skip_button").performClick()
        assertHomeAndRelaunch()
    }

    @Test fun finishingAllWelcomePagesOpensTheHomepageAndTheNextLaunchStillWorks() {
        repeat(4) { compose.onNodeWithTag("onboarding_action_button").performClick() }
        assertHomeAndRelaunch()
    }

    @Test fun settingsShowsAnExportButtonWithoutTheOldSmallPrint() {
        compose.onNodeWithTag("onboarding_skip_button").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("我的书架").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("设置").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("export_startup_diagnostics_button"))
        compose.onNodeWithTag("export_startup_diagnostics_button").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("仅含设备版本和错误堆栈，不含书籍、账号或阅读记录。由你选择是否分享。").assertDoesNotExist()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (bitmap != null) {
            val file = java.io.File(context.getExternalFilesDir(null), "diagnostics-button.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
