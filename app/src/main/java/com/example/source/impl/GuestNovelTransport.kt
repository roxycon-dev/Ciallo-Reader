package com.example.source.impl

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** Two bounded retries for interrupted public GET requests; cancellation never becomes a retry. */
internal suspend fun <T> guestNovelIoRetry(block: suspend () -> T): T {
    for (attempt in 0..2) {
        try { return block() }
        catch (e: Exception) {
            val retryable = e is IOException || (e is com.example.source.SourceException.NetworkError &&
                Regex("HTTP (?:408|429|5[0-9]{2})\\b").containsMatchIn(e.message.orEmpty()))
            if (!retryable || attempt == 2) throw e
            currentCoroutineContext().ensureActive()
            delay(750L * (attempt + 1))
        }
    }
    error("Guest request retry exhausted")
}
