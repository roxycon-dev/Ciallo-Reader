package com.example.source

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.download.DownloadManager
import com.example.download.DownloadRequest
import com.example.download.DownloadStatus
import com.example.download.NovelTextArchive
import com.example.source.impl.IxdzsSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class IxdzsSourceDeviceTest {
    private fun context() = ApplicationProvider.getApplicationContext<Context>()
    private fun <T> value(result: SourceResult<T>): T = when (result) {
        is SourceResult.Success -> result.data
        is SourceResult.Error -> throw result.exception
    }

    @Test fun guestSearchAndDownloadMetadataStayInNovelGroupAndCacheDetails() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val request = chain.request()
            val body = if (request.url.encodedPath == "/bsearch") {
                assertEquals("中文网文", request.url.queryParameter("q"))
                """<div class="l-info"><h3 class="bname"><a href="/read/123/">中文网文</a></h3><span class="bauthor">作者</span><p class="l-p2">简介</p></div>"""
            } else {
                assertEquals("/read/123/", request.url.encodedPath)
                """<div class="novel"><div class="n-text"><h1>中文网文</h1><a class="bauthor">作者</a><p>最新:第2章 新章</p><p>更新:2026-10-01</p></div><div class="n-btn"><a href="https://down7.ixdzs8.com/123.zip">TXT下载</a></div></div><p id="intro">中文简介</p>"""
            }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val source = IxdzsSource(context(), "https://fixture.invalid/".toHttpUrl(), client)
        val book = value(source.search(" 中文网文 ")).single()
        assertTrue(source.isNovelSource)
        assertFalse(source.isComicSource)
        assertFalse(source.capabilities.supportOnlineText)
        assertFalse(source.requiresLogin)
        assertEquals("ixdzs8", book.sourceId)
        assertEquals("中文", book.language)
        val detail = value(source.getDetail(book.id))
        assertTrue(detail.description.orEmpty().contains("第2章 新章"))
        val download = value(source.getDownloadInfo(book.id))
        assertEquals("https://down7.ixdzs8.com/123.zip", download.url)
        assertEquals("txt", download.format)
        assertEquals("中文网文.txt", download.fileName)
        assertEquals(2, calls)
        assertTrue(source.getDownloadInfo("../escape") is SourceResult.Error)
        Unit
    }

    private fun zip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { output -> entries.forEach { (name, bytes) ->
            output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
        } }
    }

    @Test fun zippedNovelPreservesEncodingAndReordersCompleteNumberedChapters() = runBlocking {
        val file = File(context().cacheDir, "novel-archive-${System.nanoTime()}.zip")
        val charset = Charset.forName("GB18030")
        val first = "第1章 第一章\r\n中文第一章正文。\r\n"
        val second = "第2章 第二章\r\n中文第二章正文。\r\n"
        val third = "第3章 第三章\r\n中文第三章正文。\r\n"
        zip(file, mapOf("没钱修什么仙？.txt" to ("书名与作者\r\n" + first + third + second.trimEnd('\r', '\n')).toByteArray(charset)))
        var text: File? = null
        try {
            text = NovelTextArchive.prepare(file)
            assertNotNull(text)
            assertEquals("书名与作者\r\n" + first + second + third, text!!.readText(Charsets.UTF_8))
        } finally { text?.delete(); file.delete() }
    }

    @Test fun comicArchivesMultipleBooksAndEscapingPathsAreRejected() = runBlocking {
        val file = File(context().cacheDir, "novel-invalid-${System.nanoTime()}.zip")
        try {
            for (entries in listOf(
                mapOf("page.jpg" to byteArrayOf(1, 2, 3)),
                mapOf("a.txt" to "正文一".toByteArray(), "b.txt" to "正文二".toByteArray()),
                mapOf("../escape.txt" to "越界正文".toByteArray())
            )) {
                zip(file, entries)
                val attempt = runCatching { NovelTextArchive.prepare(file) }
                attempt.getOrNull()?.delete()
                assertTrue(attempt.isFailure)
            }
        } finally { file.delete() }
    }

    @Test fun liveCompleteGuestBookPreservesWholeText() = runBlocking {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("liveNovel") != "true") return@runBlocking
        withTimeout(180_000) {
            val context = context()
            val source = IxdzsSource(context)
            val book = value(source.search("朝花夕拾")).first { it.id == "1857" }
            assertEquals("晚若", book.author)
            assertFalse(source.requiresLogin)
            val detail = value(source.getDetail(book.id))
            val info = value(source.getDownloadInfo(detail.id))
            val resource = book.id + "#audit-" + System.nanoTime()
            val taskId = DownloadManager.taskId(source.id, resource)
            val manager = DownloadManager(context)
            manager.enqueueDownload(DownloadRequest(resource, book.title, book.author, source.id, info.url, info.format), info.referer, info.headers)
            val task = manager.allTasksFlow.first { rows -> rows.any { it.id == taskId && it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) } }.first { it.id == taskId }
            assertEquals(task.errorMessage, DownloadStatus.COMPLETED, task.status)
            val db = AppDatabase.getDatabase(context)
            val local = db.bookDao().getBookBySourceResource(source.id, resource) ?: error("TXT 未进入书架")
            val chapters = db.bookDao().getChaptersListForBook(local.id)
            val sourceText = File(task.filePath).readText()
            val offline = chapters.joinToString("\n") { it.content }
            assertFalse(offline.contains('\uFFFD'))
            val heading = Regex("^第[0-9一二三四五六七八九十百千万]+[章回卷节\\s].*")
            fun normalized(text: String) = text.lineSequence().filterNot { heading.matches(it.trim().removePrefix("\uFEFF")) }
                .joinToString("").filterNot(Char::isWhitespace)
            assertEquals("TXT 正文在本地阅读器入库后必须逐字保留", normalized(sourceText), normalized(offline))
            assertTrue(chapters.isNotEmpty() && offline.count { it in '\u4e00'..'\u9fff' } > 10_000)
            val report = JSONObject().put("source", source.id).put("title", book.title).put("author", book.author)
                .put("bookId", book.id).put("offlineChapters", chapters.size).put("bytes", File(task.filePath).length())
                .put("allTextPreserved", true).put("login", false).put("status", "search_download_room_offline_whole_text_passed")
            File(context.filesDir, "public-novel-ixdzs8-audit.json").writeText(report.toString())
        }
    }

    @Test fun liveSourcePlaceholderCannotEnterBookshelf() = runBlocking {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("liveNovel") != "true") return@runBlocking
        withTimeout(180_000) {
            val context = context()
            val source = IxdzsSource(context)
            val book = value(source.getDetail("110953"))
            val info = value(source.getDownloadInfo(book.id))
            val resource = book.id + "#placeholder-audit-" + System.nanoTime()
            val taskId = DownloadManager.taskId(source.id, resource)
            val manager = DownloadManager(context)
            manager.enqueueDownload(DownloadRequest(resource, book.title, book.author, source.id, info.url, info.format), info.referer, info.headers)
            val task = manager.allTasksFlow.first { rows -> rows.any { it.id == taskId && it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) } }.first { it.id == taskId }
            assertEquals(DownloadStatus.FAILED, task.status)
            assertTrue(task.errorMessage.orEmpty().contains("缺章占位"))
            assertNull(AppDatabase.getDatabase(context).bookDao().getBookBySourceResource(source.id, resource))
            File(context.filesDir, "ixdzs-placeholder-rejection.json").writeText(JSONObject()
                .put("bookId", book.id).put("status", "rejected_placeholder_not_imported").put("reason", task.errorMessage).toString())
        }
    }

    @Test fun liveOngoingChineseNovelsDownloadAndReadAsOfflineTextBooks() = runBlocking {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("liveNovel") != "true") return@runBlocking
        withTimeout(240_000) {
            val context = context()
            val source = IxdzsSource(context)
            val manager = DownloadManager(context)
            val db = AppDatabase.getDatabase(context)
            val rows = JSONArray()
            for ((query, expectedId, minimumLast) in listOf(
                // The public package advertises 1004 but that heading has no body.
                Triple("没钱修什么仙", "571203", 1003),
                Triple("异度旅社", "566155", 908),
                Triple("苟在武道世界成圣", "647863", 925)
            )) {
                val start = SystemClock.elapsedRealtime()
                val book = value(source.search(query)).first { it.id == expectedId }
                val detail = value(source.getDetail(book.id))
                val row = JSONObject().put("title", book.title).put("source", source.id)
                    .put("searchAndDetailMs", SystemClock.elapsedRealtime() - start)
                    .put("sourceLatest", detail.description.orEmpty().lineSequence().firstOrNull { it.startsWith("最新:") })
                val info = value(source.getDownloadInfo(book.id))
                val auditBookId = book.id + "#audit-" + System.nanoTime()
                val taskId = DownloadManager.taskId(source.id, auditBookId)
                val downloadStart = SystemClock.elapsedRealtime()
                manager.enqueueDownload(DownloadRequest(auditBookId, book.title, book.author, source.id,
                    info.url, info.format), info.referer, info.headers)
                val task = manager.allTasksFlow.first { tasks -> tasks.any { it.id == taskId &&
                    it.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED) } }.first { it.id == taskId }
                if (task.status == DownloadStatus.FAILED) {
                    assertTrue("Only detected source-package defects may reject this audit: ${task.errorMessage}",
                        listOf("缺章", "只有标题", "重复章节", "缺少开头", "缺章占位").any { task.errorMessage.orEmpty().contains(it) })
                    assertNull("A rejected partial book must never enter the shelf", db.bookDao().getBookBySourceResource(source.id, auditBookId))
                    row.put("status", "rejected_incomplete_source_package").put("reason", task.errorMessage)
                    rows.put(row)
                    continue
                }
                assertEquals(task.errorMessage, DownloadStatus.COMPLETED, task.status)
                assertEquals("txt", task.format)
                val file = File(task.filePath)
                assertTrue(file.length() > 1_000_000)
                assertTrue(file.name.endsWith(".txt"))
                val local = db.bookDao().getAllBooksSync().first { it.sourceId == source.id && it.comicId == auditBookId }
                assertFalse("Novel ZIP must never become a comic", local.isComic)
                val chapters = db.bookDao().getChaptersListForBook(local.id)
                assertTrue(chapters.isNotEmpty())
                assertTrue(chapters.sumOf { it.content.count { char -> char in '\u4e00'..'\u9fff' } } > 50_000)
                listOf(0, chapters.size / 2, chapters.lastIndex).forEach { assertTrue(chapters[it].content.isNotBlank()) }
                val numbers = chapters.mapNotNull { Regex("^第([0-9]+)章").find(it.title.trim())?.groupValues?.get(1)?.toInt() }
                assertTrue(numbers.maxOrNull()!! >= minimumLast)
                assertEquals(0, chapters.sumOf { it.content.count { char -> char in '\uac00'..'\ud7af' || char == '\ufffd' } })
                assertTrue("Numbered chapters should be chronological", numbers.zipWithNext().all { (a, b) -> a <= b })
                val text = file.readText(Charsets.UTF_8)
                val headingPattern = Regex("^第[0-9一二三四五六七八九十百千万]+[章回卷节\\s].*")
                fun digest(lines: Sequence<String>): String {
                    val hash = java.security.MessageDigest.getInstance("SHA-256")
                    lines.forEach { hash.update(it.filterNot(Char::isWhitespace).toByteArray(Charsets.UTF_8)) }
                    return hash.digest().joinToString("") { "%02x".format(it) }
                }
                val fileBodyHash = digest(text.lineSequence().filterNot { headingPattern.matches(it.trim().removePrefix("\uFEFF")) })
                val offlineBodyHash = digest(chapters.asSequence().map { it.content })
                assertEquals("Every packaged body paragraph must survive offline import", fileBodyHash, offlineBodyHash)
                val packageNumbers = text.lineSequence().mapNotNull { Regex("^第([0-9]+)章").find(it.trim())?.groupValues?.get(1)?.toInt() }.toSet()
                val emptyNumbers = (packageNumbers - numbers.toSet()).sorted()
                row.put("downloadAndImportMs", SystemClock.elapsedRealtime() - downloadStart)
                    .put("textBytes", file.length()).put("offlineChapters", chapters.size)
                    .put("highestNumberedChapter", numbers.maxOrNull()).put("isComic", local.isComic)
                    .put("packageHeadingsWithoutBody", JSONArray(emptyNumbers))
                    .put("allPackagedBodyPreserved", fileBodyHash == offlineBodyHash)
                    .put("freshDownload", true).put("loginRequired", false).put("status", "chinese_novel_download_and_offline_ok")
                rows.put(row)
            }
            File(context.filesDir, "ixdzs-audit.json").writeText(rows.toString())
        }
        Unit
    }
}
