package com.example.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AppDatabase
import com.example.download.DownloadManager
import com.example.download.DownloadRequest
import com.example.download.DownloadStatus
import com.example.source.impl.PublicNovelSite
import com.example.source.impl.PublicNovelSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.Ignore
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class PublicNovelSourceDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun <T> value(result: SourceResult<T>) = when (result) {
        is SourceResult.Success -> result.data
        is SourceResult.Error -> throw result.exception
    }
    private fun compact(s: String) = s.filterNot { it.isWhitespace() || it == '\u00A0' }

    @Test fun rejectsLockedBooksAndNeverSendsAccountCookies() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Cookie"))
            val json = """{"code":0,"chapters":[{"bookId":42,"chapterId":7,"title":"公开章","chapterType":"FREE","status":"PUBLISHED"},{"bookId":42,"chapterId":8,"title":"锁定章","chapterType":"VIP","status":"PUBLISHED"}]}"""
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(json.toResponseBody()).build()
        }.build()
        val source = PublicNovelSource(context, PublicNovelSite.IHUABEN, "https://fixture.invalid/".toHttpUrl(), client)
        val result = source.getChapters("42")
        assertTrue(result is SourceResult.Error)
        assertTrue((result as SourceResult.Error).exception.message.orEmpty().contains("非公开"))
        assertFalse(source.requiresLogin)
        assertTrue(source.isNovelSource)
        assertFalse(source.isComicSource)
        assertTrue(source.capabilities.supportEbook)
        assertFalse(source.capabilities.supportOnlineText)
    }

    @Test fun refusesBrokenOrIncompleteChapterAndPreservesInlineText() = runBlocking {
        var html = "<div id='content'><p>中文<span>内联</span>文字 &amp; 符号。</p><p>第二段😀。</p></div>"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(html.toResponseBody()).build()
        }.build()
        val source = PublicNovelSource(context, PublicNovelSite.QUANBEN, "https://fixture.invalid/".toHttpUrl(), client)
        assertEquals("中文内联文字 & 符号。\n\n第二段😀。", value(source.getChapterText("https://fixture.invalid/n/42/1.html")))
        for (bad in listOf("<p>中文\uFFFD正文</p>", "<p>內容加載失敗</p>", "<p>请登录后阅读</p>", "<p>本章未完</p>", "", "<p>中文\uE001</p>")) {
            html = "<div id='content'>$bad</div>"
            assertTrue("Must reject broken source body", source.getChapterText("https://fixture.invalid/n/42/1.html") is SourceResult.Error)
        }
        html = "<div id='content'><p>中文第一页。</p></div><a href='/n/42/1_2.html'>下一页</a>"
        assertTrue(source.getChapterText("https://fixture.invalid/n/42/1.html") is SourceResult.Error)
        Unit
    }

    @Ignore("整本真实下载返回 403，未发布该源；保留失败审计用例")
    @Test fun liveCzbooksFullSearchDownloadImportAndEveryParagraph() = runBlocking {
        live(PublicNovelSite.CZBOOKS, "金融狂人日記", "uipcl6")
    }
    @Test fun liveQuanbenFullSearchDownloadImportAndEveryParagraph() = runBlocking {
        live(PublicNovelSite.QUANBEN, "小王子", "xiaowangzi")
    }
    @Test fun liveIhuabenFullSearchDownloadImportAndEveryParagraph() = runBlocking {
        live(PublicNovelSite.IHUABEN, "我也不想被他钓", "15370935")
    }
    @Test fun liveSfacgFullSearchDownloadImportAndEveryParagraph() = runBlocking {
        live(PublicNovelSite.SFACG, "我的微小说集", "707553")
    }
    @Test fun liveSfacgLockedBookIsRejectedBeforePartialDownload() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("livePublicNovel") != "true") return@runBlocking
        val source = PublicNovelSource(context, PublicNovelSite.SFACG)
        val result = source.getChapters("770340")
        assertTrue(result is SourceResult.Error)
        assertTrue((result as SourceResult.Error).exception.message.orEmpty().contains("VIP"))
    }
    @Ignore("Android 端公开页面连续超时，未发布该源；保留失败审计用例")
    @Test fun liveLinovelFullSearchDownloadImportAndEveryParagraph() = runBlocking {
        live(PublicNovelSite.LINOVEL, "天平上的双月", "113618")
    }

    private suspend fun live(site: PublicNovelSite, query: String, expectedId: String) {
        if (InstrumentationRegistry.getArguments().getString("livePublicNovel") != "true") return
        withTimeout(900_000) {
            val source = PublicNovelSource(context, site)
            assertFalse(source.requiresLogin)
            val start = android.os.SystemClock.elapsedRealtime()
            val results = value(source.search(query))
            val book = results.firstOrNull { it.id == expectedId } ?: error("Real search did not return $expectedId: ${results.map { it.title }}")
            val detail = value(source.getDetail(book.id))
            assertEquals(book.title, detail.title)
            val toc = value(source.getChapters(book.id))
            assertTrue(toc.isNotEmpty())
            for (index in listOf(0, toc.size / 2, toc.lastIndex).distinct()) {
                val text = value(source.getChapterText(toc[index].id))
                assertTrue(text.isNotBlank())
                assertFalse(text.contains('\uFFFD'))
            }
            val info = value(source.getDownloadInfo(book.id))
            val resourceId = book.id + "#guest-audit-" + System.nanoTime()
            val taskId = DownloadManager.taskId(source.id, resourceId)
            val database = AppDatabase.getDatabase(context)
            assertNull(database.downloadTaskDao().getTaskById(taskId))
            val manager = DownloadManager(context)
            manager.enqueueDownload(DownloadRequest(resourceId, detail.title, detail.author, source.id, info.url, info.format))
            val terminal = manager.allTasksFlow.first { rows -> rows.any { it.id == taskId && it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) } }
                .first { it.id == taskId }
            assertEquals(terminal.errorMessage, DownloadStatus.COMPLETED, terminal.status)
            val file = File(terminal.filePath)
            val local = database.bookDao().getBookBySourceResource(source.id, resourceId) ?: error("Whole book missing from Room bookshelf")
            val chapters = database.bookDao().getChaptersListForBook(local.id)
            val offline = compact(chapters.joinToString("\n") { it.content })
            assertFalse(offline.contains('\uFFFD'))
            var paragraphCount = 0
            var imageCount = 0
            ZipFile(file).use { zip ->
                // Read every entry completely, so ZIP CRC failures cannot be overlooked.
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry -> zip.getInputStream(entry).use { it.readBytes() } }
                val evidence = JSONObject(zip.getInputStream(zip.getEntry("META-INF/ciallo-source.json")).use { it.readBytes().toString(Charsets.UTF_8) })
                assertTrue(evidence.getBoolean("complete"))
                val records = evidence.getJSONArray("chapters")
                assertEquals(toc.size, records.length())
                for (i in toc.indices) {
                    val record = records.getJSONObject(i)
                    assertEquals(toc[i].id, record.getString("url"))
                    val xhtml = zip.getInputStream(zip.getEntry("OEBPS/chapter$i.xhtml")).use { it.readBytes().toString(Charsets.UTF_8) }
                    val body = Jsoup.parse(xhtml)
                    val paragraphs = body.select("body p").map { it.wholeText().trim() }.filter { it.isNotEmpty() }
                    val raw = paragraphs.joinToString("\n\n")
                    val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
                    assertEquals("EPUB paragraph loss/reordering in $i", record.getString("textSha256"), hash)
                    for (paragraph in paragraphs) assertTrue("Offline reader lost paragraph in chapter $i", offline.contains(compact(paragraph)))
                    assertEquals(record.getInt("paragraphs"), paragraphs.size)
                    paragraphCount += paragraphs.size
                    imageCount += body.select("body img").size
                }
                val images = evidence.getJSONArray("images")
                for (i in 0 until images.length()) {
                    val asset = images.getJSONObject(i)
                    val entry = zip.getEntry("OEBPS/" + asset.getString("path")) ?: error("Missing illustration")
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    assertEquals(asset.getString("sha256"), hash)
                }
            }
            assertTrue(offline.count { it in '\u4e00'..'\u9fff' } > 100)
            val report = JSONObject().put("source", source.id).put("title", detail.title).put("bookId", book.id)
                .put("sourceChapters", toc.size).put("offlineChapters", chapters.size).put("verifiedParagraphs", paragraphCount)
                .put("illustrations", imageCount).put("bytes", file.length()).put("login", false)
                .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start).put("status", "search_download_room_offline_every_paragraph_passed")
            File(context.filesDir, "public-novel-${source.id}-audit.json").writeText(report.toString())
            android.util.Log.i("PublicNovelAudit", report.toString())
        }
    }
}
