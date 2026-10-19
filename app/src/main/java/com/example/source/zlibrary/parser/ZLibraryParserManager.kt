package com.example.source.zlibrary.parser

import android.util.Log
import com.example.source.SearchBook
import com.example.source.SourceException
import com.example.source.zlibrary.ParsedBookDetail
import com.example.source.zlibrary.ZLibraryAccessChecker
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object ZLibraryParserManager {

    private const val TAG = "ZLibraryParserManager"

    private val parsers: List<ZLibraryLayoutParser> = listOf(
        BookcardLayoutParser(),
        DesktopLayoutParser(),
        MobileLayoutParser(),
        LegacyLayoutParser(),
        GenericFallbackParser()
    )

    fun detectLayoutName(html: String): String {
        val doc = Jsoup.parse(html)
        val selectedParser = parsers.firstOrNull { it.canParseSearch(doc) }
        return selectedParser?.name ?: "Unknown / Default"
    }

    fun parseSearchPage(html: String, baseUrl: String, sourceId: String = "zlibrary"): List<SearchBook> {
        val doc = Jsoup.parse(html)
        ZLibraryAccessChecker.checkResponse(fakeResponse(), doc)

        // Some mirrors insert an explicitly hidden bookcard reflecting the query,
        // with a random id/format and an empty download endpoint. It is not a visible
        // search result. Remove hidden subtrees before layout selection so the generic
        // fallback cannot reintroduce the same decoy via its /book/ link.
        doc.select("[hidden], [aria-hidden=true]").remove()
        doc.select("[style]").filter { element ->
            Regex("(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)\\s*(?:!important)?\\s*(?:;|$)",
                RegexOption.IGNORE_CASE).containsMatchIn(element.attr("style"))
        }.forEach { it.remove() }

        val selectedParser = parsers.firstOrNull { it.canParseSearch(doc) }
            ?: parsers.last()

        Log.i(TAG, "Selected search parser: ${selectedParser.name}")

        val results = selectedParser.parseSearch(doc, baseUrl, sourceId)
        Log.i(TAG, "Parsed ${results.size} books using ${selectedParser.name}")
        if (results.isEmpty() && selectedParser !is GenericFallbackParser) {
            Log.i(TAG, "Selected parser returned 0 books; falling back to GenericFallbackParser")
            return GenericFallbackParser().parseSearch(doc, baseUrl, sourceId)
        }
        return results
    }

    fun parseDetailPage(html: String, baseUrl: String): ParsedBookDetail {
        val doc = Jsoup.parse(html)
        ZLibraryAccessChecker.checkResponse(fakeResponse(), doc)

        val selectedParser = parsers.firstOrNull { it.canParseDetail(doc) }
            ?: parsers.last()

        Log.i(TAG, "Selected detail parser: ${selectedParser.name}")

        return selectedParser.parseDetail(doc, baseUrl)
    }

    private fun fakeResponse(): okhttp3.Response {
        return okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://example.com").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()
    }
}
