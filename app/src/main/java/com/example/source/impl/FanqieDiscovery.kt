package com.example.source.impl

import com.example.discover.*
import com.example.source.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document

/** Public web recommendation, chapter-update and top-book APIs. No per-card detail requests. */
internal class FanqieDiscovery(
    private val home: HttpUrl,
    private val http: OkHttpClient,
    private val page: suspend (HttpUrl) -> Document,
) {
    private val mutex = Mutex()
    private val cache = linkedMapOf<String, Pair<Long, JSONObject>>()
    private var savedCatalog: Pair<Long, DiscoverCatalog>? = null
    private suspend fun json(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = home.resolve(path)!!.newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return mutex.withLock {
            cache[url.toString()]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.second ?: guestNovelIoRetry {
                val request = Request.Builder().url(url).header("Accept", "application/json")
                    .header("Referer", home.toString()).header("User-Agent", "Mozilla/5.0").build()
                http.newCall(request).executeCancellable().use { r ->
                    require(r.isSuccessful && r.request.url.host == home.host && r.request.url.scheme == home.scheme) { "番茄发现接口暂时不可用" }
                    SourceResponse.jsonObject(r.readSourceText()).also { data ->
                        require(data.optInt("code", 0) == 0) { "番茄未返回当前栏目" }
                        if (cache.size >= 12) cache.remove(cache.keys.first())
                        cache[url.toString()] = System.currentTimeMillis() to data
                    }
                }
            }
        }
    }
    suspend fun catalog(): DiscoverCatalog {
        savedCatalog?.takeIf { System.currentTimeMillis() - it.first < 300_000 }?.let { return it.second }
        val doc = page(home.resolve("/rank/1_1")!!)
        val script = doc.select("script").firstOrNull { it.data().contains("window.__INITIAL_STATE__=") }?.data()
            ?: error("番茄未返回分类目录")
        val state = JSONTokener(script.substringAfter("window.__INITIAL_STATE__=")).nextValue() as JSONObject
        val types = state.getJSONObject("rank").getJSONObject("rankCategoryTypeList")
        val categories = listOf("male" to "1", "female" to "0").flatMap { (key, gender) ->
            objects(types.optJSONArray(key)).map { o -> DiscoverNativeCategory("$gender:${o.getString("id")}", o.getString("name"), if (gender == "1") "男频" else "女频") }
        }
        return DiscoverCatalog(feeds = listOf(DiscoverFeed("native", "番茄推荐"), DiscoverFeed("updates", "最近更新", "updates"),
            DiscoverFeed("top", "番茄作品榜", "ranking")), categories = categories, declared = true, explicit = true,
            dated = true, capabilities = mapOf("updates" to listOf("最新"), "ranking" to listOf("热门")))
            .also { savedCatalog = System.currentTimeMillis() to it }
    }
    suspend fun discover(q: DiscoverQuery): DiscoverPage {
        val catalog = catalog()
        val kind = q.feedId ?: if (q.dimension == DiscoverDimension.UPDATES) "updates" else "top"
        if (q.feedId == null && !catalog.supports(q.dimension, q.range)) return DiscoverPage(supported = false, catalog = catalog)
        val selected = (listOfNotNull(q.categoryId) + q.categoryIds).distinct()
        require(selected.all { id -> catalog.categories.any { it.id == id } }) { "番茄分类已变化，请重新选择" }
        val labels = catalog.categories.filter { it.id in selected }.map { it.label }.toSet()
        val arrays: List<Pair<List<JSONObject>, String>>
        var more = false
        when (kind) {
            "native" -> {
                arrays = listOf("0", "1").map { gender ->
                    val data = json("/api/rank/recommend/list", mapOf("type" to gender, "offset" to ((q.page - 1) * 20).toString(), "limit" to "20")).getJSONObject("data")
                    val rows = objects(data.optJSONArray("list"))
                    more = more || q.page * 20 < data.optInt("total", q.page * 20)
                    rows to "番茄推荐"
                }
            }
            "updates" -> {
                val data = json("/api/rank/recent/update/list", mapOf("offset" to ((q.page - 1) * 24).toString(), "limit" to "24")).getJSONObject("data")
                val rows = objects(data.optJSONArray("data"))
                more = q.page * 24 < data.optInt("total", if (rows.size == 24) q.page * 24 + 1 else 0)
                arrays = listOf(rows to "番茄章节更新")
            }
            "top" -> {
                // This public top list supplies ordinary Unicode. The separate /rank/category/list
                // web-font payload is not ordinary text and must never be shown as unreadable PUA.
                val data = json("/api/author/misc/top_book_list/v1/", mapOf("offset" to "0", "limit" to "200"))
                val all = objects(data.optJSONArray("book_list")).filter { labels.isEmpty() || it.optString("category") in labels }
                more = q.page * 24 < all.size
                arrays = listOf(all.drop((q.page - 1) * 24).take(24) to "番茄作品榜")
            }
            else -> return DiscoverPage(supported = false, notice = "番茄未提供此栏目", catalog = catalog)
        }
        val rows = arrays.flatMap { (items, origin) -> items.mapNotNull { o -> entry(o, origin, kind == "top") } }
            .distinctBy { it.book.id }.filter { labels.isEmpty() || it.book.novelInfo?.category in labels }
            .mapIndexed { i, e -> e.copy(nativeOrder = q.offset + i, rank = if (kind == "top") q.offset + i + 1 else null) }
        return DiscoverPage(rows, hasMore = more, catalog = catalog, native = true)
    }
    private fun entry(o: JSONObject, origin: String, ranked: Boolean): DiscoverEntry? {
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { (o.opt(it) as? String)?.takeIf(String::isNotBlank) }.orEmpty()
        val id = text("bookId", "book_id")
        val title = text("bookName", "book_name")
        if (!id.matches(Regex("[0-9]{18,20}")) || title.isBlank()) return null
        require(title.none { it.code in 0xe000..0xf8ff }) { "番茄当前返回字形编码，未展示乱码作品" }
        val updated = text("updateTime", "lastChapterUpdateTime").toLongOrNull()?.takeIf { it > 0 }?.times(1000)
        val latest = text("title", "lastChapterTitle").takeIf(String::isNotBlank)
        val info = NovelInfo(category = text("category").takeIf(String::isNotBlank), synopsis = text("abstract").takeIf(String::isNotBlank),
            latestChapter = latest, status = when (text("creationStatus", "creation_status")) { "0" -> "已完结"; "1" -> "连载中"; else -> null })
        val book = SearchBook(id, "fanqie", title, text("author"), cover = text("thumbUri", "thumb_url").takeIf(String::isNotBlank),
            format = "epub", language = "中文", description = info.synopsis, novelInfo = info)
        return DiscoverEntry(book, updatedAt = updated, latestChapter = latest, native = true, origin = origin,
            statistic = if (ranked) text("rank_score").takeIf(String::isNotBlank) else null)
    }
    private fun objects(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull(a::optJSONObject)
}
