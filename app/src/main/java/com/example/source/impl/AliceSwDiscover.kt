package com.example.source.impl

import com.example.discover.DiscoverCatalog
import com.example.discover.DiscoverDimension
import com.example.discover.DiscoverEntry
import com.example.discover.DiscoverFeed
import com.example.discover.DiscoverNativeCategory
import com.example.discover.DiscoverPage
import com.example.discover.DiscoverQuery
import com.example.source.NovelInfo
import com.example.source.SearchBook
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 爱丽丝书屋发现页：排行（日/周/月/总）与分类目录。
 *
 * 站点的列表结构在首页各栏目、`/lists/{分类id}.html`、`/other/rank_hits/order/hits*.html`
 * 和 `/all/order/update_time+desc.html` 上是同一套卡片：
 * `div.rec_rullist > ul`，其中 `li.one` 名次、`li.sev` 分类、`li.two` 书名、
 * `li.three` 最新章节、`li.four` 作者、`li.five` 字数、`li.six` 更新日期、`li.diyhot` 热度。
 *
 * 排行页固定 50 条且不分页；分类页与「最近更新」用 `?page=N` 翻页。
 */
internal object AliceSwDiscover {

    private val LIST_HREF = Regex("""/lists/(\d{1,3})\.html""")
    private val NOVEL_HREF = Regex("""/novel/(\d{1,9})\.html""")
    private val DATE = Regex("""(\d{4}-\d{2}-\d{2})""")
    private val CATEGORY_SUFFIX = Regex("""小说$""")

    private const val RANK_PATH = "/other/rank_hits/order/"
    private const val UPDATES_PATH = "/all/order/update_time+desc.html"

    /** 排行档位与站点路径一一对应；站点只有这四档，不编造"飙升/新作"。 */
    private val RANK_RANGES = linkedMapOf(
        "日" to "hits_day.html",
        "周" to "hits_week.html",
        "月" to "hits_month.html",
        "总" to "hits.html"
    )

    fun catalog(html: String, base: String): DiscoverCatalog {
        val doc = Jsoup.parse(html)
        val feeds = buildList {
            add(DiscoverFeed("$base$UPDATES_PATH", "最近更新", "updates"))
            RANK_RANGES.forEach { (label, path) ->
                add(DiscoverFeed("$base$RANK_PATH$path", "${label}榜", "ranking"))
            }
        }
        val seen = linkedSetOf<String>()
        val categories = mutableListOf<DiscoverNativeCategory>()
        for (anchor in doc.select("a[href]")) {
            val match = LIST_HREF.find(anchor.attr("href")) ?: continue
            val label = anchor.text().trim().replace(CATEGORY_SUFFIX, "").trim()
            if (label.isEmpty() || label.length > 6) continue
            val id = "$base/lists/${match.groupValues[1]}.html"
            if (seen.add(id)) categories += DiscoverNativeCategory(id, label, "分类")
        }
        return DiscoverCatalog(
            feeds = feeds,
            categories = categories,
            declared = true,
            explicit = true,
            capabilities = mapOf(
                "ranking" to RANK_RANGES.keys.toList(),
                "updates" to listOf("最新")
            )
        )
    }

    /** 把维度和档位翻译成站点地址；返回 null 表示该维度确实没有数据。 */
    fun route(base: String, catalog: DiscoverCatalog, query: DiscoverQuery): String? {
        query.cursor?.takeIf { it.isNotBlank() }?.let { return it }
        query.feedId?.takeIf { it != "native" }?.let { id ->
            catalog.feeds.firstOrNull { it.id == id }?.let { return it.id }
        }
        val category = (listOfNotNull(query.categoryId) + query.categoryIds)
            .firstNotNullOfOrNull { id -> catalog.categories.firstOrNull { it.id == id } }
        if (category != null) return withPage(category.id, query.page)
        return when (query.dimension) {
            DiscoverDimension.RANKING -> RANK_RANGES[query.range]?.let { "$base$RANK_PATH$it" }
            DiscoverDimension.UPDATES -> withPage("$base$UPDATES_PATH", query.page)
            else -> null
        }
    }

    fun entries(html: String, sourceId: String, ranked: Boolean, offset: Int = 0): List<DiscoverEntry> {
        val doc = Jsoup.parse(html)
        val rows = mutableListOf<DiscoverEntry>()
        for (item in doc.select("div.rec_rullist > ul")) {
            val titleLink = item.selectFirst("li.two a[href]") ?: continue
            val bookId = NOVEL_HREF.find(titleLink.attr("href"))?.groupValues?.get(1) ?: continue
            val title = titleLink.text().trim()
            if (title.isEmpty()) continue
            val rank = item.selectFirst("li.one")?.text()?.trim()?.toIntOrNull()
            val category = item.selectFirst("li.sev")?.text()?.trim()
                ?.replace(CATEGORY_SUFFIX, "")?.trim()?.takeIf { it.isNotEmpty() }
            val latest = item.selectFirst("li.three")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val author = item.selectFirst("li.four")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val wordCount = item.selectFirst("li.five")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val date = DATE.find(item.selectFirst("li.six")?.text().orEmpty())?.groupValues?.get(1)
            val clicks = item.selectFirst("li.diyhot")?.text()?.trim()?.toLongOrNull()
            rows += DiscoverEntry(
                book = SearchBook(
                    id = bookId,
                    sourceId = sourceId,
                    title = title,
                    author = author ?: "未知作者",
                    format = "epub",
                    language = "中文",
                    novelInfo = NovelInfo(
                        category = category,
                        latestChapter = latest,
                        updatedAt = date,
                        wordCount = wordCount,
                        translation = "中文"
                    )
                ),
                updatedAt = date?.let(::epochDay),
                latestChapter = latest,
                clicks = clicks?.takeIf { it >= 0 },
                rank = (rank ?: (offset + rows.size + 1)).takeIf { ranked },
                nativeOrder = offset + rows.size,
                native = true,
                origin = category
            )
        }
        return rows.distinctBy { it.book.id }
    }

    /** `?page=N` 翻页；排行页没有下一页，站外地址一律丢弃。 */
    fun nextPage(html: String, current: String): String? {
        val doc = Jsoup.parse(html)
        val anchor = doc.select("a[href]").firstOrNull { a ->
            a.text().trim() == "下一页" || a.attr("rel").equals("next", true)
        } ?: return null
        val here = current.toHttpUrlOrNull() ?: return null
        val next = here.resolve(anchor.attr("href").trim()) ?: return null
        if (next == here || next.host != here.host) return null
        return next.toString()
    }

    fun isRanked(query: DiscoverQuery): Boolean = query.dimension == DiscoverDimension.RANKING ||
        query.feedId?.let { id -> id.contains("rank_hits") } == true

    fun notice(): DiscoverPage = DiscoverPage(
        supported = false,
        notice = "爱丽丝书屋只提供排行与分类目录，没有该维度的数据"
    )

    private fun withPage(url: String, page: Int): String = if (page <= 1) url else "$url?page=$page"

    private fun epochDay(date: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)?.time
    }.getOrNull()
}
