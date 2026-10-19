package com.example.source

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AppDatabase
import com.example.download.*
import com.example.library.ZLibraryNodeConfig
import com.example.library.ZLibraryNodeManager
import com.example.source.zlibrary.ZLibrarySource
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in live acceptance. The disposable credential file is deleted before login. */
@RunWith(AndroidJUnit4::class)
class ZLibraryDownloadDeviceTest {
    @Test fun loginSearchDownloadValidateAndImport() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("zlibLive") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val secretFile = File(app.filesDir, "zlib-live-credentials.json")
        val credential = try { if (secretFile.exists()) JSONObject(secretFile.readText()) else null } finally { secretFile.delete() }
        val report = JSONObject().put("passed", false)
        val source = ZLibrarySource(app)
        try {
            ZLibraryNodeManager.restoreSelection(app)
            report.put("phase", "login")
            if (credential != null) {
                val login = source.login(LoginCredential(credential.getString("email"), credential.getString("password")))
                assertTrue("Login failed: " + (login as? SourceResult.Error)?.exception?.message, login is SourceResult.Success && login.data)
            } else assertTrue("Provide disposable credentials for first login", source.isLoggedIn())
            report.put("phase", "search").put("node", ZLibraryNodeConfig.domain)
            val keyword = InstrumentationRegistry.getArguments().getString("zlibKeyword") ?: "无职转生未来日记"
            val search = source.search(keyword)
            val books = (search as? SourceResult.Success)?.data.orEmpty()
            report.put("searchCount", books.size)
            report.put("searchTitles", org.json.JSONArray(books.map { it.title }))
            val readable = listOf("epub", "txt", "mobi", "azw3", "azw", "fb2", "docx", "pdf")
            report.put("matchingFormats", org.json.JSONArray(books.filter { it.title.filterNot(Char::isWhitespace) == keyword.filterNot(Char::isWhitespace) }.map { it.format }))
            val book = books.filter { it.title.filterNot(Char::isWhitespace) == keyword.filterNot(Char::isWhitespace) && it.format.lowercase() in readable }
                .minByOrNull { readable.indexOf(it.format.lowercase()) }
                ?: error("未找到指定书籍：" + (search as? SourceResult.Error)?.exception?.message)
            report.put("title", book.title).put("bookId", book.id).put("format", book.format)
                .put("searchHasDownloadLink", !book.downloadUrl.isNullOrBlank()).put("keyword", keyword).put("phase", "download-link")
            val detailUrl = com.example.source.zlibrary.ZLibraryBookReference.url(book.id, ZLibraryNodeConfig.domain)
            source.httpClient.get(detailUrl).use { response ->
                val doc = org.jsoup.Jsoup.parse(response.body?.string().orEmpty())
                report.put("detailCode", response.code).put("detailTitle", doc.selectFirst("h1")?.text())
                    .put("downloadAnchors", doc.select("a[href*=/dl/]").size)
                    .put("downloadButtons", doc.select(".dlButton").size)
                    .put("apiKey", com.example.source.zlibrary.ZLibraryBookReference.apiKey(book.id)?.let { "${it.first}/${it.second}" })
                File(app.filesDir, "zlibrary-download-audit.json").writeText(report.toString(2))
            }
            val info = source.getDownloadInfo(book, book.format)
            assertTrue("Download link failed: " + (info as? SourceResult.Error)?.exception?.message, info is SourceResult.Success)
            val file = (info as SourceResult.Success).data
            report.put("downloadNode", ZLibraryNodeConfig.domain).put("downloadHost", java.net.URI(file.url).host)
            val manager = DownloadManager(app)
            val id = DownloadManager.taskId(source.id, book.id)
            manager.enqueueDownload(DownloadRequest(book.id, book.title, book.author, source.id, file.url, file.format, book.cover), file.referer, file.headers).join()
            report.put("phase", "transfer-and-import")
            val dao = AppDatabase.getDatabase(app).downloadTaskDao()
            val task = withTimeout(240_000) {
                while (true) {
                    val task = dao.getTaskById(id)
                    report.put("taskStatus", task?.status?.name).put("downloadedBytes", task?.downloadedBytes)
                    File(app.filesDir, "zlibrary-download-audit.json").writeText(report.toString(2))
                    if (task?.status == DownloadStatus.COMPLETED || task?.status == DownloadStatus.FAILED) return@withTimeout task
                    delay(500)
                }
                error("unreachable")
            }
            assertEquals(task.errorMessage, DownloadStatus.COMPLETED, task.status)
            assertTrue(DownloadFileValidator.validateFileIntegrity(File(task.filePath), task.format).valid)
            val imported = AppDatabase.getDatabase(app).bookDao().getBookBySourceResource(source.id, book.id)
            assertNotNull(imported)
            val chapters = AppDatabase.getDatabase(app).bookDao().getChaptersListForBook(imported!!.id)
            assertTrue("Imported book must have readable content", chapters.any { it.content.length > 100 && it.title != com.example.data.UNSUPPORTED_CHAPTER_TITLE })
            report.put("chapters", chapters.size).put("contentCharacters", chapters.sumOf { it.content.length })
            if (InstrumentationRegistry.getArguments().getString("zlibIllustrations") == "true") {
                val specs = chapters.flatMap { chapter -> com.example.ui.reader.NovelInlineImages.TOKEN_REGEX.findAll(chapter.content)
                    .map { it.groupValues[1] }.toList() }.distinct()
                assertTrue("The requested edition must have actual illustrations", specs.size > 5)
                for (spec in specs) {
                    assertFalse(spec.substringAfter('!').contains("../"))
                    val image = com.example.ui.reader.NovelInlineImages.NovelImageCache.decodeSampledFromEpub(spec)
                    assertNotNull("Illustration failed: " + spec.substringAfter('!'), image)
                    image!!.recycle()
                }
                report.put("decodedIllustrations", specs.size)
            }
            report.put("passed", true).put("phase", "complete").put("format", task.format).put("bytes", File(task.filePath).length())
        } catch (e: Exception) {
            report.put("failureType", e.javaClass.simpleName)
            throw e
        } finally {
            // The audit contains no account, cookies, tokens, or signed URLs.
            File(app.filesDir, "zlibrary-download-audit.json").writeText(report.toString(2))
        }

    }

    @Test fun retryRetainedEpubAndVerifyContent() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("zlibLive") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dao = AppDatabase.getDatabase(app).downloadTaskDao()
        val retained = dao.getAllTasksSync().first { it.sourceId == "zlibrary" && it.title.startsWith("3分钟未来日记") }
        val before = File(retained.filePath).readBytes().let { java.security.MessageDigest.getInstance("SHA-256").digest(it) }
        DownloadManager(app).resumeDownload(retained.id)
        val task = withTimeout(30_000) {
            while (true) {
                val t = dao.getTaskById(retained.id)!!
                if (t.status == DownloadStatus.COMPLETED || t.status == DownloadStatus.FAILED && t.errorMessage != retained.errorMessage) return@withTimeout t
                delay(200)
            }
            error("unreachable")
        }
        assertEquals(task.errorMessage, DownloadStatus.COMPLETED, task.status)
        assertArrayEquals(before, File(task.filePath).readBytes().let { java.security.MessageDigest.getInstance("SHA-256").digest(it) })
        val imported = AppDatabase.getDatabase(app).bookDao().getBookBySourceResource("zlibrary", DownloadManager.originalBookId(task.id, "zlibrary"))!!
        val chapters = AppDatabase.getDatabase(app).bookDao().getChaptersListForBook(imported.id)
        assertTrue(chapters.any { it.content.length > 100 })
        File(app.filesDir, "zlibrary-retained-import-audit.json").writeText(JSONObject().put("passed", true)
            .put("title", task.title).put("bytes", File(task.filePath).length()).put("chapters", chapters.size)
            .put("contentCharacters", chapters.sumOf { it.content.length }).put("fileUnchanged", true).toString(2))
    }

    @Test fun expiredFileLinkSwitchesMirrorAndImportsSameEdition() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("zlibFailover") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val source = ZLibrarySource(app)
        ZLibraryNodeManager.restoreSelection(app)
        assertTrue(source.isLoggedIn())
        val books = (source.search("无职转生") as SourceResult.Success).data
        val book = books.first { it.title.endsWith("第二卷") && it.format == "epub" }
        val info = (source.getDownloadInfo(book, "epub") as SourceResult.Success).data
        val failedHost = java.net.URI(info.referer!!).host
        val expiredUrl = "https://$failedHost/dl/nonexistent-recovery-probe"
        val manager = DownloadManager(app)
        val id = DownloadManager.taskId(source.id, book.id)
        val dao = AppDatabase.getDatabase(app).downloadTaskDao()
        assertNull("Use a fresh edition for the failover acceptance", dao.getTaskById(id))
        manager.enqueueDownload(DownloadRequest(book.id, book.title, book.author, source.id, expiredUrl, "epub", book.cover), info.referer, info.headers).join()
        val report = JSONObject().put("passed", false).put("title", book.title).put("failedNode", failedHost)
        try {
            val task = withTimeout(240_000) {
                while (true) {
                    val t = dao.getTaskById(id)!!
                    report.put("status", t.status.name).put("bytes", t.downloadedBytes).put("selectedNode", ZLibraryNodeConfig.domain)
                    File(app.filesDir, "zlibrary-failover-audit.json").writeText(report.toString(2))
                    if (t.status == DownloadStatus.COMPLETED || t.status == DownloadStatus.FAILED) return@withTimeout t
                    delay(500)
                }
                error("unreachable")
            }
            assertEquals(task.errorMessage, DownloadStatus.COMPLETED, task.status)
            assertNotEquals(expiredUrl, task.downloadUrl)
            assertNotEquals(failedHost, ZLibraryNodeConfig.domain)
            val imported = AppDatabase.getDatabase(app).bookDao().getBookBySourceResource(source.id, book.id)!!
            val chapters = AppDatabase.getDatabase(app).bookDao().getChaptersListForBook(imported.id)
            assertTrue(chapters.any { it.content.length > 100 })
            report.put("passed", true).put("chapters", chapters.size).put("fileHost", java.net.URI(task.downloadUrl).host)
        } finally { File(app.filesDir, "zlibrary-failover-audit.json").writeText(report.toString(2)) }
    }

    @Test fun liveExactTitleHiddenCardIsRejected() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("zlibInspect") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val source = ZLibrarySource(app)
        val query = "无职转生未来日记"
        val rows = org.json.JSONArray()
        for (host in listOf("z-library.website", "z-lib.by")) {
            source.httpClient.get("https://$host/s/" + java.net.URLEncoder.encode(query, "UTF-8")).use { response ->
                assertEquals(200, response.code)
                val html = response.body?.string().orEmpty()
                val doc = org.jsoup.Jsoup.parse(html)
                val raw = doc.select("z-bookcard").filter { it.selectFirst("[slot=title]")?.text() == query }
                val hidden = raw.count { card -> (listOf(card) + card.parents()).any { it.hasAttr("hidden") || it.attr("aria-hidden") == "true" } }
                val parsed = com.example.source.zlibrary.parser.ZLibraryParserManager.parseSearchPage(html, "https://${response.request.url.host}")
                assertTrue("Must reproduce the original hidden card", raw.isNotEmpty())
                assertEquals(raw.size, hidden)
                assertTrue("Hidden exact-title card must not be offered for download", parsed.none { it.title == query })
                rows.put(JSONObject().put("host", host).put("query", query).put("httpCode", response.code)
                    .put("rawExactCards", raw.size).put("explicitlyHiddenExactCards", hidden)
                    .put("visibleExactMatches", parsed.count { it.title == query }).put("filterPassed", true))
            }
        }
        File(app.filesDir, "zlibrary-exact-hidden-card-audit.json").writeText(rows.toString(2))
    }

    @Test fun clearLiveTestSession() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("zlibCleanup") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dao = AppDatabase.getDatabase(app).downloadTaskDao()
        val manager = DownloadManager(app)
        val ids = dao.getAllTasksSync().filter { it.sourceId == "zlibrary" && it.title == "无职转生 未来日记" && it.status != DownloadStatus.COMPLETED }.map { it.id }
        ids.forEach(manager::cancelDownload)
        withTimeout(15_000) { while (ids.any { dao.getTaskById(it) != null }) delay(100) }
        val source = ZLibrarySource(app)
        val domains = (ZLibraryNodeManager.INITIAL_SCRAPED_NODES + source.credentialStorage.getDomain()).distinct()
        source.logout()
        withContext(Dispatchers.Main) {
            val cookies = android.webkit.CookieManager.getInstance()
            domains.forEach { host -> listOf("remix_userid", "remix_userkey", "dwid", "_dwa", "c_token", "c_time", "__diamwall").forEach { key ->
                cookies.setCookie("https://$host/", "$key=; Max-Age=0; Path=/; Secure")
                cookies.setCookie("https://$host/", "$key=; Max-Age=0; Path=/; Domain=$host; Secure")
            } }
            cookies.flush()
        }
        File(app.filesDir, "zlib-live-credentials.json").delete()
        assertFalse(source.isLoggedIn())
    }
}
