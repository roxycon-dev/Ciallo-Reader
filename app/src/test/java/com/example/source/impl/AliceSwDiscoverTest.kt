package com.example.source.impl

import com.example.discover.DiscoverDimension
import com.example.discover.DiscoverQuery
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 爱丽丝书屋发现页回归：栏目/分类目录、路由、JSON 与 HTML 条目解析、翻页。 */
@RunWith(RobolectricTestRunner::class)
class AliceSwDiscoverTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/alicesw-net/$name")) { "缺少测试资源 $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val base = "https://alicesw1.homes"

    @Test
    fun `catalog declares feeds and categories`() {
        val catalog = AliceSwDiscover.catalog(resource("api_categories.json"))
        assertEquals(listOf("热度榜", "收藏榜", "最近更新"), catalog.feeds.map { it.label })
        assertTrue(catalog.categories.any { it.id == "cat:5" && it.label == "反差" })
        assertTrue(catalog.declared && catalog.explicit)
        assertEquals(listOf("热门", "收藏"), catalog.ranges(DiscoverDimension.RANKING))
    }

    @Test
    fun `catalog survives broken categories json`() {
        val catalog = AliceSwDiscover.catalog("<html>不是 JSON</html>")
        assertEquals(3, catalog.feeds.size)
        assertTrue(catalog.categories.isEmpty())
    }

    @Test
    fun `routes map feeds ranges categories and cursors`() {
        val latest = AliceSwDiscover.route(
            base, DiscoverQuery(DiscoverDimension.UPDATES, "最新", feedId = AliceSwDiscover.FEED_LATEST)
        )
        assertEquals("$base/api/v1/search?sort=latest&page_size=20", latest?.url)
        assertTrue(latest!!.json && !latest.ranked)

        val favorites = AliceSwDiscover.route(base, DiscoverQuery(DiscoverDimension.RANKING, "收藏"))
        assertEquals("$base/api/v1/search?sort=favorites&page_size=20", favorites?.url)

        val hot = AliceSwDiscover.route(base, DiscoverQuery(DiscoverDimension.RANKING, "热门"))
        assertEquals("$base/search", hot?.url)
        assertTrue(hot!!.ranked && !hot.json)

        val category = AliceSwDiscover.route(
            base, DiscoverQuery(DiscoverDimension.RANKING, "热门", categoryId = "cat:5")
        )
        assertEquals("$base/api/v1/books?category_id=5&page_size=20", category?.url)

        val paged = AliceSwDiscover.route(
            base, DiscoverQuery(DiscoverDimension.UPDATES, "最新", page = 3, feedId = AliceSwDiscover.FEED_LATEST)
        )
        assertEquals("$base/api/v1/search?sort=latest&page_size=20&page=3", paged?.url)

        val cursor = AliceSwDiscover.route(
            base, DiscoverQuery(DiscoverDimension.RANKING, "热门", cursor = "$base/search?page=2")
        )
        assertEquals("$base/search?page=2", cursor?.url)

        // 站点没有的维度（热议/点击）路由返回 null → 上层给 notice。
        assertEquals(null, AliceSwDiscover.route(base, DiscoverQuery(DiscoverDimension.DISCUSSION, "24h")))
        assertEquals(null, AliceSwDiscover.route(base, DiscoverQuery(DiscoverDimension.CLICKS, "日")))
    }

    @Test
    fun `api list entries parse and paginate`() {
        val data = checkNotNull(JSONObject(resource("api_books_cat21.json")).optJSONObject("data"))
        val entries = AliceSwDiscover.entriesFromApi(data, "alicesw", base, ranked = false)
        assertTrue("应解析出条目：${entries.size}", entries.isNotEmpty())
        assertTrue(entries.all { it.book.id.isNotBlank() })
        assertTrue(AliceSwDiscover.hasMoreApi(data))
        val next = AliceSwDiscover.nextApiUrl("$base/api/v1/books?category_id=21&page_size=20", data)
        assertNotNull(next)
        assertTrue(next!!.contains("page=2"))
    }

    @Test
    fun `hot html page keeps ranking order`() {
        val entries = AliceSwDiscover.entriesFromHtml(resource("rankings.html"), "alicesw", base, ranked = true)
        // 榜单页边流式渲染边返回：快照里只有首屏完整卡片，其余是骨架屏，按实际卡片数断言
        assertTrue("应解析出榜单：${entries.size}", entries.size >= 10)
        assertEquals(1, entries.first().rank)
        assertEquals("776834", entries.first().book.id)
        assertEquals(2, entries[1].rank)
    }
}
