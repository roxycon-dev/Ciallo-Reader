package com.example.source.impl

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** One bounded retry for interrupted public GET requests; cancellation never becomes a retry. */
internal suspend fun <T> guestNovelIoRetry(block: suspend () -> T): T {
    try { return block() }
    catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        delay(750)
        return block()
    }
}
