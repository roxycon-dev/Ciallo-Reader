package com.example.library

import android.net.Uri

/** Offline detail links for sources without a public website. No account data is included. */
object SharedWorkLink {
    data class Target(val sourceId: String, val bookId: String, val title: String)

    fun create(sourceId: String, bookId: String, title: String): String {
        require(sourceId.isNotBlank() && bookId.isNotBlank()) { "作品来源或编号缺失" }
        require(sourceId.length <= 256 && bookId.length <= 4096)
        require((sourceId + bookId).none(Char::isISOControl))
        require(Uri.parse(bookId).userInfo == null) { "作品链接包含账号信息，无法分享" }
        return Uri.Builder().scheme("ciallo").authority("detail")
            .appendQueryParameter("source", sourceId).appendQueryParameter("id", bookId)
            .appendQueryParameter("title", title.take(300)).build().toString()
    }

    fun parse(uri: Uri?): Target? = runCatching {
        if (uri == null || uri.scheme != "ciallo" || uri.host != "detail" ||
            !uri.path.isNullOrEmpty() || uri.fragment != null || uri.userInfo != null || uri.port != -1 ||
            uri.toString().length > 20_000) return null
        if (uri.queryParameterNames != setOf("source", "id", "title")) return null
        if (uri.queryParameterNames.any { uri.getQueryParameters(it).size != 1 }) return null
        val target = Target(uri.getQueryParameter("source").orEmpty(),
            uri.getQueryParameter("id").orEmpty(), uri.getQueryParameter("title").orEmpty())
        create(target.sourceId, target.bookId, target.title)
        target
    }.getOrNull()
}
