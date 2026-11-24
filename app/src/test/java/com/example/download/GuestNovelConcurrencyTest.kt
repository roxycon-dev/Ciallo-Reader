package com.example.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.source.impl.PublicNovelSite
import com.example.source.impl.PublicNovelSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * Issue #11, part 1: the whole-book export must overlap chapter requests instead of paying one
 * round-trip (plus a fixed 200 ms sleep) per chapter, while keeping strict TOC order in the EPUB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GuestNovelConcurrencyTest {
    private val bookId = "7600000000000000001"
    private val chapterCount = 24
    private val ids = (0 until chapterCount).map { "76000000000000000%02d".format(it + 10) }
    private val url = "https://fanqienovel.com/page/$bookId#ciallo-public-epub-v1"
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private val inFlight = AtomicInteger()
    private val peak = AtomicInteger()
    private val requestedOrder = Collections.synchronizedList(mutableListOf<String>())
    private val requests = ConcurrentHashMap<String, Int>()

    private fun source(): PublicNovelSource {
        // Each chapter body takes 120 ms of "network": a serial implementation cannot finish the
        // book faster than 24 * 120 ms = 2.9 s, and its peak concurrency stays 1.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val endpoint = request.url
            val body = when {
                endpoint.encodedPath.contains("directory") -> {
                    val rows = ids.joinToString(",") { i -> """{"itemId":"$i","title":"章节$i","needPay":0}""" }
                    """{"data":{"allItemIds":[${ids.joinToString(",") { "\"$it\"" }}],"chapterListWithVolume":[[$rows]]}}"""
                }
                endpoint.queryParameter("action") == "detail" ->
                    """{"code":0,"data":{"book_infos":[{"book_id":"$bookId","book_name":"并发测试","author":"作者"}]}}"""
                else -> {
                    val id = endpoint.queryParameter("item_id") ?: error("Unexpected request: $endpoint")
                    requests.merge(id, 1, Int::plus)
                    requestedOrder += id
                    val current = inFlight.incrementAndGet()
                    peak.updateAndGet { maxOf(it, current) }
                    try { Thread.sleep(120) } finally { inFlight.decrementAndGet() }
                    """{"code":0,"content":"<p>章节$id 的完整中文正文。</p>"}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        return PublicNovelSource(context, PublicNovelSite.FANQIE, client = client)
    }

    @Test fun chaptersAreFetchedConcurrentlyButWrittenInTableOfContentsOrder() = runBlocking {
        val target = File.createTempFile("guest-parallel", ".tmp", context.cacheDir)
        try {
            val started = System.nanoTime()
            GuestNovelEpub.write(source(), url, target) { _, _ -> }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            // Real overlap, not just a bigger timeout budget.
            assertTrue("Expected overlapping chapter requests, peak in-flight was ${peak.get()}", peak.get() >= 2)
            assertTrue("Concurrency must stay bounded by the dispatcher limit, peak was ${peak.get()}",
                peak.get() <= GuestNovelEpub.CHAPTER_CONCURRENCY)
            // 24 serial 120 ms round-trips need >= 2880 ms, plus the old 24 * 200 ms sleeps.
            assertTrue("Parallel fetch took ${elapsedMs}ms, which is not faster than serial", elapsedMs < 2_400)

            assertEquals(chapterCount, requests.size)
            assertTrue("Every chapter must be fetched exactly once: $requests", requests.values.all { it == 1 })
            // The look-ahead window is the only thing bounding how far ahead the pool can read, so
            // every request in the head of the run must belong to the first window of chapters.
            val head = requestedOrder.take(GuestNovelEpub.PRELOAD_WINDOW)
            assertTrue("Look-ahead escaped the window: $head",
                head.all { ids.indexOf(it) in 0 until GuestNovelEpub.PRELOAD_WINDOW })
            assertEquals("The window must be fully used before the writer consumes ahead",
                GuestNovelEpub.PRELOAD_WINDOW, head.size)

            // The EPUB must still be strictly ordered although workers finish out of order.
            assertEquals(chapterCount, GuestNovelIntegrity.epub(target).titles.size)
            ZipFile(target).use { zip ->
                ids.forEachIndexed { index, id ->
                    val html = zip.getInputStream(zip.getEntry("OEBPS/chapter$index.xhtml")).bufferedReader().readText()
                    assertTrue("chapter$index.xhtml must contain $id", html.contains(id))
                }
            }
        } finally {
            target.delete()
            GuestNovelCheckpoint.clear(target)
        }
    }

    @Test fun aPausedDownloadKeepsEveryVerifiedChapterForResume() = runBlocking {
        // A pause in the middle must leave the verified chapters on disk; otherwise resuming a
        // 1000-chapter book starts from zero and the speed-up is lost.
        val target = File.createTempFile("guest-parallel-pause", ".tmp", context.cacheDir)
        try {
            var seen = 0
            try {
                GuestNovelEpub.write(source(), url, target) { done, _ ->
                    seen = done
                    if (done == 6) throw CancellationException("pause")
                }
                fail("Expected pause")
            } catch (_: CancellationException) { }
            assertFalse(target.exists())
            val saved = GuestNovelCheckpoint(target, "fanqie", url, ids.mapIndexed { i, id ->
                com.example.source.ComicChapter("https://fanqienovel.com/reader/$id", "章节$id", order = i.toFloat())
            })
            assertTrue("A paused run must persist the chapters it verified (progress=$seen)",
                saved.completedChapters >= 6)
            GuestNovelEpub.write(source(), url, target) { _, _ -> }
            // Resume re-uses the checkpoint instead of refetching the saved prefix.
            assertTrue("Checkpointed chapters were refetched: $requests",
                requests.filterKeys { ids.indexOf(it) < 6 }.values.all { it == 1 })
            assertEquals(chapterCount, GuestNovelIntegrity.epub(target).titles.size)
        } finally {
            target.delete()
            GuestNovelCheckpoint.clear(target)
        }
    }
}
