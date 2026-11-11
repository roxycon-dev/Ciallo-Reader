package com.example.source.zlibrary.parser

import com.example.i18n.tr

import com.example.source.SearchBook
import com.example.source.SourceException
import com.example.source.zlibrary.ParsedBookDetail
import com.example.source.zlibrary.guessFileFormatFromUrl
import org.jsoup.nodes.Document

class LegacyLayoutParser : ZLibraryLayoutParser {

    override val name: String = "LegacyLayoutParser"

    override fun canParseSearch(doc: Document): Boolean {
        return doc.select("a[href*=/book/]").isNotEmpty()
    }

    override fun parseSearch(doc: Document, baseUrl: String, sourceId: String): List<SearchBook> {
        val books = mutableListOf<SearchBook>()
        val bookLinks = doc.select("a[href*=/book/]")

        for (link in bookLinks) {
            try {
                val rawHref = link.attr("href")
                if (rawHref.isBlank()) continue

                val bookId = rawHref.removePrefix("/").trim()
                val title = link.text().ifBlank { "未知书名" }
                if (title.length < 2) continue

                val parent = link.parent()
                val author = parent?.select("a[href*=/g/]")?.joinToString(", ") { it.text() }?.ifBlank { "未知作者" } ?: "未知作者"

                var cover = parent?.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }
                }
                if (!cover.isNullOrBlank() && !cover.startsWith("http")) {
                    cover = formatUrl(baseUrl, cover)
                }

                val dlEl = parent?.selectFirst("a[href*=/dl/]")
                var downloadUrl: String? = dlEl?.attr("href")
                if (!downloadUrl.isNullOrBlank() && !downloadUrl.startsWith("http")) {
                    downloadUrl = formatUrl(baseUrl, downloadUrl)
                }
                val format = guessFileFormatFromUrl(downloadUrl)
                    ?: parent?.selectFirst(
                        ".property_extension, .extension, [class*=extension], [class*=format], [class*=file-type]"
                    )?.text()?.lowercase()?.trim()
                    ?: dlEl?.text()?.lowercase()?.trim()?.takeIf { it.length in 1..8 }
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
        return books.distinctBy { it.id }
    }

    override fun canParseDetail(doc: Document): Boolean {
        return doc.select("a[href*=/dl/]").isNotEmpty()
    }

    override fun parseDetail(doc: Document, baseUrl: String): ParsedBookDetail {
        val dlAnchor = doc.firstRealDownloadLink()
            ?: throw SourceException.ParseError(tr("未找到通用下载链接"))

        var dlUrl = dlAnchor.attr("href")
        if (!dlUrl.startsWith("http")) dlUrl = formatUrl(baseUrl, dlUrl)

        val title = doc.title().ifBlank { "未知书名" }

        return ParsedBookDetail(
            title = title,
            author = "未知作者",
            cover = null,
            downloadUrl = dlUrl,
            format = guessFileFormatFromUrl(dlUrl)
                ?: doc.selectFirst(
                    ".property_extension, .extension, [class*=extension], [class*=format], [class*=file-type]"
                )?.text()?.lowercase()?.trim()
                ?: "epub"
        )
    }

    private fun formatUrl(baseUrl: String, path: String): String {
        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        return "$cleanBase/$cleanPath"
    }
}
