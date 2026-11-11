package com.example.source.zlibrary

import com.example.i18n.tr

import com.example.source.SourceException
import okhttp3.Response
import org.jsoup.nodes.Document

object ZLibraryAccessChecker {

    fun checkResponse(response: Response, htmlDoc: Document? = null) {
        if (response.code == 403 || response.code == 503) {
            val serverHeader = response.header("Server") ?: ""
            if (serverHeader.contains("cloudflare", ignoreCase = true)) {
                throw SourceException.NetworkError("触发 Cloudflare 防火墙保护 (HTTP ${response.code})")
            }
        }

        if (htmlDoc != null) {
            val title = htmlDoc.title()
            if (title.contains("Just a moment", ignoreCase = true) ||
                title.contains("Attention Required", ignoreCase = true) ||
                title.contains("Cloudflare", ignoreCase = true)
            ) {
                throw SourceException.NetworkError(tr("触发 Cloudflare 人机验证，请更换节点或检查网页访问状态"))
            }

            if (htmlDoc.select("form[action*=/login]").isNotEmpty() &&
                htmlDoc.select("input[name=email]").isNotEmpty() &&
                htmlDoc.select(".book-item, z-bookcard, .resItemBox, #booksList").isEmpty()
            ) {
                if (title.contains("Login", ignoreCase = true) || title.contains("Sign in", ignoreCase = true)) {
                    throw SourceException.LoginRequired
                }
            }
        }
    }
}
