package com.example.source.impl

import com.example.source.ComicChapter
import com.example.source.NovelInfo
import com.example.source.SearchBook
import com.example.source.SourceException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 爱丽丝书屋（alicesw）页面解析。
 *
 * 站点是传统服务端渲染的 PHP 站点，搜索/详情/目录/正文都能直接拿完整 HTML，
 * 正文免登录。作品页 `/novel/{作品id}.html`，目录页 `/other/chapters/id/{作品id}.html`，
 * 正文页 `/book/{书库id}/{章节hash}.html`——注意目录页用的是作品 id，正文链接里的是
 * 书库 id，两者不同，所以章节标识保存为 `书库id/章节hash`。
 *
 * 解析与网络分离，便于用真实页面快照做离线回归（站点改版时这些用例先红）。
 */
internal object AliceSwParser {

    private val NOVEL_HREF = Regex("""/novel/(\d{1,9})\.html""")
    private val CHAPTER_HREF = Regex("""/book/(\d{1,9})/([0-9a-f]{8,16})\.html""")
    private val WORD_COUNT = Regex("""字数：\s*([0-9]+(?:\.[0-9]+)?万?)""")
    private val UPDATED_AT = Regex("""更新时间：\s*([0-9]{4}-[0-9]{2}-[0-9]{2})""")
    private val TAG_PREFIX = Regex("""^#\s*""")
    private val LIST_INDEX_PREFIX = Regex("""^\d+[.、]\s*""")

    /** 搜索结果列表；关键字命中高亮用 `<em>` 包住，text() 后自然还原。 */
    fun books(html: String, sourceId: String): List<SearchBook> {
        val doc = Jsoup.parse(html)
        val items = doc.select("div.list-group div.list-group-item")
        val books = mutableListOf<SearchBook>()
        for (item in items) {
            val link = item.selectFirst("h5 a[href]") ?: continue
            val bookId = NOVEL_HREF.find(link.attr("href"))?.groupValues?.get(1) ?: continue
            // 列表标题带序号前缀（"1. xxx"），去掉后再入库，否则书名会被污染。
            val title = link.text().trim().replace(LIST_INDEX_PREFIX, "").trim()
            if (title.isEmpty()) continue
            val meta = item.selectFirst("p.mb-1")?.text().orEmpty()
            val author = item.selectFirst("p.mb-1 a[href*=f=author]")?.text()?.trim().orEmpty()
            val intro = item.selectFirst("p.content-txt")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val tags = tagsOf(item.select("p.text-muted a[href]"))
            val updatedAt = UPDATED_AT.find(item.selectFirst("p.timedesc")?.text().orEmpty())
                ?.groupValues?.get(1)
            books += SearchBook(
                id = bookId,
                sourceId = sourceId,
                title = title,
                author = author.ifBlank { "未知作者" },
                description = intro,
                format = "epub",
                language = "中文",
                novelInfo = NovelInfo(
                    synopsis = intro,
                    status = statusOf(item.selectFirst("h5 small")?.text()),
                    updatedAt = updatedAt,
                    wordCount = WORD_COUNT.find(meta)?.groupValues?.get(1),
                    tags = tags,
                    translation = "中文"
                )
            )
        }
        return books.distinctBy { it.id }
    }

