package com.example.discover

import com.example.source.*
import com.example.source.impl.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelDiscoveryCoverageTest {
    private val home = "https://catalog.test/".toHttpUrl()
    private fun query(kind: String) = DiscoverQuery(if (kind == "ranking") DiscoverDimension.RANKING else DiscoverDimension.UPDATES,
        if (kind == "ranking") "热门" else "最新", feedId = if (kind == "recommend") "native" else null)
    private fun html(source: String, requests: MutableList<HttpUrl>, body: String): HtmlNovelDiscovery = HtmlNovelDiscovery(source, home,
        { u -> requests += u; Jsoup.parse(body, u.toString()) }, { u -> Regex("^/(?:b|book|read|n)/([a-z0-9]+)/?$").matchEntire(u.encodedPath)?.groupValues?.get(1) })

    @Test fun allHtmlSitesUseTheirOwnThreeFeedContracts() = runBlocking {
        val fixtures = mapOf(
            "sfacg" to "<li><a href='/category/21/'><img></a><br>魔幻</li><ul><a href='/b/1/'><li><img src='/1.jpg'></li><li><div id='listtitle'>一部作品</div><div>作者甲 / 魔幻</div></li></a></ul>",
            "ihuaben" to "<a href='/tongren/'>同人</a><div><h3><a href='/book/1/'>一部作品</a></h3><a href='https://user.ihuaben.com/1'>作者甲</a><a href='/category/ys/'>影视同人</a></div>",
            "ixdzs8" to "<a href='/sort/1/'>玄幻奇幻</a><li><h3><a href='/read/1/'>一部作品</a></h3><span class='nsort'>玄幻奇幻</span><span class='bauthor'>作者甲</span></li>",
            "xbookcn" to "<a href='/novels?cat=都市生活'>都市生活7.8万</a><div><div><h3><a href='/book/abc123/'>一部作品</a></h3></div><p>作者甲</p><span class='shrink-0 rounded'>都市生活</span></div>"
        )
        for ((id, fixture) in fixtures) {
            val requests = mutableListOf<HttpUrl>(); val d = html(id, requests, fixture)
            val c = d.catalog()
            assertTrue("$id updates", c.supports(DiscoverDimension.UPDATES, "最新"))
            assertTrue("$id ranking", c.supports(DiscoverDimension.RANKING, "热门"))
            assertTrue("$id categories", c.categories.isNotEmpty())
            for (kind in listOf("recommend", "updates", "ranking")) {
                val result = d.discover(query(kind))
                assertTrue("$id $kind", result.supported && result.entries.isNotEmpty())
                assertEquals("一部作品", result.entries.single().book.title)
                assertEquals("作者甲", result.entries.single().book.author)
            }
            assertTrue("$id uses distinct feed paths", requests.map { it.encodedPath + "?" + it.encodedQuery }.distinct().size >= 3)
        }
    }
    @Test fun huabenRoutesEverySelectedCategoryAndNeverConfusesCreationWithChapterUpdates() = runBlocking {
        val requests = mutableListOf<HttpUrl>()
        val d = html("ihuaben", requests, "<a href='/tongren/'>同人</a><a href='/boy/'>男频</a><h3><a href='/book/1/'>一部作品</a></h3>")
        val categories = d.catalog().categories.map { it.id }; requests.clear()
        d.discover(query("updates").copy(categoryIds = categories))
        assertEquals(setOf("/tongren/updatetime/1.html", "/boy/updatetime/1.html"), requests.map { it.encodedPath }.toSet())
        requests.clear(); d.discover(query("ranking").copy(range = "新作", categoryIds = categories))
        assertTrue(requests.all { it.encodedPath.contains("/createtime/") })
    }
    @Test fun sfImageNavigationAndNestedTitlesAreNotLost() = runBlocking {
        val requests = mutableListOf<HttpUrl>()
        val d = html("sfacg", requests, "<li><a href='/category/21/'><img></a><br>魔幻</li><ul><a href='/b/1/'><li><div>异世界 / 恋爱 /</div></li><li><div id='listtitle'>标题不是整张卡片的文字</div><div>作者 / 魔幻</div></li></a></ul>")
        assertEquals("魔幻", d.catalog().categories.single().label)
        val b = d.discover(query("updates")).entries.single().book
        assertEquals("标题不是整张卡片的文字", b.title); assertEquals("作者", b.author)
        assertEquals("魔幻", b.novelInfo?.category); assertEquals(listOf("异世界", "恋爱"), b.novelInfo?.tags)
    }
    @Test fun xbookUsesCategoryParametersNotCountsOrDescriptionsAsNames() = runBlocking {
        val requests = mutableListOf<HttpUrl>()
        val d = html("xbookcn", requests, "<a href='/novels?cat=都市生活'>都市生活7.8万</a><a href='/novels?cat=都市生活'>都市生活，现代城市、豪门……</a><a href='/novels?tag=重生'>重生123</a><h3><a href='/book/abc123/'>一部作品</a></h3>")
        val c = d.catalog(); assertEquals(listOf("都市生活", "重生"), c.categories.map { it.label })
        d.discover(query("ranking").copy(categoryIds = c.categories.map { it.id }))
        assertEquals(setOf("都市生活", "重生"), requests.filter { it.queryParameter("sort") == "heat" }.mapNotNull { it.queryParameter("cat") ?: it.queryParameter("tag") }.toSet())
    }
    @Test fun quanbenDoesNotInventUpdatesOrPopularity() = runBlocking {
        val d = html("quanben", mutableListOf(), "<a href='/c/xuanhuan.html'>玄幻</a><h3><a href='/n/one/'>一部作品</a></h3>")
        assertFalse(d.discover(query("updates")).supported); assertFalse(d.discover(query("ranking")).supported)
        assertTrue(d.discover(query("recommend")).entries.isNotEmpty())
        val c = d.catalog().categories.single()
        assertEquals("玄幻", d.discover(query("recommend").copy(categoryId = c.id)).entries.single().book.novelInfo?.category)
    }
    @Test fun compoundNovelCategoriesMapExactlyAndDoNotMatchTitleSubstrings() {
        val expected = mapOf("都市高武" to setOf("modern", "battle"), "玄幻言情" to setOf("fantasy", "love"),
            "科幻末世" to setOf("scifi", "apocalypse"), "宫廷宅斗" to setOf("palace", "ancient"),
            "影视同人" to setOf("doujin"), "恋爱：异世界" to setOf("love", "otherworld"), "星际机甲" to setOf("scifi", "mecha"))
        expected.forEach { (label, ids) -> assertTrue(label, DiscoverTaxonomy.classify(listOf(label)).containsAll(ids)) }
        assertTrue(DiscoverTaxonomy.classify(listOf("作者名字带玄幻但不是分类")).isEmpty())
        assertFalse("BL is not boys' readership", "bl" in DiscoverTaxonomy.classify(listOf("男频")))
    }
    @Test fun fanqieUsesReadablePublicTopListAndRealUpdateTimesWithoutDetailFanout() = runBlocking {
        val requests = mutableListOf<HttpUrl>(); val responses = java.util.ArrayDeque<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(responses.removeFirst().toResponseBody()).build()
        }.build()
        try {
            val d = FanqieDiscovery(home, client) { u -> Jsoup.parse("""<script>window.__INITIAL_STATE__={"rank":{"rankCategoryTypeList":{"male":[{"id":"1014","name":"都市高武"}],"female":[]}}};</script>""", u.toString()) }
            val rec = """{"data":{"list":[{"bookId":"700000000000000001","bookName":"推荐作品","author":"作者","category":"都市高武"}],"total":1}}"""
            responses.add(rec); responses.add(rec)
            assertEquals(1, d.discover(query("recommend")).entries.size)
            responses.add("""{"data":{"data":[{"bookId":"700000000000000002","bookName":"更新作品","title":"第二章","updateTime":"1792550000","category":"都市高武"}],"total":1}}""")
            val update = d.discover(query("updates")).entries.single()
            assertEquals(1792550000000L, update.updatedAt); assertEquals("第二章", update.latestChapter)
            responses.add("""{"book_list":[{"book_id":"700000000000000003","book_name":"榜单作品","category":"都市高武"}]}""")
            assertEquals("榜单作品", d.discover(query("ranking")).entries.single().book.title)
            assertEquals(4, requests.size)
            val paths = requests.map { it.encodedPath }
            assertEquals("/api/author/misc/top_book_list/v1/", paths.last())
            assertTrue(paths.none { it.startsWith("/page/") })
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun noveliaKeepsNativeProviderGenreAndNeverRepeatsUnpagedKakuyomuRanks() = runBlocking {
        val requests = mutableListOf<HttpUrl>()
        val d = AutoNovelDiscovery(home, { u -> requests += u; JSONObject("""{"items":[{"providerId":"syosetu","novelId":"n123","titleZh":"作品"}],"pageNumber":3}""") },
            { id, o -> SearchBook(id, "auto_novel", o.getString("titleZh"), "", novelInfo = NovelInfo()) })
        // The guest list endpoint now answers 401; no request may be sent for it.
        val recommend = d.discover(query("recommend"))
        assertFalse(recommend.supported); assertTrue(recommend.notice!!.contains("401"))
        assertFalse(d.discover(query("updates")).supported)
        assertTrue(requests.isEmpty())
        val category = d.catalog.categories.first().id
        d.discover(query("ranking").copy(categoryId = category))
        assertEquals("流派", requests.last().queryParameter("type")); assertEquals("恋爱：异世界", requests.last().queryParameter("genre"))
        requests.clear(); d.discover(query("ranking").copy(page = 2))
        assertEquals(listOf("/api/novel/rank/syosetu"), requests.map { it.encodedPath })
        requests.clear(); d.discover(query("ranking").copy(range = "完结"))
        assertEquals("完结", requests.single().queryParameter("status"))
    }
    @Test fun wenkuSortsTheCompleteIndexAndFiltersActualTagsInsteadOfIgnoredApiParameters() = runBlocking {
        val file = Files.createTempDirectory("wenku-discover").resolve("metadata.json").toFile()
        try {
            val requests = mutableListOf<HttpUrl>()
            val d = Wenku8Discovery(home, file, { u ->
                requests += u
                if (u.encodedPath.endsWith("categories")) JSONObject("""{"tags":[{"name":"恋爱"},{"name":"悬疑&nbsp;&nbsp;女性视角"}]}""")
                else if (u.queryParameter("page") == "1") JSONObject("""{"pages":2,"total":3,"items":[{"id":1,"heat":"E级，当前热度上升指数为：S级","updatedAt":"2026-10-21","tags":["恋爱"]},{"id":2,"heat":"A级，当前热度上升指数为：C级","updatedAt":"2026-10-19","tags":["悬疑"]}]}""")
                else JSONObject("""{"items":[{"id":3,"heat":"S级，当前热度上升指数为：A级","updatedAt":"2026-10-20","tags":["恋爱"],"status":"completed"}]}""")
            }, { o -> SearchBook(o.getLong("id").toString(), "wenku8_library", "作品", "", novelInfo = NovelInfo()) })
            assertEquals(listOf("恋爱", "悬疑", "女性视角"), d.catalog().categories.map { it.label })
            assertEquals(listOf("3", "2", "1"), d.discover(query("ranking")).entries.map { it.book.id })
            assertEquals(listOf("1", "3", "2"), d.discover(query("updates")).entries.map { it.book.id })
            assertEquals("1", d.discover(query("ranking").copy(range = "飙升")).entries.first().book.id)
            assertEquals(listOf("3", "1"), d.discover(query("recommend").copy(categoryIds = listOf("恋爱"))).entries.map { it.book.id })
            assertEquals(2, requests.count { it.encodedPath == "/api/books" })
            assertTrue(file.isFile); assertTrue(requests.none { it.queryParameter("sort") != null })
        } finally { file.delete(); file.parentFile.delete() }
    }
}
