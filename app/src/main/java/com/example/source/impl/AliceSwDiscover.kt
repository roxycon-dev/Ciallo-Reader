package com.example.source.impl

import com.example.discover.DiscoverCatalog
import com.example.discover.DiscoverDimension
import com.example.discover.DiscoverEntry
import com.example.discover.DiscoverFeed
import com.example.discover.DiscoverNativeCategory
import com.example.discover.DiscoverPage
import com.example.discover.DiscoverQuery
import com.example.source.SearchBook
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 爱丽丝书屋发现页（2026-11 新站契约）。
 *
 * 站点榜单页（/rankings）的 tab 是客户端渲染，服务端只渲染默认的热度序；真正可用的
 * 维度是：
 *  - 热度榜：`/search`（服务端默认序 = 热度榜，带名次，`?page=N` 翻页，HTML 解析）
 *  - 收藏榜：`/api/v1/search?sort=favorites`（JSON）
 *  - 最近更新：`/api/v1/search?sort=latest`（JSON）
 *  - 分类：`/api/v1/books?category_id={id}`（JSON，/api/v1/categories 提供目录）
 *
 * 站点没有热议与点击维度；/api/v1/books 的 sort 参数会被服务端忽略（2026-11-28 实测），
 * 所以排序类维度全部走 /api/v1/search。
 */
internal object AliceSwDiscover {

    const val FEED_HOT = "hot"
    const val FEED_FAVORITES = "favorites"
    const val FEED_LATEST = "latest"
    const val CATEGORY_PREFIX = "cat:"

    private const val PAGE_SIZE = 20

    /** 发现页路由：url + 是否 JSON 接口 + 是否带站内名次。 */
    data class Route(val url: String, val json: Boolean, val ranked: Boolean)

