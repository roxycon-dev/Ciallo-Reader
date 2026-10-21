package com.example.source.impl

import com.example.discover.*
import com.example.source.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Follows only the site's own catalogue links, never guessed search/ranking endpoints. */
internal class HtmlNovelDiscovery(
    private val sourceId: String, private val home: HttpUrl,
    private val fetch: suspend (HttpUrl) -> Document,
    private val identify: (HttpUrl) -> String?,
    private val format: String = "epub",
) {
    private val mutex = Mutex()
    private val saved = linkedMapOf<String, Pair<Long, Document>>()
    private suspend fun document(url: HttpUrl): Document = mutex.withLock {
        saved[url.toString()]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.second
            ?: fetch(url).also {
                if (saved.size >= 12) saved.remove(saved.keys.first())
                saved[url.toString()] = System.currentTimeMillis() to it
            }
    }
    private suspend fun homepage() = document(home)
    private fun safe(value: String): HttpUrl? = home.resolve(value)?.takeIf {
        it.host == home.host && it.scheme == home.scheme && it.username.isEmpty() && it.password.isEmpty()
    }
    private fun catalogue(doc: Document): DiscoverCatalog {
        val feeds = mutableListOf(DiscoverFeed("native", "原站推荐"))
        val categories = mutableListOf<DiscoverNativeCategory>()
        val genres = Regex("玄幻|奇幻|都市|言情|古言|穿越|青春|仙侠|修真|武侠|宫廷|乡村|灵异|悬疑|历史|军事|游戏|竞技|科幻|职场|官场|现言|耽美|同人|校园|轻小说|其他|其它")
        doc.select("a[href]").forEach { a ->
            val label = a.text().trim()
            val url = safe(a.attr("href")) ?: return@forEach
            if (label.isBlank() || label.length > 18 || identify(url) != null || url == home || url.fragment != null) return@forEach
            val kind = when {
                Regex("最近更新|最新更新|^更新$|^最新$").containsMatchIn(label) -> "updates"
                Regex("排行|榜单|热门榜|人气榜|收藏榜|点击榜").containsMatchIn(label) -> "ranking"
                else -> null
            }
            if (kind != null) feeds += DiscoverFeed(url.toString(), label, kind)
            else if (genres.containsMatchIn(label)) categories += DiscoverNativeCategory(url.toString(), label, "原站分类")
        }
        return DiscoverCatalog(feeds.distinctBy { it.id }, categories.distinctBy { it.id }, declared = true, explicit = true,
            capabilities = buildMap {
                if (feeds.any { it.kind == "updates" }) put("updates", listOf("最新"))
                if (feeds.any { it.kind == "ranking" }) put("ranking", listOf("热门"))
            })
    }
    suspend fun catalog(): DiscoverCatalog {
        val doc = document(home.resolve(when (sourceId) {
            "sfacg" -> "/category/"; "xbookcn" -> "/novels"; "ixdzs8" -> "/sort/1/"; else -> "/"
        })!!)
        return NovelDiscoverySites.catalog(sourceId, home, doc) ?: catalogue(doc)
    }
    private fun links(node: Element): Set<String> = node.select("a[href]").mapNotNull { a -> safe(a.attr("href"))?.let(identify) }.toSet()
    internal fun books(doc: Document, category: String? = null): List<SearchBook> {
        val found = linkedMapOf<String, SearchBook>()
        doc.select("a[href]").forEach { link ->
            val bookId = safe(link.attr("href"))?.let(identify) ?: return@forEach
            var card = link
            for (step in 0 until 6) {
                val parent = card.parent() ?: break
                if (links(parent) != setOf(bookId)) break
                card = parent
            }
            val titleLink = card.select("h1 a[href], h2 a[href], h3 a[href], h4 a[href], .title a[href], .book-title a[href]")
                .firstOrNull { safe(it.attr("href"))?.let(identify) == bookId }
            val title = (titleLink?.text() ?: card.selectFirst("#listtitle, h3.title, .book-title")?.text()
                ?: link.selectFirst(".font-medium, h1, h2, h3, h4")?.text()
                ?: link.attr("title").takeIf { it.isNotBlank() } ?: link.selectFirst("p")?.text()
                ?: link.selectFirst("[itemprop=name]")?.text() ?: link.selectFirst("img[alt]")?.attr("alt") ?: link.ownText()).trim()
            if (title.length !in 2..150 || title.all(Char::isDigit)) return@forEach
            val sfMeta = if (sourceId == "sfacg") link.selectFirst("#listtitle")?.parent()?.children()?.lastOrNull()?.text() else null
            val xAuthor = if (sourceId == "xbookcn") card.selectFirst("h3")?.parent()?.parent()?.selectFirst("p")?.text() else null
            val author = (sfMeta?.substringBeforeLast('/') ?: xAuthor ?: card.selectFirst(".bauthor, .author, .author-info, [itemprop=author], .book-author, .novel-item-author, a[href*=/author/], a[href*='user.ihuaben.com/']")?.text() ?: link.selectFirst("span.text-xs")?.text()?.substringBefore(" · ")).orEmpty()
                .removePrefix("作者：").removePrefix("作者:").trim()
            val image = card.selectFirst("img[data-original], img[data-src], img[src]")
            val cover = image?.let { img -> listOf("data-original", "data-src", "src").firstNotNullOfOrNull { key ->
                img.attr(key).takeIf { it.isNotBlank() }?.let { doc.location().let(home::resolve)?.resolve(it)?.toString() }
            } }
            val status = card.selectFirst(".lz, .novel-item-state, .state, .book-status")?.text()
            val latest = card.selectFirst(".l-last a, .novel-item-newest-chapter a, .newchapter a")?.text()
            val intro = card.selectFirst(".l-p2, .intro, .description, .book-intro, [itemprop=description]")?.text()
            val quanbenCategory = if (sourceId == "quanben") card.parents().firstOrNull { it.hasClass("box") }
                ?.selectFirst("h2")?.text()?.trim()?.takeUnless { it.contains("推荐") } else null
            val nativeCategory = quanbenCategory ?: sfMeta?.substringAfterLast('/')?.trim()?.takeIf(String::isNotEmpty)
                ?: card.selectFirst(".nsort, .book-type")?.text()?.trim()
                ?: if (sourceId == "xbookcn") card.selectFirst("span.shrink-0.rounded")?.text() else null
            val tags = when (sourceId) {
                "sfacg" -> link.select("li").firstOrNull()?.selectFirst("div")?.text().orEmpty().split('/').map(String::trim).filter(String::isNotEmpty)
                "ihuaben" -> card.select("a[href*='/category/']").map { it.text().trim() }.filter(String::isNotEmpty)
                "xbookcn" -> card.select("span.text-stone-400").filter { it.children().isEmpty() }.map { it.text().trim() }.filter { it.isNotEmpty() && it != "·" }
                else -> emptyList()
            }
            val book = SearchBook(bookId, sourceId, title, author, cover = cover, description = intro, format = format, language = "中文",
                novelInfo = NovelInfo(synopsis = intro, status = status, category = nativeCategory ?: category, latestChapter = latest, tags = tags))
            val old = found[bookId]
            found[bookId] = if (old == null) book else old.copy(
                author = old.author.ifBlank { book.author }, cover = old.cover ?: book.cover,
                description = old.description ?: book.description,
                novelInfo = old.novelInfo?.copy(category = old.novelInfo.category ?: book.novelInfo?.category,
                    tags = (old.novelInfo.tags + book.novelInfo?.tags.orEmpty()).distinct(),
                    status = old.novelInfo.status ?: book.novelInfo?.status,
                    latestChapter = old.novelInfo.latestChapter ?: book.novelInfo?.latestChapter) ?: book.novelInfo)
        }
        return found.values.toList()
    }
    suspend fun discover(query: DiscoverQuery): DiscoverPage = coroutineScope {
        val catalog = catalog()
        if (query.feedId == null && !catalog.supports(query.dimension, query.range))
            return@coroutineScope DiscoverPage(supported = false,
                notice = if (sourceId == "quanben") "全本小说网未公开更新榜或热度榜，可查看原站推荐和分类目录" else "该书源未提供当前榜单或时间范围", catalog = catalog)
        if (query.categoryId != null && catalog.categories.none { it.id == query.categoryId })
            return@coroutineScope DiscoverPage(supported = false, notice = "原站分类已变化，请重新选择", catalog = catalog)
        val starts = if (query.cursor != null) decodeCursor(query.cursor) else if (query.page == 1) {
            NovelDiscoverySites.routes(sourceId, home, catalog, query).ifEmpty {
                val c = catalog.categories.firstOrNull { it.id == (query.categoryId ?: query.categoryIds.firstOrNull()) }
                val f = catalog.feeds.firstOrNull { it.id == query.feedId || (query.feedId == null && it.kind == query.dimension.name.lowercase()) }
                listOfNotNull((c?.id ?: f?.id)?.let { if (it == "native") home else safe(it) }?.let { it to (c?.label ?: f?.label.orEmpty()) })
            }
        } else emptyList()
        if (starts.isEmpty()) return@coroutineScope DiscoverPage(catalog = catalog)
        val slots = Semaphore(3)
        val pages = starts.map { (target, origin) -> async { slots.withPermit {
            val doc = document(target)
            val rows = if (sourceId == "sfacg" && target.encodedPath == "/API/HTML5.ashx") sfBooks(doc) else books(doc, catalog.categories.firstOrNull { c ->
                safe(c.id)?.let { target.encodedPath.startsWith(it.encodedPath) && (it.encodedQuery == null || it.encodedQuery == target.encodedQuery) } == true
            }?.label)
            require((sourceId == "sfacg" && target.encodedPath == "/API/HTML5.ashx") || rows.isNotEmpty() || Regex("暂无|没有|无结果|沒有").containsMatchIn(doc.text())) { "源站未返回可识别的小说列表" }
            val next = doc.select("a[href]").firstNotNullOfOrNull { a ->
                if (a.attr("rel").equals("next", true) || a.text().trim() in setOf("下一页", "下页", "下一頁", "Next", "›", "»"))
                    safe(a.absUrl("href"))?.takeIf { it != target && identify(it) == null } else null
            } ?: if (sourceId == "sfacg" && rows.isNotEmpty() && (target.encodedPath == "/lateList/" || target.encodedPath == "/API/HTML5.ashx")) {
                home.resolve("/API/HTML5.ashx")!!.newBuilder().addQueryParameter("op", "latest")
                    .addQueryParameter("index", ((target.queryParameter("index")?.toIntOrNull() ?: 0) + 1).toString())
                    .addQueryParameter("sign", "false").build()
            } else null
            Triple(rows, origin, next)
        } } }.awaitAll()
        val ranked = query.feedId?.let { f -> catalog.feeds.any { it.id == f && it.kind == "ranking" } }
            ?: (query.dimension == DiscoverDimension.RANKING)
        val selected = (listOfNotNull(query.categoryId) + query.categoryIds).mapNotNull { id -> catalog.categories.find { it.id == id } }
        val rows = pages.flatMap { (books, origin, _) -> books.map { it to origin } }.distinctBy { it.first.id }
            .filter { (book, _) -> selected.isEmpty() || sourceId in setOf("ihuaben", "xbookcn") || query.feedId == "native" ||
                selected.any { c -> c.label == book.novelInfo?.category || c.label in book.novelInfo?.tags.orEmpty() } }
        val next = pages.mapNotNull { (_, origin, url) -> url?.let { it to origin } }
        DiscoverPage(rows.mapIndexed { i, (book, origin) -> DiscoverEntry(book, latestChapter = book.novelInfo?.latestChapter,
            nativeOrder = query.offset + i, rank = if (ranked) query.offset + i + 1 else null, native = true, origin = origin) },
            hasMore = next.isNotEmpty(), nextCursor = encodeCursor(next), catalog = catalog, native = true)
    }
    private fun encodeCursor(routes: List<Pair<HttpUrl, String>>): String? = when (routes.size) {
        0 -> null; 1 -> routes.single().first.toString()
        else -> JSONArray(routes.map { (url, label) -> JSONObject().put("url", url.toString()).put("label", label) }).toString()
    }
    private fun decodeCursor(value: String): List<Pair<HttpUrl, String>> = if (!value.startsWith("["))
        listOfNotNull(safe(value)?.let { it to "原站续页" }) else JSONArray(value).let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> safe(o.optString("url"))?.let { it to o.optString("label") } } }
        }
    private fun sfBooks(doc: Document): List<SearchBook> {
        val a = JSONArray(doc.body().text())
        fun decode(s: String) = java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SearchBook(o.getLong("NovelID").toString(), sourceId, decode(o.getString("NovelName")), decode(o.optString("AuthorName")),
                cover = "https://rs.sfacg.com/web/novel/images/NovelCover/Big/" + o.optString("NovelCover"), format = format, language = "中文",
                novelInfo = NovelInfo(category = decode(o.optString("TypeName")), tags = o.optJSONArray("Tags")?.let { t ->
                    (0 until t.length()).map { decode(t.getString(it)) } }.orEmpty()))
        }
    }
}
