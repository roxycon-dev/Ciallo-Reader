package com.example.source.js

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AppDatabase
import com.example.discover.DiscoverDimension
import com.example.discover.DiscoverQuery
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
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NineteenDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun nativeCategoriesUseSiteLabelsIdsAndStableRoutes() = runBlocking {
        val bridge = context.assets.open("venera/discover_bridge.js").bufferedReader().use { it.readText() }
        for (key in listOf("nineteen", "xipi")) {
            val engine = JsSourceEngine(
                context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() },
                context.assets.open("js_extra/$key.js").bufferedReader().use { it.readText() }, "${key}_discovery_fixture", context)
            val markup = if (key == "nineteen") """
                <div class="cate-col"><p class="cate-title">标签：</p><a href="/category/">全部</a>
                <a href="/category/tags/901">新题材</a><a href="/category/tags/901">重复</a>
                <a href="https://ads.invalid/category/tags/902">广告</a></div>
                <div class="cate-col"><p class="cate-title">地区：</p><a href="/category/city/903">新区</a></div>
            """ else """<a href="/genre/new-genre">新题材</a><a href="/genre/new-genre">重复</a>
                <a href="/category/new-region">新区</a><a href="https://ads.invalid/genre/ad">广告</a>"""
            val setup = if (key == "nineteen") "src.page=async()=>${JSONObject.quote(markup)};" else
                "src.browseHtml=async()=>new HtmlDocument(${JSONObject.quote(markup)});"
            val result = JSONObject(engine.call("(() => {$bridge; $setup return __cialloDiscover(src,{catalogOnly:true,resolveDynamic:true});})()")!!)
            assertTrue(result.toString(), result.getBoolean("ok"))
            val categories = result.getJSONObject("data").getJSONArray("categories")
            assertEquals(2, categories.length())
            val genre = (0 until categories.length()).map { categories.getJSONObject(it) }.single { it.getString("label") == "新题材" }
            assertEquals(if (key == "nineteen") "/category/tags/901" else "/genre/new-genre", genre.getString("param"))
            val id = genre.getString("id")
            val reordered = markup.replace("新题材", "新题材改名")
            val reset = if (key == "nineteen") "src.page=async()=>${JSONObject.quote(reordered)};" else
                "src.browseHtml=async()=>new HtmlDocument(${JSONObject.quote(reordered)});"
            val next = JSONObject(engine.call("(() => {$bridge; $reset src.browseCategories=null; src.__cialloDynamicCategories=null; return __cialloDiscover(src,{catalogOnly:true,resolveDynamic:true});})()")!!)
            assertTrue(next.toString(), next.getBoolean("ok"))
            // Identity includes source label and route; neither is replaced with a synthetic category number.
            assertTrue(id.startsWith("dynamic:"))
            assertTrue(next.getJSONObject("data").getJSONArray("categories").toString().contains("新题材改名"))
            if (key == "xipi") {
                val filters = JSONObject(engine.call("(() => {src.browseHtml=async()=>new HtmlDocument('<div data-category-id=\"\" data-genre-id=\"901\"></div>'); return src.nativeFilter('/genre/new-genre');})()")!!)
                assertEquals("901", filters.getJSONObject("data").getString("genre"))
                val invalid = JSONObject(engine.call("src.nativeFilter('https://ads.invalid/genre/ad')")!!)
                assertFalse(invalid.getBoolean("ok"))
            }
        }
    }

    @Test fun anonymousSourcesDiscoverCategoriesStatusAndSecondPages() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("liveLatestComics") != "true") return@runBlocking
        fun <T> value(result: SourceResult<T>): T = when (result) {
            is SourceResult.Success -> result.data
            is SourceResult.Error -> throw result.exception
        }
        val report = JSONArray()
        for (key in listOf("nineteen", "xipi")) {
            val source = JsSourceRepo.loadCached(context, false).single { it.sourceKey == key }
            val catalog = withTimeout(90_000) { value(source.discoverCatalog(true)) }
            assertTrue(catalog.dynamicCategories)
            assertTrue(catalog.categories.size >= if (key == "nineteen") 100 else 9)
            assertEquals(setOf("连载", "完结"), catalog.statuses.toSet())
            val category = catalog.categories.first { it.label in setOf("冒险", "冒險") }
            assertTrue(category.standardIds.contains("adventure"))
            val query = DiscoverQuery(DiscoverDimension.UPDATES, "最新", categoryId = category.id, status = "完结")
            val first = withTimeout(90_000) { value(source.discover(query)) }
            assertTrue(first.supported)
            assertTrue(first.entries.isNotEmpty())
            assertTrue(first.entries.all { it.book.comicInfo?.status == "完结" })
            // A valid completed category can contain one page. Verify pagination on its full catalogue.
            val pagingQuery = query.copy(status=null)
            val pageOne = withTimeout(90_000) { value(source.discover(pagingQuery)) }
            assertTrue(pageOne.entries.isNotEmpty())
            assertTrue(pageOne.hasMore)
            val second = withTimeout(90_000) { value(source.discover(pagingQuery.copy(page=2, offset=pageOne.entries.size))) }
            assertTrue(second.entries.isNotEmpty())
            assertNotEquals(pageOne.entries.map { it.book.id }, second.entries.map { it.book.id })
            for (range in catalog.ranges(DiscoverDimension.RANKING)) {
                val page = withTimeout(90_000) { value(source.discover(DiscoverQuery(DiscoverDimension.RANKING, range))) }
                assertTrue("$key $range", page.supported && page.entries.isNotEmpty())
            }
            report.put(JSONObject().put("source", key).put("categories", catalog.categories.size)
                .put("groups", JSONArray(catalog.categories.map { it.group }.distinct()))
                .put("category", category.label).put("status", "完结").put("completedResults", first.entries.size)
                .put("completedHasMore", first.hasMore).put("firstPage", pageOne.entries.size)
                .put("secondPage", second.entries.size).put("rankingRanges", JSONArray(catalog.ranges(DiscoverDimension.RANKING))))
            File(context.filesDir, "latest-comic-discovery-audit.json").writeText(report.toString(2))
        }
    }

    @Test fun readerRejectsMissingLockedAndWrongChapterPages() = runBlocking {
        val engine = JsSourceEngine(
            context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() },
            context.assets.open("js_extra/nineteen.js").bufferedReader().use { it.readText() },
            "nineteen_fixture", context)
        val html = """<img data-original="https://ads.invalid/ad.jpg">
            <div class="page-index__btn"><span class="count">2</span></div>
            <div class="rd-article-wr">
            <div class="rd-article__pic" data-index="1"><img data-original="https://images.invalid/1.webp"></div>
            <div class="rd-article__pic" data-index="2"><img data-original="https://images.invalid/2.webp"></div></div>
            <script>readPic(42,99,0,0);</script>""".trimIndent()
        suspend fun parse(markup: String, book: String = "42") = JSONObject(engine.call(
            "src.parseImages(${JSONObject.quote(markup)},${JSONObject.quote(book)},'/chapter/99')")!!)
        val valid = parse(html)
        assertTrue(valid.toString(), valid.getBoolean("ok"))
        assertEquals(2, valid.getJSONObject("data").getJSONArray("images").length())
        for (markup in listOf(
            html.replace("class=\"count\">2", "class=\"count\">3"),
            html.replace("data-index=\"2\"", "data-index=\"3\""),
            html.replace("images.invalid/2.webp", "images.invalid/1.webp"),
            html.replace("readPic(42,99,0,0)", "readPic(42,99,1,0)"),
            html.replace("readPic(42,99,0,0)", "readPic(42,98,0,0)")
        )) assertFalse(parse(markup).toString(), parse(markup).getBoolean("ok"))
        assertFalse(parse(html, "43").getBoolean("ok"))
    }

    /** Full production reader/download/Room/offline audit; explicitly enabled for real network runs. */
    @Test fun latestChaptersSearchReadDownloadAndDecodeOffline() = runBlocking {
        auditLatestChapters("nineteen", listOf(
            Triple("间谍过家家", "70922", Pair("第141话", 23)),
            Triple("葬送的芙莉莲", "68250", Pair("第147话", 16)),
            Triple("海贼王", "71176", Pair("第1195话", 15))
        ))
    }

    @Test fun hero180And180HalfSearchReadDownloadAndDecodeOffline() = runBlocking {
        auditLatestChapters("xipi", listOf(
            Triple("这个勇者是个金钱至上主义者", "bTozMDg0MA", Pair("鹰身女妖&天使", 2)),
            Triple("这个勇者是金钱至上主义者", "bTozMDg0MA", Pair("鹰眼堡的女妖（四堡篇）", 87))
        ))
    }

    @Test fun xipiImageCodecMatchesPublicReaderAndRejectsMalformedData() = runBlocking {
        val engine = JsSourceEngine(
            context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() },
            context.assets.open("js_extra/xipi.js").bufferedReader().use { it.readText() },
            "xipi_fixture", context)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val payload = assets.open("xipi/chapter-180-5.json").bufferedReader().use { it.readText() }
        val expected = JSONArray(assets.open("xipi/expected-urls.json").bufferedReader().use { it.readText() })
        val result = JSONObject(engine.call("src.decodeImages($payload)")!!)
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(expected.toString(), result.getJSONArray("data").toString())
        for (override in listOf("{images:'invalid'}", "{sid:1e22}", "{order_id:0.5}")) {
            val invalid = JSONObject(engine.call("src.decodeImages(Object.assign({},$payload,$override))")!!)
            assertFalse(invalid.toString(), invalid.getBoolean("ok"))
        }
        val wrongBook = JSONObject(engine.call("src.comic.loadEp('bTo0Mg','bTozMDg0MC1jOjE5MjkzOA-MzA4NDA6MjczLjAw')")!!)
        assertFalse(wrongBook.toString(), wrongBook.getBoolean("ok"))
    }

    private suspend fun auditLatestChapters(sourceKey: String, targets: List<Triple<String, String, Pair<String, Int>>>) {
        if (InstrumentationRegistry.getArguments().getString("liveLatestComics") != "true") return
        fun <T> value(result: SourceResult<T>): T = when (result) {
            is SourceResult.Success -> result.data
            is SourceResult.Error -> throw result.exception
        }
        val rows = JSONArray()
        val report = JSONObject().put("source", sourceKey).put("login", "not_required").put("chapters", rows)
        val file = File(context.filesDir, "$sourceKey-live-audit.json")
        fun save() = file.writeText(report.toString(2))
        val source = JsSourceRepo.loadCached(context, false).single { it.sourceKey == sourceKey }
        assertFalse(source.requiresLogin)
        try {
            for ((keyword, id, expected) in targets) {
                val row = JSONObject().put("keyword", keyword).put("stage", "search")
                rows.put(row); save()
                val books = withTimeout(60_000) { value(source.search(keyword)) }
                val expectedBookId = if (sourceKey == "nineteen") "https://m.sxchex.com/comic/$id" else id
                val book = books.single { it.id == expectedBookId }
                row.put("title", book.title).put("bookId", book.id).put("stage", "catalogue"); save()
                val details = withTimeout(60_000) { value(source.getDiscoverMetadata(book.id)) }
                assertTrue(details.title.isNotBlank())
                val chapters = withTimeout(60_000) { value(source.getChapters(book.id)) }
                val chapter = chapters.single { it.title == expected.first }
                row.put("catalogueCount", chapters.size).put("chapter", chapter.title).put("chapterId", chapter.id)
                    .put("stage", "images"); save()
                val pages = withTimeout(60_000) { value(source.getChapterImages(chapter.id)) }
                assertEquals(expected.second, pages.size)
                assertEquals(pages.size, pages.toSet().size)
                val headers = source.getChapterImageHeaders(chapter.id, pages)
                val samples = listOf(0, pages.size / 2, pages.lastIndex).distinct()
                val loader = ComicPageLoader(context)
                try {
                    for (index in samples) {
                        val bitmap = withTimeout(60_000) { loader.load(
                            ComicPageRef.Remote("$sourceKey-live-$id:${expected.first}:$index", pages[index], headers[pages[index]].orEmpty()),
                            "$sourceKey-live-$id:${expected.first}:$index", ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()).bitmap }
                        assertTrue(bitmap.width > 0 && bitmap.height > 0)
                    }
                } finally { loader.shutdown() }
                row.put("onlineSamples", samples.size).put("pages", pages.size).put("stage", "download"); save()
                val freshBook = book.copy(id = book.id + "#audit-" + UUID.randomUUID())
                val database = AppDatabase.getDatabase(context)
                val taskId = ComicDownloadManager.taskId(freshBook, chapter.id)
                ComicDownloadManager.start(context.applicationContext as Application, database, freshBook, chapter, source)
                val terminal = withTimeout(600_000) { ComicDownloadManager.tasks.first {
                    it[taskId]?.status in setOf(ComicDownloadStatus.SUCCESS, ComicDownloadStatus.FAILED)
                }.getValue(taskId) }
                assertEquals(terminal.error, ComicDownloadStatus.SUCCESS, terminal.status)
                val directory = ComicDownloadManager.directory(context, taskId)
                val localBook = database.bookDao().getBookByFilePath(directory.absolutePath)
                    ?: error("Downloaded chapter missing from bookshelf")
                val localPages = database.bookDao().getChaptersListForBook(localBook.id)
                assertEquals(pages.size, localPages.size)
                for (page in localPages) {
                    val bitmap = BitmapFactory.decodeFile(page.content, BitmapFactory.Options().apply { inSampleSize = 4 })
                    assertNotNull("Downloaded image cannot decode: ${page.content}", bitmap)
                    assertTrue(bitmap!!.width > 0 && bitmap.height > 0)
                    bitmap.recycle()
                }
                val offline = ComicPageLoader(context)
                try {
                    for (index in samples) {
                        val bitmap = offline.load(ComicPageRef.Local("$sourceKey-offline-$id:${expected.first}:$index", localPages[index].content),
                            "$sourceKey-offline-$id:${expected.first}:$index", ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()).bitmap
                        assertTrue(bitmap.width > 0 && bitmap.height > 0)
                    }
                } finally { offline.shutdown() }
                row.put("downloadedPages", localPages.size).put("offlineSamples", samples.size).put("stage", "passed"); save()
            }
            report.put("status", "passed")
        } catch (e: Throwable) {
            report.put("status", "failed").put("error", e.message)
            throw e
        } finally { save() }
    }
}
