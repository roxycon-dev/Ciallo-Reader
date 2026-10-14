package com.example.source

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.charset.Charset
import com.example.data.readImportBytes
import okhttp3.Response

internal fun Response.readSourceText(maxBytes: Int = 2 * 1024 * 1024, fallback: String? = null): String {
    val bytes = body?.byteStream()?.use { it.readImportBytes(maxBytes) } ?: ByteArray(0)
    return SourceResponse.text(bytes, headers.toMultimap().mapValues { it.value.joinToString("; ") }, fallback)
}

/** Shared by native sources and the JS HTTP bridge. Never include response bodies in errors. */
internal object SourceResponse {
    /** Mixed HTML/JSON Accept headers belong to web adapters as well as APIs. */
    fun expectsJson(headers: Map<String, String>): Boolean {
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value.orEmpty()
        return accept.contains("json", true) && !accept.contains("text/html", true) && !accept.contains("application/xhtml", true)
    }
    fun text(bytes: ByteArray, headers: Map<String, String> = emptyMap(), fallback: String? = null): String {
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value.orEmpty()
        val prefix = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val charsetName = Regex("charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
            ?: (if (prefix.trimStart().startsWith('<')) Regex("charset\\s*=\\s*[\"']?\\s*(gbk|gb2312|gb18030)", RegexOption.IGNORE_CASE)
                .find(prefix)?.groupValues?.get(1) else null)
            ?: fallback
        val charset = when {
            bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> Charsets.UTF_8
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            charsetName != null -> runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
            else -> Charsets.UTF_8
        }
        val text = if (charset == Charsets.UTF_8 && charsetName == null) {
            try {
                Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                String(bytes, Charset.forName("GB18030"))
            }
        } else String(bytes, charset)
        return text.removePrefix("\uFEFF")
    }

    fun problem(text: String, expectJson: Boolean = false): String? {
        val body = text.trimStart { it.isWhitespace() || it == '\uFEFF' }
        if (body.isEmpty()) return "源站返回空响应，请稍后重试"
        if (!body.startsWith('<')) return null
        // Only inspect actual HTML. Keywords in valid JSON or ordinary book text are harmless.
        val sample = body.take(32_768)
        if (Regex("<title[^>]*>\\s*(Just a moment|Attention Required|Access denied|Forbidden|Service Unavailable)", RegexOption.IGNORE_CASE).containsMatchIn(sample) ||
            sample.contains("/cdn-cgi/challenge-platform/", true))
            return "源站返回验证或拦截页面，请在书源管理完成站点验证或稍后重试"
        return if (expectJson) "源站返回网页而非接口数据，请稍后重试或切换该源线路" else null
    }

    fun jsonRoot(text: String): Any {
        problem(text, expectJson = true)?.let { throw SourceException.ParseError(it) }
        val normalized = text.trim { it.isWhitespace() || it == '\uFEFF' }
        return try {
            val tokener = JSONTokener(normalized)
            val root = tokener.nextValue()
            if ((root !is JSONObject && root !is JSONArray) || tokener.nextClean() != '\u0000')
                throw SourceException.ParseError("接口数据结构异常，预期为 JSON 对象或数组")
            root
        } catch (e: SourceException) { throw e }
        catch (_: Exception) { throw SourceException.ParseError("接口数据不完整或 JSON 格式异常，请稍后重试") }
    }

    fun jsonObject(text: String): JSONObject = jsonRoot(text) as? JSONObject
        ?: throw SourceException.ParseError("接口数据结构异常，预期为 JSON 对象")
}
