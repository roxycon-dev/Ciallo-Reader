package com.example.source

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Validate public links before passing them to other apps. Relative paths need a source base. */
object DetailLink {
    fun valid(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: return null
        val url = text.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return url.toString()
    }

    fun resolve(raw: String?, base: String): String? {
        valid(raw)?.let { return it }
        val path = raw?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: return null
        return valid(base.toHttpUrlOrNull()?.resolve(path)?.toString())
    }
}
