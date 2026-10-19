package com.example.source.zlibrary

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Keep a search result's identity when the active mirror changes. */
internal object ZLibraryBookReference {
    fun url(bookId: String, domain: String): String {
        val original = bookId.toHttpUrlOrNull()
        val path = original?.encodedPath ?: "/${bookId.trimStart('/')}"
        return "https://$domain".toHttpUrl().newBuilder().encodedPath(path)
            .encodedQuery(original?.encodedQuery).build().toString()
    }

    fun apiKey(bookId: String): Pair<String, String>? {
        val path = bookId.toHttpUrlOrNull()?.encodedPath ?: "/${bookId.trimStart('/')}"
        val match = Regex("^/book/(\\d+)/([a-fA-F0-9]+)(?:/|$)").find(path) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }
}

internal fun shouldRetryZLibraryDownload(message: String): Boolean =
    listOf("上限", "额度", "限制", "limit", "quota", "频繁", "429", "too many requests", "空间", "ENOSPC", "无法保存", "无法替换", "入库")
        .none { message.contains(it, ignoreCase = true) }

internal fun zLibraryDownloadPageError(html: String): String? {
    val text = org.jsoup.Jsoup.parse(html).text().lowercase()
    return when {
        listOf("daily limit", "download limit", "downloads limit", "download quota", "下载上限", "下载限额", "下载次数已达")
            .any { it in text } -> "今日下载额度已用尽，请等待额度重置"
        else -> null
    }
}

internal fun uniqueZLibraryEdition(books: List<com.example.source.SearchBook>, expected: com.example.source.SearchBook,
    format: String?): com.example.source.SearchBook? {
    fun normalized(value: String) = value.filterNot(Char::isWhitespace).lowercase()
    return books.filter { candidate ->
        normalized(candidate.title) == normalized(expected.title) &&
            (expected.author.isBlank() || expected.author == "未知作者" || normalized(candidate.author) == normalized(expected.author)) &&
            (format.isNullOrBlank() || candidate.format.equals(format, true))
    }.distinctBy { it.id }.singleOrNull()
}
