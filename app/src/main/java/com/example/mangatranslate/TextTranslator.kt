package com.example.mangatranslate

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import com.example.source.executeCancellable
import com.example.data.readImportBytes
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
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

/** Tencent's public web translator. Bounded batches, cancellation and repeated-dialogue caching. */
class OnlineFallbackTranslator(
    private val context: android.content.Context? = null,
    private val clientOverride: OkHttpClient? = null,
) : TextTranslator {
    private val client by lazy {
        clientOverride ?: com.example.source.SharedHttpTransport.builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
    private val sentences = android.util.LruCache<String, String>(512)
    private val requests = Semaphore(2)

    override suspend fun prepare(sourceLang: String): String? = null
    override suspend fun translate(text: String, sourceLang: String): String? =
        translateBatch(listOf(text), sourceLang)?.firstOrNull()?.takeIf { it.isNotBlank() }

    suspend fun translateBatch(texts: List<String>, sourceLang: String): List<String>? = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        if (context != null && !TranslationPrivacy.allowed(context)) return@withContext null
        require(texts.size <= 500 && texts.sumOf { it.length } <= 128_000) { "翻译请求过大" }
        if (sourceLang == "zh") return@withContext texts
        val normalized = texts.map { it.trim() }
        val unique = normalized.filter { it.isNotBlank() }.distinct()
        val missing = unique.filter { sentences.get("$sourceLang|$it") == null }
        // Chunk by both count and character budget. A failure never fans out into N single requests.
        val chunks = mutableListOf<MutableList<String>>()
        for (text in missing) {
            if (text.length > 6000) continue
            if (chunks.isEmpty() || chunks.last().size >= 24 || chunks.last().sumOf { it.length } + text.length > 6000)
                chunks.add(mutableListOf())
            chunks.last().add(text)
        }
        withTimeoutOrNull(10_000L) {
            coroutineScope {
                chunks.map { batch -> async {
                    requests.withPermit {
                        requestBatch(batch, sourceLang)?.forEachIndexed { i, translated ->
                            sentences.put("$sourceLang|${batch[i]}", translated)
                        }
                    }
                } }.awaitAll()
            }
        }
        val out = normalized.map { if (it.isBlank()) "" else sentences.get("$sourceLang|$it") ?: "" }
        out.takeIf { it.any(String::isNotBlank) }
    }

    private suspend fun requestBatch(texts: List<String>, sourceLang: String): List<String>? {
        val lang = sourceLang.takeIf { it in setOf("ja", "en", "ko", "zh") } ?: "auto"
        val body = JSONObject()
            .put("header", JSONObject().put("fn", "auto_translation").put("session", "")
                .put("client_key", "browser-chromium-131.0.0.0"))
            .put("source", JSONObject().put("text_list", JSONArray(texts)).put("lang", lang))
            .put("target", JSONObject().put("lang", "zh"))
        val request = Request.Builder().url("https://transmart.qq.com/api/imt")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .header("Referer", "https://transmart.qq.com/")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return runCatching {
            client.newCall(request).executeCancellable().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val raw = response.body?.byteStream()?.use { it.readImportBytes(2 * 1024 * 1024).toString(Charsets.UTF_8) }
                    ?: return@runCatching null
                parseTransmart(raw, texts.size)
            }
        }.getOrElse { kotlinx.coroutines.currentCoroutineContext().ensureActive(); null }
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
}
