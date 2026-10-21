package com.example.source.impl

import com.example.discover.*
import okhttp3.HttpUrl
import org.jsoup.nodes.Document

/** Audited public navigation contracts, not keyword matches against arbitrary book titles. */
internal object NovelDiscoverySites {
    fun catalog(source: String, home: HttpUrl, doc: Document): DiscoverCatalog? {
        fun feed(path: String, label: String, kind: String? = null) = DiscoverFeed(home.resolve(path)!!.toString(), label, kind)
        val feeds = mutableListOf(DiscoverFeed("native", "原站推荐"))
        val categories = mutableListOf<DiscoverNativeCategory>()
        when (source) {
            "sfacg" -> {
                feeds += feed("/lateList/", "最新更新", "updates")
                feeds += feed("/rank/original.html", "人气榜", "ranking")
                feeds += feed("/rank/new.html", "新书榜", "ranking")
                feeds += feed("/rank/sale.html", "畅销榜", "ranking")
                feeds += feed("/rank/ticket.html", "月票榜", "ranking")
                // Mobile navigation uses image-only anchors; the label is a sibling text node.
                doc.select("a[href]").forEach { a ->
                    val u = home.resolve(a.attr("href")) ?: return@forEach
                    if (u.host == home.host && Regex("^/category/[0-9]+/$").matches(u.encodedPath)) {
                        val label = a.parent()?.ownText().orEmpty().trim()
                        if (label.isNotEmpty()) categories += DiscoverNativeCategory(u.toString(), label, "题材")
                    }
                }
            }
            "ihuaben" -> {
                listOf("girl" to "女频", "boy" to "男频", "tongren" to "同人").forEach { (path, label) ->
                    feeds += feed("/$path/updatetime/1.html", "$label·章节更新", "updates")
                    feeds += feed("/$path/clickcount_days_7/1.html", "$label·七日点击榜", "ranking")
                    feeds += feed("/$path/createtime/1.html", "$label·新书", "ranking")
                }
                val names = mapOf("girl" to "女频", "boy" to "男频", "tongren" to "同人", "mingxingtongren" to "明星同人",
                    "yingshitongren" to "影视同人", "dongmantongren" to "动漫同人", "youxitongren" to "游戏同人",
                    "xiaoyuanyanqing" to "校园言情", "gudaiyanqing" to "古代言情", "chuanyueyanqing" to "穿越言情",
                    "lingyiyanqing" to "灵异言情", "xuanhuan" to "玄幻", "dushi" to "都市", "xianxia" to "仙侠",
                    "kehuan" to "科幻", "lishi" to "历史", "jingji" to "竞技", "qingxiaoshuo" to "轻小说")
                doc.select("a[href]").forEach { a ->
                    val u = home.resolve(a.attr("href")) ?: return@forEach
                    val key = u.encodedPath.trim('/')
                    if (u.host == home.host && key in names)
                        categories += DiscoverNativeCategory(u.toString(), names.getValue(key), "话本分类")
                }
            }
            "ixdzs8" -> {
                feeds += feed("/new/", "最近更新", "updates")
                feeds += feed("/hot/", "总排行榜", "ranking")
                feeds += feed("/hot/day/", "日排行榜", "ranking")
                feeds += feed("/hot/month/", "月排行榜", "ranking")
                categories += doc.select("a[href]").mapNotNull { a ->
                    val u = home.resolve(a.attr("href")) ?: return@mapNotNull null
                    if (u.host == home.host && u.encodedQuery == null && Regex("^/sort/[0-9]+/$").matches(u.encodedPath) &&
                        !Regex("分类|推荐|完结|^全部$").containsMatchIn(a.text()))
                        DiscoverNativeCategory(u.toString(), a.text().trim(), "题材") else null
                }
            }
            "xbookcn" -> {
                feeds += feed("/novels", "最近更新", "updates")
                feeds += feed("/novels?sort=heat", "热度榜", "ranking")
                categories += doc.select("a[href]").mapNotNull { a ->
                    val u = home.resolve(a.attr("href")) ?: return@mapNotNull null
                    if (u.host != home.host || u.encodedPath != "/novels") return@mapNotNull null
                    val key = listOf("cat", "tag").firstOrNull { !u.queryParameter(it).isNullOrBlank() } ?: return@mapNotNull null
                    // Parameter values are the actual category names; anchor text includes counts/descriptions.
                    val value = u.queryParameter(key)!!
                    val canonical = home.resolve("/novels")!!.newBuilder().addQueryParameter(key, value).build()
                    DiscoverNativeCategory(canonical.toString(), value, if (key == "cat") "题材" else "标签")
                }
            }
            "quanben" -> categories += doc.select("a[href]").mapNotNull { a ->
                val u = home.resolve(a.attr("href")) ?: return@mapNotNull null
                if (u.host == home.host && Regex("^/c/[a-z]+\\.html$").matches(u.encodedPath))
                    DiscoverNativeCategory(u.toString(), a.text().trim(), "题材") else null
            }
            else -> return null
        }
        return DiscoverCatalog(feeds.distinctBy { it.id }, categories.filter { it.label.isNotEmpty() }.distinctBy { it.id },
            declared = true, explicit = true, capabilities = buildMap {
                if (feeds.any { it.kind == "updates" }) put("updates", listOf("最新"))
                if (feeds.any { it.kind == "ranking" }) put("ranking", if (source in setOf("sfacg", "ihuaben")) listOf("热门", "新作") else listOf("热门"))
            })
    }

