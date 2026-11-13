package com.example.source.zlibrary.parser

import com.example.i18n.tr

import com.example.source.SearchBook
import com.example.source.SourceException
import com.example.source.zlibrary.ParsedBookDetail
import com.example.source.zlibrary.guessFileFormatFromUrl
import org.jsoup.nodes.Document

class DesktopLayoutParser : ZLibraryLayoutParser {

    override val name: String = "DesktopLayoutParser"

    override fun canParseSearch(doc: Document): Boolean {
        return doc.select(".resItemBox, tr.bookRow, #booksList .item").isNotEmpty()
    }

    override fun parseSearch(doc: Document, baseUrl: String, sourceId: String): List<SearchBook> {
        val books = mutableListOf<SearchBook>()
        val items = doc.select(".resItemBox, tr.bookRow, #booksList .item")

        for (item in items) {
            try {
                val linkEl = item.selectFirst("a[href*=/book/]") ?: item.selectFirst("a[href*=/s/]") ?: continue
                val rawHref = linkEl.attr("href")
                if (rawHref.isBlank()) continue

                val bookId = rawHref.removePrefix("/").trim()
                val title = linkEl.text().ifBlank {
                    item.selectFirst("h3, .title, td[itemprop=name]")?.text() ?: "未知书名"
                }

                val author = item.select("div.authors a, a[itemprop=author], .book-author, td[itemprop=author]")
                    .joinToString(", ") { it.text() }
                    .ifBlank { "未知作者" }

                var cover = CoverExtractor.extract(item)
                if (cover.isNotBlank() && !cover.startsWith("http")) {
                    cover = formatUrl(baseUrl, cover)
                }

                val dlEl = item.selectFirst("a[href*=/dl/], a.add_to_download_history, a.dlButton")
                var downloadUrl: String? = dlEl?.attr("href")
                if (!downloadUrl.isNullOrBlank() && !downloadUrl.startsWith("http")) {
                    downloadUrl = formatUrl(baseUrl, downloadUrl)
                }
                val formatEl = item.selectFirst(
                    ".property_extension, .property__file .property__value, td.property_extension"
                )
                val format = formatEl?.text()?.lowercase()?.trim()
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
                // skip corrupted item
            }
        }
        return books
    }

    override fun canParseDetail(doc: Document): Boolean {
        return doc.select("h1[itemprop=name], a.add_to_download_history, a.dlButton").isNotEmpty()
    }

    override fun parseDetail(doc: Document, baseUrl: String): ParsedBookDetail {
        val title = doc.selectFirst("h1[itemprop=name], h1.title")?.text() ?: "未知书名"
        val author = doc.select("a[itemprop=author]")?.joinToString(", ") { it.text() }?.ifBlank { "未知作者" } ?: "未知作者"

        var cover = doc.selectFirst("img.cover, img[itemprop=image]")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        if (!cover.isNullOrBlank() && !cover.startsWith("http")) {
            cover = formatUrl(baseUrl, cover)
        }

        val dlAnchor = doc.firstRealDownloadLink()
            ?: throw SourceException.ParseError(tr("未找到下载链接"))

        var dlUrl = dlAnchor.attr("href")
        if (dlUrl.isBlank()) throw SourceException.ParseError(tr("解析下载链接为空"))
        if (!dlUrl.startsWith("http")) dlUrl = formatUrl(baseUrl, dlUrl)

        val formatText = doc.selectFirst(".property_extension, div:contains(Extension) + div")?.text()
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
