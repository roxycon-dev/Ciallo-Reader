package com.example.source.impl

import com.example.discover.*
import com.example.source.*
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
    private var saved: Pair<Long, Document>? = null
    private suspend fun homepage(): Document = mutex.withLock {
        saved?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.second
            ?: fetch(home).also { saved = System.currentTimeMillis() to it }
    }
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
    suspend fun catalog(): DiscoverCatalog = catalogue(homepage())
    private fun links(node: Element): Set<String> = node.select("a[href]").mapNotNull { a -> safe(a.attr("href"))?.let(identify) }.toSet()
    internal fun books(doc: Document, category: String? = null): List<SearchBook> {
        val found = linkedMapOf<String, SearchBook>()
        doc.select("a[href]").forEach { link ->
            val bookId = safe(link.attr("href"))?.let(identify) ?: return@forEach
            var card = link
            repeat(6) {
                val parent = card.parent()
                if (parent != null && links(parent) == setOf(bookId)) card = parent
            }
            val titleLink = card.select("h1 a[href], h2 a[href], h3 a[href], h4 a[href], .title a[href], .book-title a[href]")
                .firstOrNull { safe(it.attr("href"))?.let(identify) == bookId }
            val title = (titleLink?.text() ?: card.selectFirst("h3.title, .book-title")?.text()
                ?: link.selectFirst(".font-medium, h1, h2, h3, h4")?.text()
                ?: link.attr("title").takeIf { it.isNotBlank() } ?: link.selectFirst("p")?.text()
                ?: link.ownText()).trim()
            if (title.length !in 2..150 || title.all(Char::isDigit)) return@forEach
            val author = (card.selectFirst(".bauthor, .author, .book-author, .novel-item-author, a[href*=/author/]")?.text() ?: link.selectFirst("span.text-xs")?.text()?.substringBefore(" · ")).orEmpty()
                .removePrefix("作者：").removePrefix("作者:").trim()
            val image = card.selectFirst("img[data-original], img[data-src], img[src]")
            val cover = image?.let { img -> listOf("data-original", "data-src", "src").firstNotNullOfOrNull { key ->
                img.attr(key).takeIf { it.isNotBlank() }?.let { doc.location().let(home::resolve)?.resolve(it)?.toString() }
            } }
            val status = card.selectFirst(".lz, .novel-item-state, .state, .book-status")?.text()
            val latest = card.selectFirst(".l-last a, .novel-item-newest-chapter a, .newchapter a")?.text()
            val intro = card.selectFirst(".l-p2, .intro, .description, .book-intro")?.text()
            val book = SearchBook(bookId, sourceId, title, author, cover = cover, description = intro, format = format, language = "中文",
                novelInfo = NovelInfo(synopsis = intro, status = status, category = category, latestChapter = latest))
            val old = found[bookId]
            if (old == null || (old.cover == null && cover != null) || (old.author.isBlank() && author.isNotBlank())) found[bookId] = book
        }
        return found.values.toList()
    }
    suspend fun discover(query: DiscoverQuery): DiscoverPage {
        val catalog = catalog()
        if (query.feedId == null && query.categoryId == null && query.categoryIds.isEmpty() && !catalog.supports(query.dimension, query.range))
            return DiscoverPage(supported = false, notice = "该书源未提供当前榜单或时间范围", catalog = catalog)
        val categoryId = query.categoryId ?: query.categoryIds.firstOrNull()
        val category = catalog.categories.firstOrNull { it.id == categoryId }
        val feed = if (category == null) {
            if (query.feedId == "native") catalog.feeds.first()
            else if (query.feedId != null) catalog.feeds.firstOrNull { it.id == query.feedId }
            else catalog.feeds.firstOrNull { it.kind == query.dimension.name.lowercase() }
        } else null
        if ((categoryId != null && category == null) || (category == null && feed == null))
            return DiscoverPage(supported = false, notice = "该书源未提供当前榜单或时间范围，可切换原站推荐或分类", catalog = catalog)
        val start = if (category != null) safe(category.id) else if (feed?.id == "native") home else safe(feed!!.id)
        val target = query.cursor?.let(::safe) ?: if (query.page == 1) start else null
        if (target == null) return DiscoverPage(catalog = catalog)
        val doc = if (target == home) homepage() else fetch(target)
        val rows = books(doc, category?.label)
        require(rows.isNotEmpty() || Regex("暂无|没有|无结果|沒有").containsMatchIn(doc.text())) { "源站未返回可识别的小说列表" }
        val next = doc.select("a[href]").firstNotNullOfOrNull { a ->
            if (a.attr("rel").equals("next", true) || a.text().trim() in setOf("下一页", "下页", "下一頁", "Next", "›", "»"))
                safe(a.absUrl("href"))?.takeIf { it != target && identify(it) == null } else null
        }
        val ranked = feed?.kind == "ranking"
        return DiscoverPage(rows.mapIndexed { i, book -> DiscoverEntry(book, latestChapter = book.novelInfo?.latestChapter,
            nativeOrder = query.offset + i, rank = if (ranked) query.offset + i + 1 else null,
            native = true, origin = category?.label ?: feed?.label) }, hasMore = next != null,
            nextCursor = next?.toString(), catalog = catalog, native = true)
    }
}
