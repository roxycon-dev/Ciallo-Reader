package com.example.discover

import com.example.source.impl.MangaDexSource
import com.example.source.SourceResult
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MangaDexDiscoverTest {
    private fun source(requests: MutableList<Request>): MangaDexSource {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val time = formatter.format(java.util.Date())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val item = if (request.url.encodedPath == "/chapter") JSONObject().put("id", "chapter")
                .put("attributes", JSONObject().put("publishAt", time).put("chapter", "12").put("title", "第十二话"))
                .put("relationships", JSONArray().put(JSONObject().put("type", "manga").put("id", "work")))
            else JSONObject().put("id", "work").put("attributes", JSONObject()
                .put("title", JSONObject().put("en", "Fixture Story")).put("status", "ongoing")
                .put("altTitles", JSONArray().put(JSONObject().put("zh", "测试故事"))))
                .put("relationships", JSONArray().put(JSONObject().put("type", "author").put("attributes", JSONObject().put("name", "Fixture Author"))))
            val body = JSONObject().put("data", JSONArray().put(item)).put("limit", 30).put("total", 60).toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        return MangaDexSource(client)
    }
    @Test fun updatesUseChapterPublicationTimeAndKeepLatestChapter() = runBlocking {
        val requests = mutableListOf<Request>()
        val page = (source(requests).discover(DiscoverQuery(DiscoverDimension.UPDATES, "今日")) as SourceResult.Success).data
        assertEquals(2, requests.size)
        assertEquals("desc", requests.first().url.queryParameter("order[publishAt]"))
        assertNotNull(requests.first().url.queryParameter("publishAtSince"))
        assertEquals("work", requests.last().url.queryParameter("ids[]"))
        val entry = page.entries.single()
        assertEquals("mdapi:work", entry.book.id)
        assertEquals("第十二话", entry.latestChapter)
        assertNotNull(entry.updatedAt)
        assertTrue(page.hasMore)
        assertNull(entry.clicks)
    }
    @Test fun sourceRankingKeepsNativeOrderAndPagination() = runBlocking {
        val requests = mutableListOf<Request>()
        val page = (source(requests).discover(DiscoverQuery(DiscoverDimension.RANKING, "完结", 2)) as SourceResult.Success).data
        assertEquals("desc", requests.single().url.queryParameter("order[followedCount]"))
        assertEquals("completed", requests.single().url.queryParameter("status[]"))
        assertEquals("30", requests.single().url.queryParameter("offset"))
        assertEquals(31, page.entries.single().rank)
        assertFalse(page.hasMore)
    }
    @Test fun unavailableDimensionsNeverMakeRequestsOrInventData() = runBlocking {
        val requests = mutableListOf<Request>()
        val source = source(requests)
        for (query in listOf(DiscoverQuery(DiscoverDimension.DISCUSSION, "24h"), DiscoverQuery(DiscoverDimension.CLICKS, "总"), DiscoverQuery(DiscoverDimension.RANKING, "飙升"))) {
            val page = (source.discover(query) as SourceResult.Success).data
            assertFalse(page.supported)
            assertTrue(page.entries.isEmpty())
        }
        assertTrue(requests.isEmpty())
    }
    @Test fun multipleNativeTagsUseApiOrFilterAndKeepPagination() = runBlocking {
        val requests = mutableListOf<Request>()
        val ids = listOf("391b0423-d847-456f-aff0-8b0cfc03066b", "423e2eae-a7a2-4a8b-ac03-a8351462d71d")
        val page = (source(requests).discover(DiscoverQuery(DiscoverDimension.UPDATES, "最新", page = 2,
            categoryIds = ids.map { "md:tag:$it" })) as SourceResult.Success).data
        val url = requests.single().url
        assertEquals("/manga", url.encodedPath)
        assertEquals("OR", url.queryParameter("includedTagsMode"))
        assertEquals(ids, url.queryParameterValues("includedTags[]"))
        assertEquals("30", url.queryParameter("offset"))
        assertTrue(page.native)
        assertNull(page.entries.single().rank)
    }
    @Test fun nativeCatalogParsesOfficialTagsAndReusesTheDirectory() = runBlocking {
        var requests = 0
        val id = "391b0423-d847-456f-aff0-8b0cfc03066b"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("/manga/tag", chain.request().url.encodedPath)
            val body = JSONObject().put("data", JSONArray().put(JSONObject().put("id", id).put("attributes",
                JSONObject().put("name", JSONObject().put("en", "Adventure")).put("group", "genre")))).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        val source = MangaDexSource(client)
        val first = (source.discoverCatalog(false) as SourceResult.Success).data
        val second = (source.discoverCatalog(true) as SourceResult.Success).data
        assertEquals(1, requests)
        assertEquals("md:tag:$id", first.categories.single().id)
        assertEquals(first.categories, second.categories)
        assertTrue("adventure" in first.categories.single().standardIds)
        assertFalse(first.dynamicCategories)
        assertTrue(first.supports(DiscoverDimension.UPDATES, "今日"))
        assertFalse(first.supports(DiscoverDimension.DISCUSSION, "7d"))
    }
}
