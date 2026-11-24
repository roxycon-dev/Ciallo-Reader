package com.example.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.source.impl.PublicNovelSite
import com.example.source.impl.PublicNovelSource
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * Issue #11 的量化口径：1000 章、每章 60 ms 网关往返，即用户报的「十几分钟下载不到 1 MB」。
 *
 * 这里不模拟真实网络，只把「一次往返的固定成本」和「每章固定睡眠」这两个变量放大，
 * 让新旧实现的差距落在可断言的数量级上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GuestNovelThroughputTest {
    private val bookId = "7600000000000000001"
    private val chapterCount = 200
    private val rttMs = 60L
    private val ids = (0 until chapterCount).map { "76100000000000%05d".format(it) }
    private val url = "https://fanqienovel.com/page/$bookId#ciallo-public-epub-v1"
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val inFlight = AtomicInteger()
    private val peak = AtomicInteger()

    private fun source(): PublicNovelSource {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val endpoint = request.url
            val body = when {
                endpoint.encodedPath.contains("directory") -> {
                    val rows = ids.joinToString(",") { i -> """{"itemId":"$i","title":"章节$i","needPay":0}""" }
                    """{"data":{"allItemIds":[${ids.joinToString(",") { "\"$it\"" }}],"chapterListWithVolume":[[$rows]]}}"""
                }
                endpoint.queryParameter("action") == "detail" ->
                    """{"code":0,"data":{"book_infos":[{"book_id":"$bookId","book_name":"吞吐测试","author":"作者"}]}}"""
                else -> {
                    val id = endpoint.queryParameter("item_id") ?: error("Unexpected request: $endpoint")
                    val current = inFlight.incrementAndGet()
                    peak.updateAndGet { maxOf(it, current) }
                    try { Thread.sleep(rttMs) } finally { inFlight.decrementAndGet() }
                    """{"code":0,"content":"<p>章节$id 的正文内容，约五十字。</p>"}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        return PublicNovelSource(context, PublicNovelSite.FANQIE, client = client)
    }

    @Test fun twoHundredChaptersFinishInAFractionOfTheSerialLowerBound() = runBlocking {
        val target = File.createTempFile("guest-throughput", ".tmp", context.cacheDir)
        try {
            val started = System.nanoTime()
            GuestNovelEpub.write(source(), url, target) { _, _ -> }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            // 串行下限 = 200 章 * 60 ms = 12 000 ms。旧实现还额外 sleep 200 ms/章（+40 000 ms）。
            val serialLowerBound = chapterCount * rttMs
            println("elapsed=${elapsedMs}ms serialLowerBound=${serialLowerBound}ms peak=$peak")

            assertTrue("Downloaded in ${elapsedMs}ms, which is not meaningfully faster than the " +
                "serial lower bound of ${serialLowerBound}ms", elapsedMs < serialLowerBound / 2)
            assertTrue("Expected overlapping requests, peak was ${peak.get()}", peak.get() >= 2)
            assertTrue("Peak concurrency must stay bounded, was ${peak.get()}",
                peak.get() <= GuestNovelEpub.CHAPTER_CONCURRENCY)
            assertEquals(chapterCount, GuestNovelIntegrity.epub(target).titles.size)
            ZipFile(target).use { zip ->
                assertEquals(ids.first(), zip.entries().asSequence()
                    .first { it.name == "OEBPS/chapter0.xhtml" }
                    .let { zip.getInputStream(it).bufferedReader().readText() }
                    .let { html -> ids.first().takeIf { html.contains(it) } })
            }
        } finally {
            target.delete()
            GuestNovelCheckpoint.clear(target)
        }
    }
}
