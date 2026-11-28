package com.example.source.impl

import com.example.source.ComicChapter
import com.example.source.NovelInfo
import com.example.source.SearchBook
import com.example.source.SourceException
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap

/**
 * 爱丽丝书屋解析（2026-11 站点重写后）。
 *
 * 站点从 PHP 重写为 Next.js：可达线路（alicesw1/2/4/5/6.homes、www.alicesw.tw）对外是
 * 服务端渲染页面，对内是 /api/v1/… JSON 接口；alicesw.net / alicesw.click / alicesw.club /
 * 爱丽丝.als1007.space 只挂发布页。解析按两套来源实现，JSON 优先、页面兜底：
 *
 *  - 搜索  `/api/v1/search?q=&sort=&page=&page_size=`（sort 省略 = 相关度；latest = 最近更新；
 *          favorites = 收藏序；不带 q 只返回精选，不能当全站目录用）
 *  - 详情  `/api/v1/books/{内部id}`（页面地址是 /books/{book_no}，两个号不同，JSON 里都有）
 *  - 目录  `/api/v1/books/{内部id}/chapters`
 *  - 正文  `/api/v1/chapters/{chapter_no}`，data.content 按 \n 分段，带 can_read / 前后章节号
 *  - 分类  `/api/v1/categories`；分类列表 `/api/v1/books?category_id=&page=`
 *  - 封面  cover_url 是站内路径（/api/v1/media/{hash}），按当前线路补全
 *
 * 作品标识统一用内部 id（纯数字）；章节标识就是 chapter_no（纯数字）。
 * 旧站 PHP 结构的解析已删除——所有可达线路都已是新版（旧实现见 git 历史）。
 */
internal object AliceSwParser {

    private val BOOK_HREF = Regex("""/books/(\d{1,12})""")
    private val META_WORD = Regex("""([0-9]+(?:\.[0-9]+)?)万""")
    private val META_STATUS = Regex("""(已完结|连载中|完本)""")
    private val API_DATE = Regex("""(\d{4}-\d{2}-\d{2})""")

    /** 详情/搜索解析出来的 book_no 缓存（id → 页面地址里的公开书号），供分享链接使用。 */
    private val bookNos = ConcurrentHashMap<String, String>()

    fun bookNoFor(bookId: String): String? = bookNos[bookId]

    private fun rememberBookNo(id: String, bookNo: String?) {
        if (!bookNo.isNullOrBlank() && bookNos.size > 800) bookNos.clear()
        bookNo?.let { bookNos[id] = it }
    }

    // ---------- JSON（主路径） ----------

    internal class ApiBook(val book: SearchBook, val bookNo: String?)

    /**
     * 搜索条目（/api/v1/search 的 items）、列表条目（/api/v1/books 的 books）与详情
     * （/api/v1/books/{id} 的 data）共用一套字段解析；搜索是扁平 author_name + 字符串
     * tags，列表/详情是 author 对象 + 对象 tags，两种形态都接。
     */
    fun bookFromApi(item: JSONObject, sourceId: String, base: String, categoryOverride: String? = null): ApiBook? {
        val id = item.optString("id").trim()
        val title = item.optString("title").trim()
        if (id.isEmpty() || title.isEmpty()) return null
        val synopsis = item.optString("synopsis").trim().takeIf { it.isNotEmpty() }
        val category = categoryOverride?.takeIf { it.isNotBlank() }
        val book = SearchBook(
            id = id,
            sourceId = sourceId,
            title = title,
            author = authorOf(item).ifBlank { "未知作者" },
            cover = coverFromApi(item, base),
            description = synopsis,
            format = "epub",
            language = "中文",
            novelInfo = NovelInfo(
                synopsis = synopsis,
                status = statusOf(item),
                category = category,
                updatedAt = API_DATE.find(item.optString("updated_at"))?.groupValues?.get(1),
                chapterCount = item.optInt("chapter_count", 0).takeIf { it > 0 },
                wordCount = formatWordCount(item.optLong("word_count", 0L)),
                tags = tagsOf(item),
                translation = "中文"
            )
        )
        val bookNo = item.optString("book_no").trim().takeIf { it.isNotEmpty() }
        rememberBookNo(id, bookNo)
        return ApiBook(book, bookNo)
    }

