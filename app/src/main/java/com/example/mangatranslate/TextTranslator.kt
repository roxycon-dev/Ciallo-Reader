package com.example.mangatranslate

import com.example.i18n.tr

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import com.example.source.executeCancellable
import com.example.data.readImportBytes
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 文本翻译引擎接口：目标语言恒为简体中文。 */
interface TextTranslator {
    /** 翻译单段文本；失败返回 null（调用方保留原文显示）。 */
    suspend fun translate(text: String, sourceLang: String): String?

    /** 预热（语言包下载等），返回 null=成功 或 失败原因。 */
    suspend fun prepare(sourceLang: String): String?

    fun close()
}

/** 语言检测（纯函数，供单测）：按文字系统启发式。 */
object ScriptDetector {
    /** 返回语言码：ja / en / ko / zh；无法判断返回 null。 */
    fun detect(text: String): String? {
        var kana = 0
        var hangul = 0
        var latin = 0
        var cjk = 0
        for (ch in text) {
            when {
                ch.code in 0x3040..0x30FF || ch.code == 0x31F0 || ch.code == 0x31F1 -> kana++
                ch.code in 0xAC00..0xD7A3 || ch.code in 0x1100..0x11FF -> hangul++
                ch.code in 0x61..0x7A || ch.code in 0x41..0x5A -> latin++
                ch.code in 0x4E00..0x9FFF -> cjk++
            }
        }
        val total = (kana + hangul + latin + cjk).coerceAtLeast(1)
        return when {
            kana > 0 && kana * 3 >= total / 4 -> "ja"
            hangul > 0 && hangul * 2 >= total -> "ko"
            latin * 2 >= total -> "en"
            // 纯汉字按中文跳过翻译（第十九轮实测：中文电子书被误判 ja 会导致乱序覆盖）。
            // 无假名的纯日文极罕见——漫画对白几乎必带假名，误跳过的代价远小于错翻。
            cjk * 2 >= total -> "zh"
            else -> null
        }
    }
}

