package com.example.source.zlibrary.parser

import com.example.i18n.tr

import com.example.source.SearchBook
import com.example.source.SourceException
import com.example.source.zlibrary.ParsedBookDetail
import com.example.source.zlibrary.guessFileFormatFromUrl
import org.jsoup.nodes.Document

class MobileLayoutParser : ZLibraryLayoutParser {

    override val name: String = "MobileLayoutParser"

    override fun canParseSearch(doc: Document): Boolean {
        return doc.select("z-bookcard, .book-item, .mobile-book-card").isNotEmpty()
    }

    override fun parseSearch(doc: Document, baseUrl: String, sourceId: String): List<SearchBook> {
        val books = mutableListOf<SearchBook>()
        val items = doc.select("z-bookcard, .book-item, .mobile-book-card")

        for (item in items) {
            try {
                val linkEl = item.selectFirst("a[href*=/book/]") ?: continue
                val rawHref = linkEl.attr("href")
                if (rawHref.isBlank()) continue

                val bookId = rawHref.removePrefix("/").trim()
                val title = item.selectFirst(".book-title, h3, .title")?.text() ?: linkEl.text().ifBlank { "未知书名" }
                val author = item.select(".book-author, .author").joinToString(", ") { it.text() }.ifBlank { "未知作者" }

                var cover = CoverExtractor.extract(item)
                if (cover.isNotBlank() && !cover.startsWith("http")) {
                    cover = formatUrl(baseUrl, cover)
                }

                val dlEl = item.selectFirst("a[href*=/dl/], a.btn-primary")
                var downloadUrl: String? = dlEl?.attr("href")
                if (!downloadUrl.isNullOrBlank() && !downloadUrl.startsWith("http")) {
                    downloadUrl = formatUrl(baseUrl, downloadUrl)
                }
                val format = item.selectFirst(".book-property__extension, .extension")
                    ?.text()?.lowercase()?.trim()
                    ?: guessFileFormatFromUrl(downloadUrl)
                    ?: "epub"

                books.add(
                    SearchBook(
                        id = bookId,
                        sourceId = sourceId,
                        title = title.trim(),
                        author = author.trim(),
                        cover = cover,
                        format = format,
                        downloadUrl = downloadUrl
                    )
                )
            } catch (e: Exception) {
                // skip
            }
        }
        return books
    }

    override fun canParseDetail(doc: Document): Boolean {
        return doc.select(".book-title, .book-property__extension, a.btn-primary[href*=/dl/]").isNotEmpty()
    }

    override fun parseDetail(doc: Document, baseUrl: String): ParsedBookDetail {
        val title = doc.selectFirst(".book-title, h1")?.text() ?: "未知书名"
        val author = doc.select(".book-author")?.joinToString(", ") { it.text() }?.ifBlank { "未知作者" } ?: "未知作者"

        var cover = doc.selectFirst("img.cover, img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        if (!cover.isNullOrBlank() && !cover.startsWith("http")) {
            cover = formatUrl(baseUrl, cover)
        }

        val dlAnchor = doc.firstRealDownloadLink()
            ?: throw SourceException.ParseError(tr("未找到移动端下载按钮"))

        var dlUrl = dlAnchor.attr("href")
        if (dlUrl.isBlank()) throw SourceException.ParseError(tr("下载链接为空"))
        if (!dlUrl.startsWith("http")) dlUrl = formatUrl(baseUrl, dlUrl)

        val formatText = doc.selectFirst(".book-property__extension")?.text()
            ?: guessFileFormatFromUrl(dlUrl)
            ?: "epub"

        return ParsedBookDetail(
            title = title.trim(),
            author = author.trim(),
            cover = cover,
            downloadUrl = dlUrl,
            format = formatText.lowercase().trim()
        )
    }

    private fun formatUrl(baseUrl: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        if (path.startsWith("//")) return "https:$path"
        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        return "$cleanBase/$cleanPath"
    }
}