    /** /api/v1/search 的 data.items → 搜索结果。 */
    fun searchFromApi(json: JSONObject, sourceId: String, base: String): List<SearchBook> {
        val arr = json.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { bookFromApi(it, sourceId, base) }?.book
        }
    }

    /** /api/v1/books 的 data.books → 发现列表。 */
    fun listFromApi(json: JSONObject, sourceId: String, base: String): List<SearchBook> {
        val arr = json.optJSONObject("data")?.optJSONArray("books") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { bookFromApi(it, sourceId, base) }?.book
        }
    }

    /** /api/v1/books/{id} 的 data → 详情；顺带记下 book_no 供分享链接使用。 */
    fun detailFromApi(json: JSONObject, sourceId: String, base: String): SearchBook {
        val data = json.optJSONObject("data") ?: throw SourceException.ParseError("爱丽丝书屋详情接口返回缺少数据")
        val category = data.optJSONObject("category")?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
        val parsed = bookFromApi(data, sourceId, base, categoryOverride = category)
            ?: throw SourceException.ParseError("爱丽丝书屋详情缺少作品标识，页面可能已变化")
        return parsed.book
    }

    /** /api/v1/books/{id}/chapters 的 data 数组 → 目录；顺序即阅读顺序。 */
    fun chaptersFromApi(json: JSONObject, sourceId: String): List<ComicChapter> {
        val arr = json.optJSONArray("data") ?: return emptyList()
        val chapters = mutableListOf<ComicChapter>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val no = item.optString("chapter_no").trim()
            if (no.isEmpty()) continue
            val title = (item.optString("display_title").ifBlank { item.optString("title") })
                .trim().ifBlank { "第 ${chapters.size + 1} 节" }
            chapters += ComicChapter(id = no, title = title, order = (chapters.size + 1).toFloat())
        }
        return chapters.distinctBy { it.id }
    }

    /** /api/v1/chapters/{no} 的 data.content（\n 分段）→ 阅读器正文（\n\n 分段）。 */
    fun chapterTextFromApi(json: JSONObject): String {
        val data = json.optJSONObject("data") ?: throw SourceException.ParseError("爱丽丝书屋正文接口返回缺少数据")
        if (!data.optBoolean("can_read", true)) {
            throw SourceException.ParseError("该章节未解锁或需要登录后阅读")
        }
        val content = data.optString("content")
        val paragraphs = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) throw SourceException.ParseError("本章正文为空，可能已下架或需要登录")
        return paragraphs.joinToString("\n\n")
    }

    /** /api/v1/categories 的 data → (id, 名称) 列表。 */
    fun categoriesFromApi(json: JSONObject): List<Pair<Int, String>> {
        val arr = json.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<Pair<Int, String>>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optInt("id", 0)
            val name = item.optString("name").trim()
            if (id > 0 && name.isNotEmpty()) out += id to name
        }
        return out
    }

    // ---------- HTML（兜底路径） ----------

    /**
     * 服务端渲染列表页（/search、/rankings、首页栏目）的卡片：
     * `article.book-feature`，链接 `a.book-feature__link[href="/books/{book_no}"]`。
     * 注意卡片链接用的是公开书号 book_no，与 API 的内部 id 不同。
     */
    fun booksFromHtml(html: String, sourceId: String, base: String): List<SearchBook> {
        val doc = Jsoup.parse(html)
        val books = mutableListOf<SearchBook>()
        for (article in doc.select("article.book-feature")) {
            val link = article.selectFirst("a.book-feature__link[href]") ?: continue
            val bookNo = BOOK_HREF.find(link.attr("href"))?.groupValues?.get(1) ?: continue
            // 标题三种来源：卡片 h3 → 无封面占位文案 → 封面图 alt（部分栏目只有图卡片）
            val title = article.selectFirst("h3.book-feature__title")?.text()?.trim()
                ?: article.selectFirst(".book-cover span.line-clamp-2")?.text()?.trim()
                ?: article.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: continue
            if (title.isEmpty()) continue
            val author = article.selectFirst("p.book-feature__author span[data-zh-skip]")?.text()?.trim().orEmpty()
            val summary = article.selectFirst("p.book-feature__summary")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val meta = article.selectFirst("p.book-feature__meta")?.text()?.trim().orEmpty()
            val tags = article.select("p.book-feature__tags span[title]").eachAttr("title")
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val wordCount = META_WORD.find(meta)?.groupValues?.get(1)?.let { "${it}万" }
            books += SearchBook(
                id = bookNo,
                sourceId = sourceId,
                title = title,
                author = author.ifBlank { "未知作者" },
                cover = coverFromHtml(article, base),
                description = summary,
                format = "epub",
                language = "中文",
                novelInfo = NovelInfo(
                    synopsis = summary,
                    status = META_STATUS.find(meta)?.groupValues?.get(1),
                    wordCount = wordCount,
                    tags = tags,
                    translation = "中文"
                )
            )
        }
        return books.distinctBy { it.id }
    }

    /** /books/{book_no} 详情页兜底：标题/作者/简介/标签/徽章 + 章节列表（ChapterList）。 */
    fun detailFromHtml(html: String, bookNo: String, sourceId: String, base: String): SearchBook {
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1[data-zh-skip]")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw SourceException.ParseError("爱丽丝书屋未返回作品标题，页面可能已变化")
        val author = doc.select("a[href*=\"search?q=\"]")
            .firstOrNull { it.text().trim().isNotEmpty() && it.attr("href").contains("tag=").not() }
            ?.text()?.trim().orEmpty()
        val synopsis = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        val tags = doc.select("a[href*=\"search?tag=\"]").eachText().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val status = doc.select("span[data-tone=completed]").takeIf { it.isNotEmpty() }?.let { "已完结" }
            ?: META_STATUS.find(doc.body().text().take(4000))?.groupValues?.get(1)
        val wordCount = META_WORD.find(doc.body().text().take(4000))?.groupValues?.get(1)?.let { "${it}万" }
        val chapters = chaptersFromHtml(html, sourceId)
        val coverRoot = doc.selectFirst("div[class*=page_cover]") ?: doc.body()
        return SearchBook(
            id = bookNo,
            sourceId = sourceId,
            title = title,
            author = author.ifBlank { "未知作者" },
            cover = coverFromHtml(coverRoot, base),
            description = synopsis,
            format = "epub",
            language = "中文",
            novelInfo = NovelInfo(
                synopsis = synopsis,
                status = status,
                updatedAt = null,
                chapterCount = chapters.size.takeIf { it > 0 },
                latestChapter = chapters.firstOrNull()?.title,
                wordCount = wordCount,
                tags = tags,
                translation = "中文",
                notice = "正文免登录公开；整本下载会逐章抓取并复核目录，任何一章失败都不会标记完成。"
            )
        )
    }

    /** 详情页章节列表：`a[data-chapter-row][href="/read/{no}"]`，DOM 顺序即阅读顺序。 */
    fun chaptersFromHtml(html: String, sourceId: String): List<ComicChapter> {
        val doc = Jsoup.parse(html)
        val chapters = mutableListOf<ComicChapter>()
        for (row in doc.select("a[data-chapter-row][href]")) {
            val no = row.attr("data-chapter-row").trim()
            if (!no.matches(Regex("""\d{1,15}"""))) continue
            val title = row.selectFirst("span[class*=ChapterList_title]")?.text()?.trim()
                ?: row.text().trim().ifBlank { "第 ${chapters.size + 1} 节" }
            chapters += ComicChapter(id = no, title = title, order = (chapters.size + 1).toFloat())
        }
        return chapters.distinctBy { it.id }
    }

    /** /read/{no} 兜底：`div[class*=ChapterContent_content]` 内的 `div[data-indent-line]` 段落。 */
    fun chapterTextFromHtml(html: String): String {
        val doc = Jsoup.parse(html)
        val body = doc.selectFirst("div[class*=ChapterContent_content]")
            ?: doc.selectFirst("article[class*=page_article]")
            ?: throw SourceException.BookNotFound
        val paragraphs = body.select("div[data-indent-line]").map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { body.select("p").map { it.text().trim() }.filter { it.isNotEmpty() } }
        if (paragraphs.isEmpty()) throw SourceException.ParseError("本章正文为空，可能已下架或需要登录")
        return paragraphs.joinToString("\n\n")
    }

    // ---------- 字段辅助 ----------

    private fun authorOf(item: JSONObject): String = sequence {
        yield(item.optString("author_name"))
        yield(item.optJSONObject("author")?.optString("pen_name"))
        yield(item.optString("original_author"))
    }.filterNot { it.isNullOrBlank() }.firstOrNull()?.trim().orEmpty()

    private fun tagsOf(item: JSONObject): List<String> {
        val arr = item.optJSONArray("tags") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val value = arr.opt(i) ?: continue
            val name = when (value) {
                is String -> value
                is JSONObject -> value.optString("name")
                else -> null
            } ?: continue
            if (name.isNotBlank()) out.add(name.trim())
        }
        return out.distinct()
    }

    /** is_finished 是搜索条目专属；列表/详情用 status=="completed" 判断，"published" 不能编造成连载。 */
    private fun statusOf(item: JSONObject): String? = when {
        item.has("is_finished") -> if (item.optBoolean("is_finished")) "已完结" else "连载中"
        item.optString("status") == "completed" -> "已完结"
        else -> null
    }

    private fun formatWordCount(count: Long): String? = when {
        count <= 0L -> null
        count >= 10000L -> {
            val wan = count / 10000.0
            val text = "%.1f".format(wan).removeSuffix(".0")
            "${text}万"
        }
        else -> count.toString()
    }

    /** cover_url / 页面图片统一补全成当前线路上的绝对地址。 */
    private fun coverFromApi(item: JSONObject, base: String): String? =
        absolutizeCover(item.optString("cover_url"), base)

    private fun coverFromHtml(root: org.jsoup.nodes.Element, base: String): String? {
        val img = root.selectFirst("img[src]") ?: return null
        return absolutizeCover(img.attr("src").trim(), base)
    }

    private fun absolutizeCover(raw: String, base: String): String? {
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("https://") -> raw
            raw.startsWith("http://") -> raw.replaceFirst("http://", "https://")
            raw.startsWith("/_next/image?url=") -> {
                // /_next/image?url=%2Fapi%2Fv1%2Fmedia%2F{hash}&w=… → 还原出真实媒体路径
                val decoded = runCatching {
                    raw.substringAfter('?').split('&').firstOrNull { it.startsWith("url=") }
                        ?.substringAfter('=')
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                }.getOrNull()
                absolutizeCover(decoded.orEmpty(), base)
            }
            raw.startsWith("/") -> base + raw
            else -> null
        }
    }

    /** 站内作品标识的合法性（纯数字内部 id 或公开书号）。 */
    fun isBookId(raw: String): Boolean = raw.matches(Regex("""\d{1,12}"""))
}
