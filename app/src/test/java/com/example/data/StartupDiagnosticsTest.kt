package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StartupDiagnosticsTest {
    @Test fun failedOptionalTaskDoesNotPreventNextTask() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val failure = java.io.IOException("disk unavailable")
        assertNull(optionalStartupTask(failures::add) { throw failure })
        assertEquals(listOf(failure), failures)
        assertEquals(42, optionalStartupTask(failures::add) { 42 })
    }

    @Test fun nativeLinkFailureIsIsolatedButFatalMemoryErrorPropagates() = runBlocking {
        val failures = mutableListOf<Throwable>()
        assertNull(optionalStartupTask(failures::add) { throw UnsatisfiedLinkError("native") })
        assertTrue(failures.single() is UnsatisfiedLinkError)
        val fatal = OutOfMemoryError("fatal")
        try {
            optionalStartupTask(failures::add) { throw fatal }
            fail("Must propagate memory exhaustion")
        } catch (actual: OutOfMemoryError) { assertSame(fatal, actual) }
    }

    @Test fun cancellationPropagatesWithoutBeingReported() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val cancelled = CancellationException("cancelled")
        try {
            optionalStartupTask(failures::add) { throw cancelled }
            fail("Must cancel")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
        assertTrue(failures.isEmpty())
    }

    @Test fun reportOmitsSensitiveMessagesAndBoundsCauseCycles() {
        val first = IllegalStateException("secret-token /private/book/title")
        val second = RuntimeException("password")
        first.initCause(second)
        second.initCause(first)
        val report = StartupDiagnostics.describe(first)
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("StartupDiagnosticsTest"))
        assertFalse(report.contains("secret-token"))
        assertFalse(report.contains("password"))
        assertFalse(report.contains("/private/book/title"))
        assertTrue(report.length <= 24 * 1024)
    }
}
