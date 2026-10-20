package com.example.source.impl

import com.example.source.SourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuestNovelTransportTest {
    @Test fun transientServerErrorGetsBoundedRetry() = runBlocking {
        var calls = 0
        val value = guestNovelIoRetry {
            if (++calls < 3) throw SourceException.NetworkError("网文源返回 HTTP 503")
            "正文"
        }
        assertEquals("正文", value)
        assertEquals(3, calls)
    }
    @Test fun accessRestrictionAndCancellationAreNeverRetried() = runBlocking {
        for (failure in listOf(SourceException.NetworkError("源站返回 HTTP 403"), CancellationException("cancelled"))) {
            var calls = 0
            try { guestNovelIoRetry { calls++; throw failure }; fail("Failure was swallowed") }
            catch (actual: Exception) { assertSame(failure, actual) }
            assertEquals(1, calls)
        }
    }
}