    /** 作品详情页：书名、作者、分类、字数、章节数、最新章节、标签与简介。 */
    fun detail(html: String, bookId: String, sourceId: String): SearchBook {
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("div.novel_title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("div.mu_h1 h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw SourceException.ParseError("爱丽丝书屋未返回作品标题，页面可能已变化")
        val info = infoFields(doc)
        val synopsis = doc.selectFirst("div.jianjie p")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("div.jianjie")?.text()?.trim()
                ?.removePrefix("内容简介：")?.takeIf { it.isNotEmpty() }
        val latestRow = info["最新"].orEmpty()
        val latestUpdatedAt = doc.selectFirst("div.book_newchap div.con li em")
            ?.text()?.let { UPDATED_AT.find(it)?.groupValues?.get(1) }
            ?.takeIf { it.isNotEmpty() } ?: ""
        val tags = tagsOf(doc.select("div.tags_list a[href]"))
        val chapterCount = info["章节"]?.toIntOrNull()
        return SearchBook(
            id = bookId,
            sourceId = sourceId,
            title = title,
            author = info["作者"].orEmpty().ifBlank { "未知作者" },
            cover = coverOf(doc),
            description = synopsis,
            format = "epub",
            language = "中文",
            novelInfo = NovelInfo(
                synopsis = synopsis,
                status = info["状态"]?.takeIf { it.isNotBlank() },
                category = info["分类"]?.takeIf { it.isNotBlank() },
                latestChapter = latestRow.ifBlank { null },
                updatedAt = latestUpdatedAt.ifBlank { null },
                chapterCount = chapterCount?.takeIf { it > 0 },
                wordCount = info["字数"]?.takeIf { it.isNotBlank() },
                tags = tags,
                translation = "中文",
                notice = "正文免登录公开；整本下载会逐章抓取并复核目录，任何一章失败都不会标记完成。"
            )
        )
    }

    /** 目录页：`ul.mulu_list` 里的顺序就是阅读顺序，order 直接取列表位置。 */
    fun chapters(html: String, sourceId: String): List<ComicChapter> {
        val doc = Jsoup.parse(html)
        val chapters = mutableListOf<ComicChapter>()
        for (link in doc.select("ul.mulu_list li a[href]")) {
            val match = CHAPTER_HREF.find(link.attr("href")) ?: continue
            val title = link.text().trim().ifBlank { "第 ${chapters.size + 1} 节" }
            chapters += ComicChapter(
                id = "${match.groupValues[1]}/${match.groupValues[2]}",
                title = title,
                order = (chapters.size + 1).toFloat()
            )
        }
        return chapters.distinctBy { it.id }
    }

    /** 正文页：`div.read-content` 内的 `<p>` 段落，用空行拼接成纯文本。 */
    fun chapterText(html: String): String {
        val doc = Jsoup.parse(html)
        val body = doc.selectFirst("div.read-content")
            ?: throw SourceException.BookNotFound
        val paragraphs = body.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) {
            throw SourceException.ParseError("本章正文为空，可能已下架或需要登录")
        }
        return paragraphs.joinToString("\n\n")
    }

    /** 详情页信息行：同一行可能用 `·` 并排两个字段（如「字 数：12.12万 · 章 节：11」）。 */
    private fun infoFields(doc: Document): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        for (row in doc.select("div.novel_info p")) {
            for (segment in row.text().split("·")) {
                val index = segment.indexOf('：')
                if (index <= 0) continue
                val key = segment.substring(0, index)
                    .replace(" ", "").replace("\u00a0", "").trim()
                val value = segment.substring(index + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) fields[key] = value
            }
        }
        return fields
    }

    /** 只认站内的标签链接（`?f=tag`）；列表项里的作者链接同样是 text-muted 子节点。 */
    private fun tagsOf(links: List<Element>): List<String> = links.mapNotNull { link ->
        if (!link.attr("href").contains("f=tag")) return@mapNotNull null
        val text = link.text().trim().replace(TAG_PREFIX, "").trim()
        text.takeIf { it.isNotEmpty() }
    }.distinct()

    private fun statusOf(raw: String?): String? = raw?.trim()
        ?.removeSurrounding("[", "]")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun coverOf(doc: Document): String? {
        val img = doc.selectFirst("div.box_intro img") ?: return null
        val raw = img.attr("data-src").ifBlank { img.attr("src") }.trim()
        if (raw.isEmpty()) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") -> raw.replaceFirst("http://", "https://")
            raw.startsWith("https://") -> raw
            else -> null
        }
    }

    /** 站点列表页/详情页里作品 id 的合法性（纯数字作品号）。 */
    fun isBookId(raw: String): Boolean = raw.matches(Regex("""\d{1,9}"""))

    /** 章节标识必须是 `书库id/章节hash`，避免把站外地址塞进下载链路。 */
    fun parseChapterId(raw: String): Pair<String, String> {
        val match = Regex("""(\d{1,9})/([0-9a-f]{8,16})""").matchEntire(raw.trim())
            ?: throw SourceException.ParseError("爱丽丝书屋章节标识无效")
        return match.groupValues[1] to match.groupValues[2]
    }
}
