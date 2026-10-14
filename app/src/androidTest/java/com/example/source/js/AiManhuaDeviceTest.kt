package com.example.source.js

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AppDatabase
import com.example.library.ComicDownloadManager
import com.example.library.ComicDownloadStatus
import com.example.source.SourceResult
import com.example.ui.comic.ComicImagePipeline
import com.example.ui.comic.ComicPageLoader
import com.example.ui.comic.ComicPageRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AiManhuaDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun engine(): JsSourceEngine = JsSourceEngine(
        context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() },
        context.assets.open("js_extra/aimanhua.js").bufferedReader().use { it.readText() },
        "aimanhua_fixture", context
    )

    @Test fun imageListExcludesAdsAndRejectsIncompleteChapters() = runBlocking {
        val engine = engine()
        val html = """<img data-src="https://ads.invalid/ad.jpg"><ul id="comicContain"><li><img data-src="//i3.wp.com/example.com/a.jpg@1080w.avif"></li><li><img data-src="//images.example.com/b.jpg"></li></ul>"""
        val reply = JSONObject(engine.call("src.parseImages(${JSONObject.quote(html)},2)")!!)
        assertTrue(reply.toString(), reply.getBoolean("ok"))
        val images = reply.getJSONObject("data").getJSONArray("images")
        assertEquals(2, images.length())
        assertEquals("https://i3.wp.com/example.com/a.jpg@1080w.avif?w=1080", images.getString(0))
        assertEquals("https://images.example.com/b.jpg", images.getString(1))
        val incomplete = JSONObject(engine.call("src.parseImages(${JSONObject.quote(html)},3)")!!)
        assertFalse(incomplete.toString(), incomplete.getBoolean("ok"))
        assertTrue(incomplete.getString("error").contains("2/3"))
        assertFalse(JSONObject(engine.call("src.parseImages('<ul id=\"comicContain\"></ul>',2)")!!).getBoolean("ok"))
    }

    @Test fun searchUsesWorkLinksAndCatalogPreservesAllEntries() = runBlocking {
        val engine = engine()
        val html = """<a href="/99.html">推荐</a><div class="bookList_3"><div class="item"><p class="title"><a href="/42.html">作品</a></p><img class="cover" src="//i3.wp.com/example.com/cover.png"><p class="tip">作者</p></div></div>"""
        val reply = JSONObject(engine.call("src.parseSearch(${JSONObject.quote(html)})")!!)
        assertTrue(reply.toString(), reply.getBoolean("ok"))
        val comic = reply.getJSONObject("data").getJSONArray("comics").getJSONObject(0)
        assertEquals("https://www.alipaypay.com/42.html", comic.getString("id"))
        assertEquals("作品", comic.getString("title"))
        val rows = JSONArray().apply { repeat(275) { i -> put(JSONObject()
            .put("link", "/42/${i + 1}.html").put("name", "条目$i").put("pnum", 87)
            .put("price", 0).put("vip", 0).put("cion", 0)) } }
        val payload = JSONObject().put("code", 1).put("data", rows).toString()
        val result = JSONObject(engine.call("""
            (async()=>{src.page=async()=>${JSONObject.quote(payload)};const rows=await src.catalog('42');return {count:rows.length,first:rows[0].title,last:rows[274].title};})()
        """.trimIndent())!!)
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(275, result.getJSONObject("data").getInt("count"))
        assertEquals("条目274", result.getJSONObject("data").getString("last"))
    }

    /** Opt-in live audit: actual production search, reader, WorkManager, Room and offline decoder. */
    @Test fun liveChapter180DownloadsEveryPageAndReadsOffline() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("liveAiManhua") != "true") return@runBlocking
        fun <T> value(result: SourceResult<T>): T = when (result) {
            is SourceResult.Success -> result.data
            is SourceResult.Error -> throw result.exception
        }
        val report = JSONObject().put("source", "aimanhua").put("login", "not_required")
        val file = File(context.filesDir, "aimanhua-live-audit.json")
        try {
            val source = JsSourceRepo.loadCached(context, false).single { it.sourceKey == "aimanhua" }
            assertFalse(source.requiresLogin)
            val books = withTimeout(60_000) { value(source.search("这个勇者是金钱至上主义者")) }
            val book = books.single { it.title == "这个勇者是金钱至上主义者" }
            assertEquals("https://www.alipaypay.com/961857393.html", book.id)
            val chapters = withTimeout(60_000) { value(source.getChapters(book.id)) }
            report.put("searchCount", books.size).put("catalogEntries", chapters.size)
            val chapter = chapters.single { it.title == "鹰眼堡的女妖（四堡篇）" }
            val pages = withTimeout(60_000) { value(source.getChapterImages(chapter.id)) }
            assertEquals(87, pages.size)
            val headers = source.getChapterImageHeaders(chapter.id, pages)
            val loader = ComicPageLoader(context)
            try {
                for (index in listOf(0, 11, 43, 86)) {
                    val bitmap = withTimeout(60_000) { loader.load(
                        ComicPageRef.Remote("aimanhua-live:$index", pages[index], headers[pages[index]].orEmpty()),
                        "aimanhua-live:$index", ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()
                    ).bitmap }
                    assertTrue(bitmap.width > 0 && bitmap.height > 0)
                }
            } finally { loader.shutdown() }
            report.put("chapter", chapter.title).put("onlineSamples", 4).put("expectedPages", pages.size)
            val freshBook = book.copy(id = book.id + "#audit-" + java.util.UUID.randomUUID())
            val database = AppDatabase.getDatabase(context)
            val taskId = ComicDownloadManager.taskId(freshBook, chapter.id)
            ComicDownloadManager.start(context.applicationContext as Application, database, freshBook, chapter, source)
            val terminal = withTimeout(600_000) { ComicDownloadManager.tasks.first {
                it[taskId]?.status in setOf(ComicDownloadStatus.SUCCESS, ComicDownloadStatus.FAILED)
            }.getValue(taskId) }
            assertEquals(terminal.error, ComicDownloadStatus.SUCCESS, terminal.status)
            val directory = ComicDownloadManager.directory(context, taskId)
            val localBook = database.bookDao().getBookByFilePath(directory.absolutePath)
                ?: error("Downloaded chapter was not added to the bookshelf")
            val localPages = database.bookDao().getChaptersListForBook(localBook.id)
            assertEquals(pages.size, localPages.size)
            for (page in localPages) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(page.content, bounds)
                assertTrue("Invalid downloaded page: ${page.content}", bounds.outWidth > 0 && bounds.outHeight > 0)
            }
            val offline = ComicPageLoader(context)
            try {
                for (index in listOf(0, 11, 43, 86)) {
                    val bitmap = offline.load(ComicPageRef.Local("aimanhua-offline:$index", localPages[index].content),
                        "aimanhua-offline:$index", ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()).bitmap
                    assertTrue(bitmap.width > 0 && bitmap.height > 0)
                }
            } finally { offline.shutdown() }
            report.put("downloadedPages", localPages.size).put("offlineSamples", 4).put("status", "passed")
        } catch (e: Throwable) {
            report.put("status", "failed").put("error", e.message)
            throw e
        } finally { file.writeText(report.toString(2)) }
    }
}
