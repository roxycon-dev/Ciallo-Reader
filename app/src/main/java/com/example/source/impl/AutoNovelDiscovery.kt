package com.example.source.impl

import com.example.discover.*
import com.example.source.SearchBook
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import org.json.JSONObject

/** Values and rank parameters match Novelia's published WebNovelRank selector. */
internal class AutoNovelDiscovery(
    private val home: HttpUrl,
    private val json: suspend (HttpUrl) -> JSONObject,
    private val book: (String, JSONObject) -> SearchBook,
) {
    private val syosetu = listOf("恋爱：异世界", "恋爱：现实世界", "幻想：高幻想", "幻想：低幻想", "文学：纯文学", "文学：人性剧",
        "文学：历史", "文学：推理", "文学：恐怖", "文学：动作", "文学：喜剧", "科幻：VR游戏", "科幻：宇宙", "科幻：空想科学",
        "科幻：惊悚", "其他：童话", "其他：诗", "其他：散文", "其他：其他")
    private val kakuyomu = listOf("异世界幻想", "现代幻想", "科幻", "恋爱", "浪漫喜剧", "现代戏剧", "恐怖", "推理", "散文·纪实", "历史·时代·传奇", "创作论·评论", "诗·童话·其他")
    /**
     * `/api/novel` answers 401 "游客没有权限执行此操作" to unauthenticated callers, so there is no
     * guest recommendation or update list any more. Only the public daily ranks stay readable;
     * claiming a 推荐/更新 feed here would only surface a guaranteed 401 on every open.
     */
    val guestListNotice = "Novelia 已关闭游客列表接口（HTTP 401），推荐与更新需登录；公开日榜仍可查看"
    val catalog = DiscoverCatalog(feeds = listOf(
        DiscoverFeed("syosetu", "成为小说家·综合日榜", "ranking"),
        DiscoverFeed("kakuyomu", "Kakuyomu·综合日榜", "ranking")),
        categories = syosetu.map { DiscoverNativeCategory("syosetu|$it", it, "成为小说家·流派") } +
            kakuyomu.map { DiscoverNativeCategory("kakuyomu|$it", it, "Kakuyomu·流派") },
        declared = true, explicit = true, dated = true,
        capabilities = mapOf("ranking" to listOf("热门", "完结")))
    suspend fun discover(q: DiscoverQuery): DiscoverPage = coroutineScope {
        val ranking = q.categoryId != null || q.feedId in setOf("syosetu", "kakuyomu") ||
            (q.feedId == null && q.dimension == DiscoverDimension.RANKING)
        if (!ranking) return@coroutineScope DiscoverPage(supported = false, notice = guestListNotice, catalog = catalog)
        if (q.feedId == null && !catalog.supports(q.dimension, q.range)) return@coroutineScope DiscoverPage(supported = false, catalog = catalog)
        val categories = (listOfNotNull(q.categoryId) + q.categoryIds).distinct()
        require(categories.all { id -> catalog.categories.any { it.id == id } }) { "轻小说分类已变化，请重新选择" }
        val routes = categories.ifEmpty {
            if (q.feedId in setOf("syosetu", "kakuyomu")) listOf(q.feedId!!) else listOf("syosetu", "kakuyomu")
        }
        val effectiveRoutes = routes.filterNot { (q.page > 1 || (q.range == "完结" && q.feedId == null)) && it.startsWith("kakuyomu") }
        val results = effectiveRoutes.chunked(3).flatMap { batch -> batch.map { route -> async {
            val url = home.resolve("/api/novel/rank/" + route.substringBefore('|'))!!.newBuilder().apply {
                val provider = route.substringBefore('|')
                val genre = route.substringAfter('|', "")
                if (provider == "syosetu") {
                    addQueryParameter("type", if (genre.isEmpty()) "综合" else "流派")
                    addQueryParameter("page", q.page.toString())
                }
                if (genre.isNotEmpty()) addQueryParameter("genre", genre)
                else if (provider == "kakuyomu") addQueryParameter("genre", "综合")
                addQueryParameter("range", "每日").addQueryParameter("status", if (q.range == "完结" && provider == "syosetu") "完结" else "全部")
            }.build()
            val data = json(url)
            val items = data.getJSONArray("items")
            val entries = (0 until items.length()).mapNotNull { i ->
                val o = items.optJSONObject(i) ?: return@mapNotNull null
                val provider = o.optString("providerId")
                val id = o.optString("novelId")
                if (provider !in setOf("syosetu", "kakuyomu", "novelup", "hameln", "alphapolis") || !id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) return@mapNotNull null
                val b = book("$provider/$id", o)
                if (b.title.isBlank()) return@mapNotNull null
                val genre = route.substringAfter('|', "").takeIf(String::isNotBlank)
                val completed = q.range == "完结" && provider == "syosetu"
                val enriched = b.copy(novelInfo = b.novelInfo?.copy(tags = (b.novelInfo.tags + listOfNotNull(genre)).distinct(),
                    status = if (completed) "已完结" else b.novelInfo.status))
                DiscoverEntry(enriched, updatedAt = o.optLong("updateAt").takeIf { it > 0 }?.times(1000), native = true,
                    origin = "${if (provider == "syosetu") "成为小说家" else "Kakuyomu"}·${genre ?: "综合"}日榜",
                    statistic = o.optString("extra").takeIf(String::isNotBlank))
            }
            // Kakuyomu's rank API has no page parameter. Never refetch its first page as a next page.
            entries to (if (route.startsWith("kakuyomu")) false else q.page < data.optInt("pageNumber", if (items.length() == 24) q.page + 1 else q.page))
        } }.awaitAll() }
        val entries = results.flatMap { it.first }.distinctBy { it.book.id }.mapIndexed { i, e ->
            e.copy(nativeOrder = q.offset + i, rank = q.offset + i + 1) }
        DiscoverPage(entries, hasMore = results.any { it.second }, catalog = catalog, native = true)
    }
}
