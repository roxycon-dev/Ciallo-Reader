package com.example.ui.components

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StartupDiagnosticsExportButtonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun visibleButtonOpensASaveDialogAndCancellationKeepsItUsable() {
        compose.setContent { MaterialTheme { StartupDiagnosticsExportButton() } }
        compose.onNodeWithTag("export_startup_diagnostics_button").assertIsDisplayed().assertHasClickAction().performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        assertEquals("text/plain", request.intent.type)
        compose.runOnIdle { compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null) }
        compose.onNodeWithText("导出启动诊断").assertIsEnabled()
    }

    @Test fun selectedDestinationReceivesTheCompleteDiagnosticBundle() {
        compose.setContent { MaterialTheme { StartupDiagnosticsExportButton() } }
        compose.onNodeWithTag("export_startup_diagnostics_button").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        val destination = File(compose.activity.cacheDir, "diagnostic-test-export.txt")
        try {
            compose.runOnIdle {
                compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
                    Intent().setData(Uri.fromFile(destination)))
            }
            compose.waitUntil(10_000) { destination.isFile && destination.length() > 100 }
            compose.waitForIdle()
            val report = destination.readText()
            assertTrue(report.contains("Ciallo Reader diagnostic bundle"))
            assertTrue(report.contains("Current threads"))
            assertTrue(report.contains("historical process exits"))
            compose.onNodeWithText("导出启动诊断").assertIsEnabled()
        } finally { destination.delete() }
    }
}
