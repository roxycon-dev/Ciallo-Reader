package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StartupDiagnosticsBundleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun exceptionsKeepDeepFramesCausesAndSuppressedFailuresWhileRedactingCredentials() {
        val root = IllegalStateException("missing RenderEffect; token=secret-value")
        root.stackTrace = Array(220) { StackTraceElement("renderer.Frame$it", "draw", "Renderer.kt", it + 1) }
        root.addSuppressed(IllegalArgumentException("unsupported graphics format"))
        root.initCause(NoClassDefFoundError("android.graphics.RenderEffect"))
        val text = StartupDiagnostics.describe(root)
        assertTrue(text.contains("Frame219.draw(Renderer.kt:220)"))
        assertTrue(text.contains("Suppressed:"))
        assertTrue(text.contains("Caused by:"))
        assertTrue(text.contains("android.graphics.RenderEffect"))
        assertFalse(text.contains("secret-value"))
    }

    @Test fun recentNonfatalFailuresCannotEraseTheLastCrash() {
        StartupDiagnostics.record(context, "uncaught", IllegalStateException("original-crash-marker"))
        repeat(12) { StartupDiagnostics.record(context, "optional-$it", IllegalArgumentException("optional-error-$it")) }
        assertEquals(8, File(context.filesDir, "diagnostic-history").listFiles()!!.count { it.extension == "txt" })
        val report = StartupDiagnostics.read(context)
        assertTrue(report.contains("original-crash-marker"))
        assertTrue(report.contains("optional-error-11"))
        assertTrue(report.contains("manufacturer="))
        assertTrue(report.contains("fingerprint="))
        assertTrue(report.contains("heap used="))
        assertTrue(report.contains("Threads at failure"))
        assertTrue(report.contains("ApplicationExitInfo"))
    }

    @Test fun breadcrumbsRetainBoundedNavigationAndTaskResults() = runBlocking {
        repeat(300) { StartupDiagnostics.breadcrumb(context, "route:screen-$it") }
        diagnosedStartupTask(context, "source-initialization") { 42 }
        diagnosedStartupTask(context, "optional-failure") { throw IllegalStateException("test") }
        val report = StartupDiagnostics.read(context)
        assertTrue(report.contains("route:screen-299"))
        assertTrue(report.contains("task:source-initialization:start"))
        assertTrue(report.contains("task:source-initialization:complete"))
        assertTrue(report.contains("task:optional-failure:failed"))
        assertFalse(report.contains("route:screen-0\n"))
    }

    @Test fun messagesAndPathsAreSanitizedAndOversizedSystemTracesAreTruncated() {
        val text = StartupDiagnostics.redact("authorization: Bearer private123\nhttps://example.test/book?key=private456\n/data/user/0/app/private.txt\nperson@example.test")
        assertFalse(text.contains("private123"))
        assertFalse(text.contains("private456"))
        assertFalse(text.contains("private.txt"))
        assertFalse(text.contains("person@example.test"))
        val limited = StartupDiagnostics.readLimited(ByteArrayInputStream(ByteArray(1024) { 65 }), 16)
        assertTrue(limited.startsWith("A".repeat(16)))
        assertTrue(limited.contains("size limit"))
        assertTrue(limited.length < 100)
    }
}
