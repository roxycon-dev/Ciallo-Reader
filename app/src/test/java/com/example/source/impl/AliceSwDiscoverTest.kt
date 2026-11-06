package com.example.source.impl

import com.example.discover.DiscoverDimension
import com.example.discover.DiscoverQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 爱丽丝书屋发现页回归（2026-11-06 真实页面快照）。
 *
 * 排行页 `/other/rank_hits/order/hits*.html`、分类页 `/lists/{id}.html` 与
 * `/all/order/update_time+desc.html` 共用 `div.rec_rullist > ul` 卡片：
 * `li.one` 名次、`li.sev` 分类、`li.two` 书名、`li.three` 最新章节、
 * `li.four` 作者、`li.five` 字数、`li.six` 日期、`li.diyhot` 热度。
 */
class AliceSwDiscoverTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/alicesw/$name")) { "缺少测试资源 $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val base = "https://alicesw4.homes"

    @Test
    fun `catalog exposes four rank windows, updates and native categories`() {
        val catalog = AliceSwDiscover.catalog(resource("home.html"), base)
        assertTrue(catalog.declared)
        assertEquals(
            listOf("最近更新", "日榜", "周榜", "月榜", "总榜"),
            catalog.feeds.map { it.label }
        )
        assertEquals(listOf("updates", "ranking", "ranking", "ranking", "ranking"), catalog.feeds.map { it.kind })
        assertEquals(listOf("日", "周", "月", "总"), catalog.capabilities["ranking"])
        assertEquals(listOf("最新"), catalog.capabilities["updates"])

        val category = catalog.categories.first { it.label == "乱伦" }
        assertEquals("$base/lists/65.html", category.id)
        assertEquals("分类", category.group)
        // 首页还挂着已失效的多位数字 /lists/ 链接，不能当成真实分类。
        assertTrue(catalog.categories.all { Regex("/lists/\\d{1,3}\\.html$").containsMatchIn(it.id) })
        assertEquals(catalog.categories.size, catalog.categories.distinctBy { it.id }.size)
    }

    @Test
    fun `category page yields ordered entries with rank author and heat`() {
        val entries = AliceSwDiscover.entries(resource("lists.html"), AliceSwSource.SOURCE_ID, ranked = true)
        assertEquals(50, entries.size)

        val first = entries.first()
        assertEquals("54008", first.book.id)
        assertEquals(AliceSwSource.SOURCE_ID, first.book.sourceId)
        assertEquals(1, first.rank)
        assertEquals("乱伦", first.book.novelInfo?.category)
        assertEquals("Txd147258", first.book.author)
        assertEquals("15.74万", first.book.novelInfo?.wordCount)
        assertEquals(5648L, first.clicks)
        assertEquals("2026-11-05", first.book.novelInfo?.updatedAt)
        assertTrue("日期应换算成时间戳", (first.updatedAt ?: 0L) > 1_600_000_000_000L)
        assertEquals(0, first.nativeOrder)
        assertTrue(first.native)
        assertTrue(first.book.title.isNotBlank())
    }

    @Test
    fun `rank page keeps site order and never fabricates a missing rank`() {
        val entries = AliceSwDiscover.entries(resource("rank.html"), AliceSwSource.SOURCE_ID, ranked = true)
        assertEquals(50, entries.size)
        assertEquals("32255", entries.first().book.id)
        assertEquals(1, entries.first().rank)
        assertEquals(5655841L, entries.first().clicks)
        assertEquals("系统", entries.first().book.novelInfo?.category)
        // 名次连续，不按热度重排
        assertEquals((1..50).toList(), entries.mapNotNull { it.rank })
    }

    @Test
    fun `updates page entries carry date but no rank when not ranked`() {
        val entries = AliceSwDiscover.entries(resource("updates.html"), AliceSwSource.SOURCE_ID, ranked = false)
        assertEquals(50, entries.size)
        assertEquals("49668", entries.first().book.id)
        assertEquals("玄幻", entries.first().book.novelInfo?.category)
        assertNull(entries.first().rank)
        assertTrue((entries.first().updatedAt ?: 0L) > 1_600_000_000_000L)
    }

    @Test
    fun `pagination follows the site next link and stops when absent`() {
        val next = AliceSwDiscover.nextPage(resource("lists.html"), "$base/lists/65.html")
        assertEquals("$base/lists/65.html?page=2", next)
        assertEquals(
            "$base/all/order/update_time+desc.html?page=2",
            AliceSwDiscover.nextPage(resource("updates.html"), "$base/all/order/update_time+desc.html")
        )
        assertNull("排行页固定 50 条，没有下一页", AliceSwDiscover.nextPage(resource("rank.html"), "$base/other/rank_hits/order/hits.html"))
    }

    @Test
    fun `routes map dimensions and keep stale cursors on the current host`() {
        val catalog = AliceSwDiscover.catalog(resource("home.html"), base)
        fun query(dimension: DiscoverDimension = DiscoverDimension.RANKING, range: String = "热门", page: Int = 1,
                  cursor: String? = null, categoryId: String? = null) =
            DiscoverQuery(dimension = dimension, range = range, page = page, cursor = cursor, categoryId = categoryId)

        assertEquals("$base/other/rank_hits/order/hits_week.html", AliceSwDiscover.route(base, catalog, query(range = "周")))
        assertEquals("$base/other/rank_hits/order/hits.html", AliceSwDiscover.route(base, catalog, query(range = "总")))
        assertEquals(
            "$base/all/order/update_time+desc.html?page=2",
            AliceSwDiscover.route(base, catalog, query(DiscoverDimension.UPDATES, "最新", page = 2))
        )
        assertEquals(
            "$base/lists/65.html",
            AliceSwDiscover.route(base, catalog, query(categoryId = "$base/lists/65.html"))
        )
        assertEquals("$base/lists/65.html?page=3", AliceSwDiscover.route(base, catalog, query(DiscoverDimension.UPDATES, "最新", page = 3, categoryId = "$base/lists/65.html")))
        assertEquals("https://alicesw3.homes/lists/65.html?page=2",
            AliceSwDiscover.route(base, catalog, query(cursor = "https://alicesw3.homes/lists/65.html?page=2")))
        assertNull("站点没有热议数据", AliceSwDiscover.route(base, catalog, query(DiscoverDimension.DISCUSSION, "24h")))
        assertTrue(!AliceSwDiscover.notice().supported)
    }

    @Test
    fun `ranked detection covers feeds and dimension`() {
        val catalog = AliceSwDiscover.catalog(resource("home.html"), base)
        val rankFeed = catalog.feeds.first { it.kind == "ranking" }.id
        assertTrue(AliceSwDiscover.isRanked(DiscoverQuery(DiscoverDimension.UPDATES, "最新", feedId = rankFeed)))
        assertTrue(AliceSwDiscover.isRanked(DiscoverQuery(DiscoverDimension.RANKING, "日")))
        assertTrue(!AliceSwDiscover.isRanked(DiscoverQuery(DiscoverDimension.UPDATES, "最新")))
    }
}