    /** Categories never silently replace a requested update feed with an unsorted category page. */
    fun routes(source: String, home: HttpUrl, catalog: DiscoverCatalog, q: DiscoverQuery): List<Pair<HttpUrl, String>> {
        val selected = (listOfNotNull(q.categoryId) + q.categoryIds).distinct().mapNotNull { id -> catalog.categories.find { it.id == id } }
        if (q.feedId != null && q.feedId != "native") return catalog.feeds.filter { it.id == q.feedId }
            .mapNotNull { f -> home.resolve(f.id)?.let { it to f.label } }
        if (q.feedId == "native") {
            if (selected.isEmpty()) return listOf(home to "原站推荐")
            return selected.mapNotNull { c -> home.resolve(c.id)?.let { it to c.label } }
        }
        if (source == "ihuaben") {
            val paths = selected.map { home.resolve(it.id)!!.encodedPath.trim('/') to it.label }
                .ifEmpty { listOf("girl" to "女频", "boy" to "男频", "tongren" to "同人") }
            val sort = if (q.dimension == DiscoverDimension.UPDATES) "updatetime" else if (q.range == "新作") "createtime" else "clickcount_days_7"
            return paths.map { (p, label) -> home.resolve("/$p/$sort/1.html")!! to "$label·${if (sort == "updatetime") "章节更新" else if (sort == "createtime") "新书" else "七日点击榜"}" }
        }
        if (source == "ixdzs8" && selected.isNotEmpty()) return selected.map { c ->
            val path = home.resolve(c.id)!!.encodedPath
            home.resolve(path + if (q.dimension == DiscoverDimension.RANKING) "index-0-0-1-1.html" else "index.html")!! to c.label
        }
        if (source == "xbookcn" && selected.isNotEmpty()) return selected.map { c ->
            home.resolve(c.id)!!.newBuilder().apply { if (q.dimension == DiscoverDimension.RANKING) setQueryParameter("sort", "heat") }.build() to c.label
        }
        val feed = catalog.feeds.firstOrNull { it.kind == q.dimension.name.lowercase() &&
            (q.range != "新作" || it.id.endsWith("/new.html")) }
        return listOfNotNull(feed?.let { home.resolve(it.id)!! to it.label })
    }
}