/** Anonymous online translation with bounded batches and repeated-dialogue caching. */
class OnlineFallbackTranslator(
    private val context: android.content.Context? = null,
    private val clientOverride: OkHttpClient? = null,
) : TextTranslator {
    private val client by lazy {
        clientOverride ?: com.example.source.SharedHttpTransport.builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    }
    private val sentences = android.util.LruCache<String, String>(512)
    private val requests = Semaphore(2)
    @Volatile private var myMemoryRetryAt = 0L
    @Volatile var lastFailure: TranslationFailure? = null
        private set

    override suspend fun prepare(sourceLang: String): String? = null
    override suspend fun translate(text: String, sourceLang: String): String? =
        translateBatch(listOf(text), sourceLang)?.firstOrNull()?.takeIf { it.isNotBlank() }

    suspend fun translateBatch(texts: List<String>, sourceLang: String): List<String>? =
        translateBatchResult(texts, sourceLang).let {
            lastFailure = it.failure
            it.texts.takeIf { lines -> lines.isEmpty() || lines.any(String::isNotBlank) }
        }

    internal data class BatchResult(val texts: List<String>, val failure: TranslationFailure? = null)

    internal suspend fun translateBatchResult(texts: List<String>, sourceLang: String): BatchResult = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext BatchResult(emptyList())
        if (context != null && !TranslationPrivacy.allowed(context)) {
            return@withContext BatchResult(List(texts.size) { "" }, TranslationFailure.Privacy)
        }
        require(texts.size <= 500 && texts.sumOf { it.length } <= 128_000) { tr("翻译请求过大") }
        if (sourceLang == "zh") return@withContext BatchResult(texts)
        val normalized = texts.map { it.trim().replace(lineBreaks, " ") }
        val unique = normalized.filter { it.isNotBlank() }.distinct()
        val missing = unique.filter { sentences.get("$sourceLang|$it") == null }
        val chunks = batches(missing)
        val failures = withTimeoutOrNull(12_000L) {
            coroutineScope {
                chunks.map { batch ->
                    async {
                        requests.withPermit {
                            val result = requestBatch(batch, sourceLang)
                            result.texts.forEachIndexed { i, translated ->
                                if (translated.isNotBlank()) sentences.put("$sourceLang|${batch[i]}", translated)
                            }
                            result.failure
                        }
                    }
                }.awaitAll()
            }
        }
        val output = normalized.map { sentences.get("$sourceLang|$it").orEmpty() }
        val failure = if (normalized.indices.any { normalized[it].isNotBlank() && output[it].isBlank() }) {
            if (failures == null) TranslationFailure.FreeTimeout
            else failures.firstOrNull { it != null } ?: TranslationFailure.FreeEmpty
        } else null
        BatchResult(output, failure)
    }

    private fun batches(texts: List<String>): List<List<String>> {
        val batches = mutableListOf<List<String>>()
        val pending = mutableListOf<String>()
        var pendingBytes = 0
        for (text in texts) {
            val size = text.toByteArray(Charsets.UTF_8).size
            if (pending.isNotEmpty() && (pending.size == 24 || pendingBytes + numberedLineBytes(pending.size, size) + 1 > 500)) {
                batches.add(pending.toList())
                pending.clear()
                pendingBytes = 0
            }
            if (size > 500) {
                if (text.length <= 6000) batches.add(listOf(text))
            } else {
                pendingBytes += numberedLineBytes(pending.size, size) + if (pending.isEmpty()) 0 else 1
                pending.add(text)
            }
        }
        if (pending.isNotEmpty()) batches.add(pending.toList())
        return batches
    }

    private suspend fun requestBatch(texts: List<String>, sourceLang: String): BatchResult {
        val query = if (texts.size == 1) texts.single() else texts.mapIndexed { index, text ->
            memoryPrefix(index) + text
        }.joinToString("\n")
        val primary = when {
            android.os.SystemClock.elapsedRealtime() < myMemoryRetryAt ->
                BatchResult(emptyList(), TranslationFailure.FreeLimited)
            sourceLang in supportedLanguages && query.toByteArray(Charsets.UTF_8).size <= 500 ->
                requestMyMemory(query, texts, sourceLang)
            else -> BatchResult(emptyList())
        }
        val remaining = texts.indices.filter { primary.texts.getOrNull(it).isNullOrBlank() }
        if (remaining.isEmpty() || primary.failure == TranslationFailure.Privacy) return primary
        val backup = requestTencent(remaining.map(texts::get), sourceLang)
        val output = MutableList(texts.size) { primary.texts.getOrNull(it).orEmpty() }
        remaining.zip(backup.texts).forEach { (index, text) -> output[index] = text }
        val failure = if (output.any(String::isBlank)) backup.failure?.takeIf { it == TranslationFailure.Privacy }
            ?: primary.failure ?: backup.failure ?: TranslationFailure.FreeEmpty else null
        return BatchResult(output, failure)
    }

    private suspend fun requestMyMemory(query: String, texts: List<String>, language: String): BatchResult {
        val url = "https://api.mymemory.translated.net/get".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("langpair", "$language|zh-CN")
            .build()
        val result = request(Request.Builder().url(url).build()) { raw ->
            val json = JSONObject(raw)
            val status = json.optInt("responseStatus")
            when {
                json.optBoolean("quotaFinished") || status == 429 -> throw TranslationFailure.FreeLimited.exception()
                status != 200 -> throw freeHttpFailure(status).exception()
            }
            val translated = json.optJSONObject("responseData")?.opt("translatedText") as? String
                ?: throw TranslationFailure.FreeFormat.exception()
            if (texts.size == 1) listOf(translated) else parseNumberedLines(translated, texts.size)
        }
        if (result.failure == TranslationFailure.FreeLimited) {
            myMemoryRetryAt = android.os.SystemClock.elapsedRealtime() + 60_000L
        }
        return validate(result, texts)
    }

    private suspend fun requestTencent(texts: List<String>, sourceLang: String): BatchResult {
        val lang = sourceLang.takeIf { it in supportedLanguages } ?: "auto"
        val body = JSONObject()
            .put("header", JSONObject().put("fn", "auto_translation").put("session", "")
                .put("client_key", "browser-chromium-131.0.0.0"))
            .put("source", JSONObject().put("text_list", JSONArray(texts)).put("lang", lang))
            .put("target", JSONObject().put("lang", "zh"))
        val httpRequest = Request.Builder().url("https://transmart.qq.com/api/imt")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .header("Referer", "https://transmart.qq.com/")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return validate(request(httpRequest) { parseTransmart(it, texts.size) ?: throw TranslationFailure.FreeFormat.exception() }, texts)
    }

    private suspend fun request(request: Request, parse: (String) -> List<String>): BatchResult {
        if (context != null && !TranslationPrivacy.allowed(context)) {
            return BatchResult(emptyList(), TranslationFailure.Privacy)
        }
        return try {
            client.newCall(request).executeCancellable().use { response ->
                if (!response.isSuccessful) throw freeHttpFailure(response.code).exception()
                val raw = response.body?.byteStream()?.use { it.readImportBytes(2 * 1024 * 1024).toString(Charsets.UTF_8) }
                    ?: throw TranslationFailure.FreeFormat.exception()
                BatchResult(parse(raw))
            }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val failure = when (error) {
                is org.json.JSONException -> TranslationFailure.FreeFormat
                is java.io.InterruptedIOException -> TranslationFailure.FreeTimeout
                else -> TranslationFailure.from(error)
            }
            BatchResult(emptyList(), failure)
        }
    }

    private fun freeHttpFailure(status: Int) = when (status) {
        402, 429 -> TranslationFailure.FreeLimited
        401, 403 -> TranslationFailure.FreeRejected
        else -> TranslationFailure.FreeUnavailable
    }

    private fun validate(result: BatchResult, originals: List<String>): BatchResult =
        result.copy(texts = result.texts.mapIndexed { index, value ->
            value.trim().takeIf {
                it != originals[index] && it.any { ch -> ch in '\u3400'..'\u9fff' }
            }.orEmpty()
        })

    private fun parseNumberedLines(text: String, size: Int): List<String> {
        val output = MutableList(size) { "" }
        val seen = mutableSetOf<Int>()
        for (line in text.lineSequence().filter(String::isNotBlank)) {
            val match = memoryLabel.matchEntire(line.trim()) ?: throw TranslationFailure.FreeFormat.exception()
            val index = match.groupValues[1].toIntOrNull()?.minus(1)
            if (index == null || index !in output.indices || !seen.add(index))
                throw TranslationFailure.FreeFormat.exception()
            output[index] = match.groupValues[2].trim()
        }
        return output
    }

    internal fun parseTransmart(raw: String, expectCount: Int): List<String>? = runCatching {
        val obj = JSONObject(raw)
        if (obj.optJSONObject("header")?.optString("ret_code") != "succ") return@runCatching null
        val arr = obj.optJSONArray("auto_translation") ?: return@runCatching null
        if (arr.length() != expectCount || (0 until arr.length()).any { arr.opt(it) !is String || arr.optString(it).isBlank() })
            return@runCatching null
        List(expectCount) { arr.getString(it).trim() }
    }.getOrNull()

    // Legacy response parser retained for old cache/import compatibility; no Google requests.
    internal fun parseGtx(body: String): String? = runCatching {
        val root = JSONArray(body)
        val segments = root.optJSONArray(0) ?: return@runCatching null
        val sb = StringBuilder()
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONArray(i) ?: continue
            val translated = seg.optString(0)
            if (translated.isNotEmpty()) sb.append(translated)
        }
        sb.toString().ifBlank { null }
    }.getOrNull()

    override fun close() { sentences.evictAll() }

    private companion object {
        val supportedLanguages = setOf("ja", "en", "ko")
        val lineBreaks = Regex("[\\r\\n]+")
        val memoryLabel = Regex("⟦([0-9]+)⟧([^⟦⟧]*)")
        fun memoryPrefix(index: Int) = "⟦${index + 1}⟧ "
        fun numberedLineBytes(index: Int, textBytes: Int) = memoryPrefix(index).toByteArray(Charsets.UTF_8).size + textBytes
    }
}