    /** 分类目录 + 三个栏目；categoriesJson 解析失败时只保留栏目（站点改版不致发现页全空）。 */
    fun catalog(categoriesJson: String?): DiscoverCatalog {
        val categories = mutableListOf<DiscoverNativeCategory>()
        if (!categoriesJson.isNullOrBlank()) {
            runCatching {
                val arr = JSONObject(categoriesJson).optJSONArray("data") ?: return@runCatching
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optInt("id", 0)
                    val name = item.optString("name").trim()
                    if (id > 0 && name.isNotEmpty()) categories += DiscoverNativeCategory("$CATEGORY_PREFIX$id", name, "分类")
                }
            }
        }
        return DiscoverCatalog(
            feeds = listOf(
                DiscoverFeed(FEED_HOT, "热度榜", "ranking"),
                DiscoverFeed(FEED_FAVORITES, "收藏榜", "ranking"),
                DiscoverFeed(FEED_LATEST, "最近更新", "updates")
            ),
            categories = categories,
            declared = true,
            explicit = true,
            capabilities = mapOf(
                "ranking" to listOf("热门", "收藏"),
                "updates" to listOf("最新")
            )
        )
    }

    /** 把维度/档位/分类翻译成站点地址；null 表示该维度确实没有数据。 */
    fun route(base: String, query: DiscoverQuery): Route? {
        query.cursor?.takeIf { it.isNotBlank() }?.let { url ->
            val json = url.contains("/api/v1/")
            return Route(url, json = json, ranked = !json)
        }
        val categoryId = (listOfNotNull(query.categoryId) + query.categoryIds)
            .firstOrNull { it.startsWith(CATEGORY_PREFIX) }
        if (categoryId != null) {
            val id = categoryId.removePrefix(CATEGORY_PREFIX)
            return Route(apiPageUrl("$base/api/v1/books?category_id=$id&page_size=$PAGE_SIZE", query.page), json = true, ranked = false)
        }
        val wantFavorites = query.feedId == FEED_FAVORITES ||
            (query.dimension == DiscoverDimension.RANKING && query.range == "收藏")
        val wantLatest = query.feedId == FEED_LATEST || query.dimension == DiscoverDimension.UPDATES
        return when {
            wantFavorites -> Route(apiSearchUrl(base, "favorites", query.page), json = true, ranked = false)
            wantLatest -> Route(apiSearchUrl(base, "latest", query.page), json = true, ranked = false)
            query.feedId == FEED_HOT || query.dimension == DiscoverDimension.RANKING ->
                Route(if (query.page <= 1) "$base/search" else "$base/search?page=${query.page}", json = false, ranked = true)
            else -> null
        }
    }

    /** JSON 列表条目（/api/v1/search 的 items 或 /api/v1/books 的 books 统一封装后的 data）。 */
    fun entriesFromApi(data: JSONObject, sourceId: String, base: String, ranked: Boolean, offset: Int = 0): List<DiscoverEntry> {
        val arr = data.optJSONArray("items") ?: data.optJSONArray("books") ?: return emptyList()
        val rows = mutableListOf<DiscoverEntry>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val parsed = AliceSwParser.bookFromApi(item, sourceId, base) ?: continue
            rows += DiscoverEntry(
                book = parsed.book,
                updatedAt = epochOf(item.optString("updated_at")),
                clicks = item.optLong("view_count", -1L).takeIf { it >= 0 },
                rank = (offset + rows.size + 1).takeIf { ranked },
                nativeOrder = offset + rows.size,
                native = true
            )
        }
        return rows.distinctBy { it.book.id }
    }

    /** JSON 列表的分页：page < total_pages（旧字段 max_page 兼容）则还有下一页。 */
    fun hasMoreApi(data: JSONObject): Boolean {
        val page = data.optInt("page", 1)
        val totalPages = data.optInt("total_pages", data.optInt("max_page", 1))
        return page in 1 until totalPages
    }

    fun nextApiUrl(current: String, data: JSONObject): String? {
        if (!hasMoreApi(data)) return null
        val here = current.toHttpUrlOrNull() ?: return null
        val next = here.newBuilder()
            .setQueryParameter("page", (data.optInt("page", 1) + 1).toString())
            .build()
        return next.toString()
    }

    /** HTML 列表（热度榜）条目：卡片顺序即名次。 */
    fun entriesFromHtml(html: String, sourceId: String, base: String, ranked: Boolean, offset: Int = 0): List<DiscoverEntry> {
        val books: List<SearchBook> = AliceSwParser.booksFromHtml(html, sourceId, base)
        return books.mapIndexed { index, book ->
            DiscoverEntry(
                book = book,
                rank = (offset + index + 1).takeIf { ranked },
                nativeOrder = offset + index,
                native = true
            )
        }
    }

    /** 服务端渲染页的 `?page=N` 翻页；站外地址一律丢弃。 */
    fun nextPageHtml(html: String, current: String): String? {
        val doc = Jsoup.parse(html)
        val anchor = doc.select("a[href]").firstOrNull { a ->
            a.text().trim() == "下一页" || a.attr("rel").equals("next", true)
        } ?: return null
        val here = current.toHttpUrlOrNull() ?: return null
        val next = here.resolve(anchor.attr("href").trim()) ?: return null
        if (next == here || next.host != here.host) return null
        return next.toString()
    }

    fun notice(): DiscoverPage = DiscoverPage(
        supported = false,
        notice = "爱丽丝书屋只提供榜单、更新与分类目录，没有该维度的数据"
    )

    private fun apiSearchUrl(base: String, sort: String, page: Int): String =
        apiPageUrl("$base/api/v1/search?sort=$sort&page_size=$PAGE_SIZE", page)

    private fun apiPageUrl(url: String, page: Int): String = if (page > 1) "$url&page=$page" else url

    /** updated_at 形如 2023-11-19T03:33:39+08:00，取本地时间量级即可（用于排序展示）。 */
    private fun epochOf(raw: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(raw.take(19))?.time
    }.getOrNull()
}
