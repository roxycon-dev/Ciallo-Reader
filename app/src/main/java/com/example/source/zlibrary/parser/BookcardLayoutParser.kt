package com.example.source.zlibrary.parser

import com.example.i18n.tr

import com.example.source.SearchBook
import com.example.source.zlibrary.ParsedBookDetail
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 新版 Z-Library 镜像布局（2025+ web-component 版本）。
 * 搜索页：每个结果是一个 <z-bookcard href="/book/xxx" download="/dl/xxx" extension="epub" filesize="9.40 MB">
 *         内含 <div slot="title">书名</div> / <div slot="author">作者</div> / <img data-src="封面">
 * 详情页：<h1 class="book-title" itemprop="name"> + <i class="authors"><a>作者</a></i> + a.dlButton[href*=/dl/]
 * 实测节点：zh.101k.by / tw.101k.by（101z.by 入口会 302 跳到 101k.by）
 */
class BookcardLayoutParser : ZLibraryLayoutParser {

    override val name: String = "BookcardLayoutParser"

    override fun canParseSearch(doc: Document): Boolean {
        return doc.select("z-bookcard[href]").isNotEmpty()
    }

    override fun parseSearch(doc: Document, baseUrl: String, sourceId: String): List<SearchBook> {
        val books = mutableListOf<SearchBook>()
        val items = doc.select("z-bookcard[href]")
        for (item in items) {
            try {
                val rawHref = item.attr("href")
                if (rawHref.isBlank() || !rawHref.contains("/book/")) continue

                val title = item.selectFirst("[slot=title]")?.text()?.trim()
                    ?: item.attr("title").trim().ifBlank { "未知书名" }
                val author = item.selectFirst("[slot=author]")?.let {
                    it.text().trim().ifBlank { null }
                }
                    ?: item.attr("author").trim().ifBlank { null }
                    ?: "未知作者"

                var cover = item.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                } ?: ""
                if (cover.isNotBlank() && !cover.startsWith("http")) {
                    cover = formatUrl(baseUrl, cover)
                }

                var downloadUrl: String? = item.attr("download").ifBlank { null }
                if (!downloadUrl.isNullOrBlank() && !downloadUrl.startsWith("http")) {
                    downloadUrl = formatUrl(baseUrl, downloadUrl)
                }

                val format = item.attr("extension").trim().lowercase().ifBlank { "epub" }
                val sizeText = item.attr("filesize").trim()
                val size = parseFileSize(sizeText)

                books.add(
                    SearchBook(
                        id = rawHref.removePrefix("/").trim(),
                        sourceId = sourceId,
                        title = title,
                        author = author,
                        cover = cover,
                        format = format,
                        size = size,
                        downloadUrl = downloadUrl
                    )
                )
            } catch (e: Exception) {
                // skip corrupted item
            }
        }
        return books
    }

    override fun canParseDetail(doc: Document): Boolean {
        // 详情页特征：z-cover 组件或 h1.book-title
        return doc.select("z-cover").isNotEmpty() || doc.selectFirst("h1.book-title") != null
    }

    override fun parseDetail(doc: Document, baseUrl: String): ParsedBookDetail {
        val title = doc.selectFirst("h1.book-title, h1[itemprop=name], h1")?.text()?.trim()
            ?: doc.selectFirst("z-cover")?.attr("title")?.trim()
            ?: "未知书名"

        // 新版：作者在 <i class="authors"><a>天蚕土豆</a></i>；也可能在 z-cover author 属性
        val author = doc.select("i.authors a, .bookAuthor a, a[itemprop=author]")
            .joinToString(", ") { it.text().trim() }
            .ifBlank {
                doc.selectFirst("z-cover")?.attr("author")?.trim().orEmpty()
            }
            .ifBlank { "未知作者" }

        var cover: String? = doc.selectFirst("z-cover img, .details-book-cover-container img, img.cover, img[itemprop=image]")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        if (!cover.isNullOrBlank() && !cover.startsWith("http")) {
            cover = formatUrl(baseUrl, cover)
        }

        val dlAnchor = doc.firstRealDownloadLink()
            ?: doc.selectFirst("a.dlButton[href*=/dl/]")
            ?: throw com.example.source.SourceException.ParseError(tr("未找到下载链接"))
        var dlUrl = dlAnchor.attr("href")
        if (dlUrl.isBlank()) throw com.example.source.SourceException.ParseError(tr("解析下载链接为空"))
        if (!dlUrl.startsWith("http")) dlUrl = formatUrl(baseUrl, dlUrl)

        // 新版格式在按钮附近：.book-property__extension 或 z-bookcard extension 属性；
        // 旧版：.property_extension
        val format = doc.selectFirst(".book-property__extension, .property_extension")
            ?.text()?.trim()?.lowercase()
            ?: doc.selectFirst("z-bookcard")?.attr("extension")?.trim()?.lowercase()
            ?: guessFileFormatFromUrl(dlUrl)
            ?: "epub"

        return ParsedBookDetail(
            title = title,
            author = author,
            cover = cover,
            downloadUrl = dlUrl,
            format = format
        )
    }

    /** "9.40 MB" / "604 KB" -> 字节数 */
    private fun parseFileSize(text: String): Long? {
        if (text.isBlank()) return null
        val m = Regex("""([\d.]+)\s*(B|KB|MB|GB|TB)""", RegexOption.IGNORE_CASE).find(text)
            ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].uppercase()
        val multiplier = when (unit) {
            "B" -> 1.0; "KB" -> 1024.0; "MB" -> 1024.0 * 1024.0
            "GB" -> 1024.0 * 1024.0 * 1024.0; "TB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> return null
        }
        return (value * multiplier).toLong().takeIf { it > 0 }
    }

    /** 从下载 URL 后缀猜测文件格式，猜不出返回 null */
    private fun guessFileFormatFromUrl(url: String): String? {
        val m = Regex("""\.([a-z0-9]{2,5})(?:[?#]|$)""", RegexOption.IGNORE_CASE).find(url)
            ?: return null
        return m.groupValues[1].lowercase()
    }

    private fun formatUrl(baseUrl: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        if (path.startsWith("//")) return "https:$path"
        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        return "$cleanBase/$cleanPath"
    }
}
