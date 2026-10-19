package com.example.source.zlibrary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun isZLibraryPage(html: String): Boolean =
    listOf("zlibrary.js", "z-cover", "z-bookcard", "book-item").any { html.contains(it, true) }

/** The caller supplies eligible hosts and route; cancelled losers close their sockets. */
internal suspend fun selectHealthyNode(
    candidates: List<String>, budgetMs: Long, check: suspend (String) -> Boolean
): String? = coroutineScope {
    if (candidates.isEmpty()) return@coroutineScope null
    val results = Channel<String?>(Channel.UNLIMITED)
    val jobs = candidates.map { host -> launch(Dispatchers.IO) { results.send(host.takeIf { check(it) }) } }
    try {
        withTimeoutOrNull(budgetMs) {
            repeat(candidates.size) { results.receive()?.let { return@withTimeoutOrNull it } }
            null
        }
    } finally {
        jobs.forEach { it.cancel() }
    }
}
