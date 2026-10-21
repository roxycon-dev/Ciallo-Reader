package com.example.source.impl

import com.example.discover.*
import com.example.source.SearchBook
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File

/** The API ignores unknown sort parameters. Sort its complete, cached metadata instead. */
internal class Wenku8Discovery(
    private val home: HttpUrl,
    private val cacheFile: File,
    private val json: suspend (HttpUrl) -> JSONObject,
    private val book: (JSONObject) -> SearchBook,
) {
    private val mutex = Mutex()
    private var memory: Pair<Long, List<JSONObject>>? = null
    private var categories: Pair<Long, DiscoverCatalog>? = null
    private val ttl = 60 * 60 * 1000L
    suspend fun catalog(): DiscoverCatalog {
        categories?.takeIf { System.currentTimeMillis() - it.first < ttl }?.let { return it.second }
        val tags = json(home.resolve("/api/categories")!!).getJSONArray("tags")
        return DiscoverCatalog(feeds = listOf(DiscoverFeed("native", "文库热度推荐"), DiscoverFeed("updates", "文库更新", "updates"),
            DiscoverFeed("heat", "文库热度分级榜", "ranking")),
            categories = (0 until tags.length()).flatMap { i -> splitTags(tags.getJSONObject(i).getString("name")) }
                .distinct().map { DiscoverNativeCategory(it, it, "文库标签") },
            declared = true, explicit = true, dated = true,
            capabilities = mapOf("updates" to listOf("最新"), "ranking" to listOf("热门", "飙升", "完结")))
            .also { categories = System.currentTimeMillis() to it }
    }
    private suspend fun index(): List<JSONObject> = mutex.withLock {
        val now = System.currentTimeMillis()
        memory?.takeIf { now - it.first < ttl }?.let { return@withLock it.second }
        if (cacheFile.isFile && now - cacheFile.lastModified() in 0 until ttl) {
            val cached = runCatching { JSONObject(cacheFile.readText()).takeIf { it.optString("owner") == home.toString() }
                ?.getJSONArray("items")?.let(::objects) }.getOrNull()
            if (!cached.isNullOrEmpty()) { memory = cacheFile.lastModified() to cached; return@withLock cached }
        }
        suspend fun list(page: Int) = json(home.resolve("/api/books")!!.newBuilder().addQueryParameter("page", page.toString()).addQueryParameter("limit", "60").build())
        val first = list(1)
        val pages = first.getInt("pages")
        require(pages in 1..120) { "文库目录规模已变化，请更新书源适配" }
        val rows = objects(first.getJSONArray("items")) + coroutineScope {
            (2..pages).toList().chunked(4).flatMap { batch ->
                batch.map { page -> async { objects(list(page).getJSONArray("items")) } }.awaitAll().flatten()
            }
        }
        val distinct = rows.distinctBy { it.getLong("id") }
        require(distinct.size == first.getInt("total")) { "文库目录在加载时发生变化，请刷新后重试" }
        // Keep metadata only. No EPUBs, covers, chapter bodies or per-book detail calls.
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            temp.writeText(JSONObject().put("owner", home.toString()).put("items", JSONArray(distinct)).toString())
            if (!temp.renameTo(cacheFile)) { temp.copyTo(cacheFile, overwrite = true); temp.delete() }
        }
        memory = now to distinct
        distinct
    }
    suspend fun discover(q: DiscoverQuery): DiscoverPage {
        val catalog = catalog()
        if (q.feedId == null && !catalog.supports(q.dimension, q.range)) return DiscoverPage(supported = false, catalog = catalog)
        val selected = (listOfNotNull(q.categoryId) + q.categoryIds).toSet()
        require(selected.all { id -> catalog.categories.any { it.id == id } }) { "文库标签已变化，请重新选择" }
        val updates = q.feedId == "updates" || (q.feedId == null && q.dimension == DiscoverDimension.UPDATES)
        val growth = q.feedId == null && q.range == "飙升"
        val filtered = index().filter { o ->
            (selected.isEmpty() || tags(o).any { it in selected }) &&
                (q.range != "完结" || q.feedId != null || o.optString("status") == "completed")
        }
        val ordered = if (updates) filtered.sortedWith(compareByDescending<JSONObject> { DiscoverEngine.parseDate(it.optString("updatedAt")) ?: Long.MIN_VALUE }.thenByDescending { it.optLong("id") })
        else if (q.feedId == "native") filtered.sortedWith(compareByDescending<JSONObject> {
            grade(it.optString("heat"), false) * 2 + grade(it.optString("heat"), true)
        }.thenByDescending { it.optString("updatedAt") }.thenByDescending { it.optLong("id") })
        else filtered.sortedWith(compareByDescending<JSONObject> { grade(it.optString("heat"), growth) }
            .thenByDescending { grade(it.optString("heat"), !growth) }.thenByDescending { it.optLong("id") })
        val origin = if (updates) "文库更新日期" else if (q.feedId == "native") "文库热度与上升等级推荐" else if (growth) "文库热度上升等级" else "文库热度等级"
        val rows = ordered.drop((q.page - 1) * 24).take(24).mapIndexed { i, o ->
            val b = book(o).let { b -> b.copy(novelInfo = b.novelInfo?.copy(tags = tags(o))) }
            DiscoverEntry(b, updatedAt = DiscoverEngine.parseDate(o.optString("updatedAt")), nativeOrder = q.offset + i,
                native = true, origin = origin, statistic = o.optString("heat").takeIf(String::isNotBlank))
        }
        return DiscoverPage(rows, hasMore = q.page * 24 < ordered.size, catalog = catalog, native = true,
            notice = if (updates) "按文库公开更新日期整理" else "按文库公开热度及上升等级整理；同等级不代表精确点击名次")
    }
    private fun objects(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    private fun tags(o: JSONObject) = o.optJSONArray("tags")?.let { a -> (0 until a.length()).flatMap { splitTags(a.optString(it)) } }.orEmpty()
    private fun splitTags(value: String) = Jsoup.parse(value).text().split(Regex("[\\s\\u00a0]+" )).filter(String::isNotBlank)
    internal fun grade(value: String, growth: Boolean): Int {
        val text = if (growth) value.substringAfter('：', "") else value.substringBefore('级')
        val level = Regex("S{1,3}|[A-E]", RegexOption.IGNORE_CASE).find(text)?.value?.uppercase() ?: return -1
        return listOf("E", "D", "C", "B", "A", "S", "SS", "SSS").indexOf(level)
    }
}
