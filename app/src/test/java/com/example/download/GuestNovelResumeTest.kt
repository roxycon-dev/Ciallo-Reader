package com.example.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.source.SourceException
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
import java.io.IOException
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GuestNovelResumeTest {
    private val bookId = "7600000000000000001"
    private val ids = listOf("7600000000000000002", "7600000000000000003")
    private val url = "https://fanqienovel.com/page/$bookId#ciallo-public-epub-v1"
    private val requests = mutableMapOf<String, Int>()
    private var failChapter: String? = null
    private var reversed = false
    private var failFinalDirectory = false
    private var directoryRequests = 0
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun source(): PublicNovelSource {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val endpoint = request.url
            val body = when {
                endpoint.encodedPath.contains("directory") -> {
                    if (++directoryRequests >= 3 && failFinalDirectory) throw IOException("network switched during final check")
                    val chapters = if (reversed) ids.reversed() else ids
                    val rows = chapters.joinToString(",") { """{"itemId":"$it","title":"章节$it","needPay":0}""" }
                    """{"data":{"allItemIds":[${chapters.joinToString(",") { "\"$it\"" }}],"chapterListWithVolume":[[$rows]]}}"""
                }
                endpoint.queryParameter("action") == "detail" ->
                    """{"code":0,"data":{"book_infos":[{"book_id":"$bookId","book_name":"断点测试","author":"作者"}]}}"""
                else -> {
                    val id = endpoint.queryParameter("item_id") ?: error("Unexpected request: $endpoint")
                    requests[id] = requests.getOrDefault(id, 0) + 1
                    if (id == failChapter) throw IOException("network switched")
                    """{"code":0,"content":"<p>章节$id 的完整中文正文。</p>"}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        return PublicNovelSource(context, PublicNovelSite.FANQIE, client = client)
    }

    private suspend fun withTarget(block: suspend (File) -> Unit) {
        val target = File.createTempFile("guest-resume", ".tmp", context.cacheDir)
        try { block(target) } finally { target.delete(); GuestNovelCheckpoint.clear(target) }
    }

    @Test fun networkFailureResumesSavedChaptersWithANewAdapterAndRestoresProgress() = runBlocking {
        withTarget { target ->
            failChapter = ids[1]
            try { GuestNovelEpub.write(source(), url, target) { _, _ -> }; fail("Expected network failure") }
            catch (_: IOException) { }
            assertFalse("An incomplete EPUB must never remain importable", target.exists())
            assertEquals(1, requests[ids[0]])
            failChapter = null
            val progress = mutableListOf<Int>()
            GuestNovelEpub.write(source(), url, target) { completed, _ -> progress += completed }
            assertEquals(1, progress.first())
            assertEquals(2, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
            assertEquals("Already completed chapters must not hit the source again", 1, requests[ids[0]])
            assertEquals(2, GuestNovelIntegrity.epub(target).titles.size)
            ZipFile(target).use { zip ->
                ids.forEachIndexed { index, id ->
                    assertTrue(zip.getInputStream(zip.getEntry("OEBPS/chapter$index.xhtml")).bufferedReader().readText().contains(id))
                }
            }
        }
    }

    @Test fun pauseKeepsCheckpointAndChangedDirectoryInvalidatesIt() = runBlocking {
        withTarget { target ->
            try {
                GuestNovelEpub.write(source(), url, target) { done, _ -> if (done == 1) throw CancellationException("pause") }
                fail("Expected pause")
            } catch (_: CancellationException) { }
            assertFalse(target.exists())
            reversed = true
            GuestNovelEpub.write(source(), url, target) { _, _ -> }
            assertEquals(2, requests[ids[0]])
            assertEquals(1, requests[ids[1]])
            ZipFile(target).use { zip ->
                assertTrue(zip.getInputStream(zip.getEntry("OEBPS/chapter0.xhtml")).bufferedReader().readText().contains(ids[1]))
            }
        }
    }

    @Test fun manualPauseResumesWithoutFetchingCompletedChaptersAgain() = runBlocking {
        withTarget { target ->
            try {
                GuestNovelEpub.write(source(), url, target) { done, _ -> if (done == 1) throw CancellationException("pause") }
                fail("Expected pause")
            } catch (_: CancellationException) { }
            GuestNovelEpub.write(source(), url, target) { _, _ -> }
            assertEquals(1, requests[ids[0]])
            assertEquals(1, requests[ids[1]])
        }
    }

    @Test fun illustrationCheckpointSurvivesRecreationAndRejectsDamagedBytes() = runBlocking {
        withTarget { target ->
            val toc = listOf(com.example.source.ComicChapter("chapter", "第一章"))
            val imageUrl = "https://example.com/image.png"
            val cache = GuestNovelCheckpoint(target, "quanben", "book", toc)
            val image = GuestNovelCheckpoint.Image(byteArrayOf(1, 2, 3), "image/png", "png")
            cache.saveImage(imageUrl, image)
            val restored = GuestNovelCheckpoint(target, "quanben", "book", toc)
            assertArrayEquals(image.bytes, restored.image(imageUrl)!!.bytes)
            val directory = File(target.parentFile, "${target.nameWithoutExtension}.chapters")
            directory.listFiles()!!.single { it.extension == "image" }.writeBytes(byteArrayOf(9))
            assertNull(restored.image(imageUrl))
            GuestNovelCheckpoint.clear(target)
            assertFalse(directory.exists())
        }
    }

    @Test fun corruptChapterIsFetchedAgainWhileOtherSavedChaptersAreReused() = runBlocking {
        withTarget { target ->
            GuestNovelEpub.write(source(), url, target) { _, _ -> }
            File(target.parentFile, "${target.nameWithoutExtension}.chapters/chapter0.json").writeText("broken checkpoint")
            GuestNovelEpub.write(source(), url, target) { _, _ -> }
            assertEquals(2, requests[ids[0]])
            assertEquals(1, requests[ids[1]])
            assertEquals(2, GuestNovelIntegrity.epub(target).titles.size)
        }
    }

    @Test fun finalDirectoryNetworkFailureCanRepackWithoutDownloadingAnyChapterAgain() = runBlocking {
        withTarget { target ->
            failFinalDirectory = true
            try {
                GuestNovelEpub.write(source(), url, target) { _, _ -> }
                fail("Expected failure during the final directory check")
            } catch (_: SourceException.NetworkError) { }
            assertFalse(target.exists())
            failFinalDirectory = false
            GuestNovelEpub.write(source(), url, target) { _, _ -> }
            assertEquals(1, requests[ids[0]])
            assertEquals(1, requests[ids[1]])
        }
    }

    @Test fun retryPolicyIsBoundedAndDoesNotRetryInvalidContentOrUserCancellation() {
        assertTrue(DownloadWorker.shouldRetryPublicNovel("fanqie", IOException("connection reset"), 0))
        assertTrue(DownloadWorker.shouldRetryPublicNovel("fanqie", SourceException.NetworkError("offline", IOException()), 1))
        assertTrue(DownloadWorker.shouldRetryPublicNovel("fanqie", SourceException.NetworkError("HTTP 503"), 2))
        assertFalse(DownloadWorker.shouldRetryPublicNovel("fanqie", SourceException.NetworkError("HTTP 403"), 0))
        assertFalse(DownloadWorker.shouldRetryPublicNovel("fanqie", IOException(), 5))
        assertFalse(DownloadWorker.shouldRetryPublicNovel("fanqie", IllegalArgumentException("缺章"), 0))
        assertFalse(DownloadWorker.shouldRetryPublicNovel("fanqie", CancellationException(), 0))
        assertFalse(DownloadWorker.shouldRetryPublicNovel("fanqie", IOException("ENOSPC"), 0))
        assertFalse(DownloadWorker.shouldRetryPublicNovel("zlibrary", IOException(), 0))
    }
}
