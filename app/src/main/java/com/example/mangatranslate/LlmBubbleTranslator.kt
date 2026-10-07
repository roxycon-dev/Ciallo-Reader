package com.example.mangatranslate

import android.content.Context
import com.example.source.executeCancellable
import com.example.data.readImportBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 自定义 AI 接口翻译（第十六轮，复刻原仓库 TextBubbleTranslationCoordinator 协议）：
 *
 * - OpenAI 兼容（chat/completions）与 Gemini 两种格式；用户自填 endpoint/key/model；
 * - 简洁漫画翻译规则（assets/mt/llm_prompt.txt），只输出 JSON；
 * - 请求：{"items":[{id,text}...],"glossary":{...}}，响应：{"items":[{id,translation}...]}；
 * - 严格校验 ID；每批最多重试一次，整页 28 秒预算，拒绝访问立即反馈；
 * - glossary_used 并入译名表跨页积累（人名/专名前后一致）。
 */
class LlmBubbleTranslator(private val context: Context, private val clientOverride: OkHttpClient? = null, glossaryScope: String = "global") {
    @Volatile var lastFailure: TranslationFailure? = null
        private set

    data class LlmConfig(
        val apiUrl: String,
        val apiKey: String,
        val modelName: String,
        val geminiFormat: Boolean,
    ) {
        fun isValid(): Boolean = apiUrl.trim().toHttpUrlOrNull() != null && modelName.isNotBlank()
    }

    data class Item(val id: Int, val text: String)

    suspend fun testConnection(): String? = kotlinx.coroutines.withTimeoutOrNull(15_000) {
        val cfg = loadConfig()
        lastFailure = null
        if (!TranslationPrivacy.allowed(context)) { lastFailure = TranslationFailure.Privacy; return@withTimeoutOrNull null }
        if (!cfg.isValid()) { lastFailure = TranslationFailure.Config; return@withTimeoutOrNull null }
        val start = android.os.SystemClock.elapsedRealtime()
        val result = try { withContext(Dispatchers.IO) {
            requestOnce(cfg, PROMPT_FALLBACK, listOf(Item(0, "Hello, how are you?")))
        } }
        catch (e: Exception) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); lastFailure = TranslationFailure.from(e); null }
        result?.get(0)?.takeIf(String::isNotBlank)?.let {
            "连接成功 · ${android.os.SystemClock.elapsedRealtime() - start} ms · $it"
        }
    }

    private val client by lazy {
        clientOverride ?: com.example.source.SharedHttpTransport.builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(22, TimeUnit.SECONDS)
            .build()
    }

    private val secrets = com.example.data.EncryptedSecretStore(context)
    private val glossaryLock = Any()
    private val glossaryKey = if (glossaryScope == "global") "glossary" else "glossary_" +
        java.security.MessageDigest.getInstance("SHA-256").digest(glossaryScope.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    /** 译名表（会话级持久，SharedPreferences 落盘），跨页积累保证人名一致。 */
    private val glossary = LinkedHashMap<String, String>()

    init {
        runCatching {
            val prefs = context.getSharedPreferences("mt_llm", Context.MODE_PRIVATE)
            val raw = prefs.getString(glossaryKey, null) ?: return@runCatching
            require(raw.length <= 256 * 1024)
            val o = JSONObject(raw)
            o.keys().asSequence().take(128).forEach { k ->
                val value=o.optString(k)
                if(k.length<=80 && value.length<=120) glossary[k]=value
            }
        }
    }

    fun persistGlossary() {
        runCatching {
            val o = JSONObject()
            synchronized(glossaryLock) { glossary.forEach { (k, v) -> o.put(k, v) } }
            context.getSharedPreferences("mt_llm", Context.MODE_PRIVATE)
                .edit().putString(glossaryKey, o.toString()).apply()
        }
    }

    fun cacheFingerprint():String {
        val cfg=loadConfig()
        val data="${cfg.apiUrl}|${cfg.modelName}|${cfg.geminiFormat}|$glossaryKey|prompt-v3"
        return java.security.MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun glossarySnapshot(): Map<String, String> =
        synchronized(glossaryLock) { glossary.toMap() }

    fun loadConfig(): LlmConfig {
        val p = context.getSharedPreferences("mt_llm", Context.MODE_PRIVATE)
        return LlmConfig(
            apiUrl = p.getString("api_url", "") ?: "",
            apiKey = if (p.getBoolean("use_api_key", true)) secrets.read("api_key", p) ?: "" else "",
            modelName = p.getString("model_name", "") ?: "",
            geminiFormat = p.getBoolean("gemini_format", false),
        )
    }

    fun saveConfig(cfg: LlmConfig) {
        // Anonymous endpoints need no keystore; explicitly disable stale keys when clearing.
        if (cfg.apiKey.isBlank()) secrets.remove("api_key") else secrets.write("api_key", cfg.apiKey)
        context.getSharedPreferences("mt_llm", Context.MODE_PRIVATE).edit()
            .remove("api_key")
            .putString("api_url", cfg.apiUrl.trim())
            .putString("model_name", cfg.modelName.trim())
            .putBoolean("gemini_format", cfg.geminiFormat)
            .putBoolean("use_api_key", cfg.apiKey.isNotBlank())
            .apply()
    }

    /**
     * 整页气泡一次请求。返回 id→译文；失败返回 null（调用方保留原文）。
     * 每批最多重试一次；永久拒绝立即反馈，整页最多等待 28 秒。
     */
    suspend fun translateBubbles(items: List<Item>): Map<Int, String>? =
        withContext(Dispatchers.IO) {
            lastFailure = null
            if (items.isEmpty()) return@withContext emptyMap()
            if (items.size > 500 || items.sumOf { it.text.length.toLong() } > 128_000) return@withContext null
            if(!TranslationPrivacy.allowed(context)) { lastFailure = TranslationFailure.Privacy; return@withContext null }
            val cfg = loadConfig()
            if (!cfg.isValid()) { lastFailure = TranslationFailure.Config; return@withContext null }
            val prompt = runCatching {
                context.assets.open("mt/llm_prompt.txt").bufferedReader(Charsets.UTF_8).readText()
            }.getOrElse {
                PROMPT_FALLBACK
            }
            try {
                // Keep the whole page ordered and in context; bound input/output size per request.
                kotlinx.coroutines.withTimeout(28_000L) {
                    val out = linkedMapOf<Int, String>()
                    for (batch in batches(items)) {
                        var result: Map<Int, String>? = null
                        for (attempt in 0..1) {
                            try { result = requestOnce(cfg, prompt, batch); break }
                            catch (e: Exception) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val failure = TranslationFailure.from(e)
                                if (attempt == 1 || !failure.retryable) throw e
                                kotlinx.coroutines.delay(400L)
                            }
                        }
                        out.putAll(result ?: throw TranslationFailure.Format.exception())
                    }
                    out
                }
            } catch (e: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                lastFailure = TranslationFailure.from(e)
                null
            }
        }

    internal fun batches(items: List<Item>): List<List<Item>> {
        val chunks = mutableListOf<MutableList<Item>>()
        for (item in items) {
            require(item.text.length <= 6000) { "单个对白过长" }
            if (chunks.isEmpty() || chunks.last().size >= 20 || chunks.last().sumOf { it.text.length } + item.text.length > 6000)
                chunks.add(mutableListOf())
            chunks.last().add(item)
        }
        return chunks
    }

    /** 整页翻译便捷入口：列表下标即 id。返回 index→译文。 */
    suspend fun translateBuckets(regions: List<TranslatedRegion>): Map<Int, String>? =
        translateBubbles(regions.mapIndexed { i, r -> Item(i, r.original) })

    private suspend fun requestOnce(cfg: LlmConfig, prompt: String, items: List<Item>): Map<Int, String>? {
        val userPayload = buildUserPayload(items)
        val (url, body) = if (cfg.geminiFormat) buildGemini(cfg, prompt, userPayload)
        else buildOpenAiCompatible(cfg, prompt, userPayload)
        val request = Request.Builder()
            .url(sanitizeEndpoint(url, cfg.geminiFormat))
            .header("Content-Type", "application/json")
            .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).executeCancellable().use { resp ->
            if (!resp.isSuccessful) throw TranslationFailure.http(resp.code).exception()
            val raw = resp.body?.byteStream()?.use { it.readImportBytes(2 * 1024 * 1024).toString(Charsets.UTF_8) } ?: throw TranslationFailure.Format.exception()
            com.example.source.parser.RuleBudget.json(raw)
            val content = if (cfg.geminiFormat) parseGeminiContent(raw) else parseOpenAiContent(raw)
            if (content == null) throw TranslationFailure.Format.exception()
            return parseStrict(content, items) ?: throw TranslationFailure.Format.exception()
        }
    }

    private fun buildUserPayload(items: List<Item>): String {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text)) }
        val g = JSONObject()
        synchronized(glossaryLock) { glossary.forEach { (k, v) -> g.put(k, v) } }
        return JSONObject().put("items", arr).put("glossary", g).toString()
    }

    private fun buildOpenAiCompatible(cfg: LlmConfig, prompt: String, userPayload: String): Pair<String, String> {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", prompt))
            put(JSONObject().put("role", "user").put("content", userPayload))
        }
        val body = JSONObject()
            .put("model", cfg.modelName)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("stream", false)
            .put("max_tokens", (itemsOutputBudget(userPayload)).coerceIn(1024, 8192))
        return compatibleEndpoint(cfg.apiUrl) to body.toString()
    }

    private fun itemsOutputBudget(payload: String): Int = payload.length * 2 + 512

    internal fun compatibleEndpoint(apiUrl: String): String {
        val base = apiUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("API 地址须以 http(s):// 开头")
        if (base.encodedPath.endsWith("/chat/completions")) return base.toString()
        return base.newBuilder().addPathSegments("chat/completions").build().toString()
    }

    private fun buildGemini(cfg: LlmConfig, prompt: String, userPayload: String): Pair<String, String> {
        val body = JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", "$prompt\n\n$userPayload"))
                )
            )
        ).put("generationConfig", JSONObject().put("temperature", 0.2))
        val base = cfg.apiUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("API 地址须以 http(s):// 开头")
        val endpoint = base.newBuilder()
            .apply { if (base.encodedPath == "/") addPathSegment("v1beta") }
            .addPathSegment("models").addPathSegment("${cfg.modelName}:generateContent")
            .setQueryParameter("key", cfg.apiKey).build()
        return endpoint.toString() to body.toString()
    }

    /**
     * endpoint 校验：允许局域网自建 LLM（LM Studio/Ollama 是合法场景），允许
     * http 内网调试；仅拒绝本机回环写法。用户自填 API 属主动配置行为。
     */
    private fun sanitizeEndpoint(url: String, gemini: Boolean): String {
        val u = java.net.URI(url)
        require(u.scheme == "https" || u.scheme == "http") { "API 地址须以 http(s):// 开头" }
        val host = (u.host ?: "").lowercase()
        require(host.isNotEmpty()) { "API 地址缺少主机名" }
        require(host != "localhost" && host != "127.0.0.1" && host != "::1" && host != "[::1]") {
            "请填局域网 IP 而非 localhost（如 http://192.168.x.x:1234）"
        }
        return url
    }

    private fun parseOpenAiContent(body: String): String? = runCatching {
        JSONObject(body)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }.getOrNull()

    private fun parseGeminiContent(body: String): String? = runCatching {
        JSONObject(body)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
            .getJSONObject(0).getString("text")
    }.getOrNull()

    /**
     * 严格解析（原仓库 parseBubbleTranslationContent 语义）：
     * 剥 markdown 围栏 → JSON → id 集合必须与请求完全一致 → 收集 glossary_used。
     */
    internal fun parseStrict(content: String, requested: List<Item>): Map<Int, String>? {
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val json = runCatching { JSONObject(cleaned) }.getOrNull() ?: return null
        val arr = json.optJSONArray("items") ?: return null
        val want = requested.map { it.id }.toSet()
        if (want.size != requested.size) return null
        val out = HashMap<Int, String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return null
            if (!o.has("id") || !o.has("translation")) return null
            val numericId = o.opt("id") as? Number ?: return null
            if (!numericId.toDouble().isFinite() || numericId.toDouble() != numericId.toInt().toDouble()) return null
            val id = numericId.toInt()
            if (id in out) return null          // 重复 id → 判失败重试
            val rawTranslation = o.opt("translation") as? String ?: return null
            // Explicit empty means skipped noise; whitespace-only is malformed output.
            if (rawTranslation.isNotEmpty() && rawTranslation.isBlank()) return null
            val translation = rawTranslation.trim()
            out[id] = translation
        }
        if (out.keys.toSet() != want) return null   // 缺失/多余 id → 判失败重试
        val used = json.optJSONObject("glossary_used")
        if (used != null) {
            synchronized(glossaryLock) {
                used.keys().asSequence().forEach { k ->
                    val v = used.opt(k) as? String ?: return@forEach
                    if (k.isNotBlank() && v.isNotBlank() && k.length<=80 && v.length<=120) {
                        glossary.remove(k); glossary[k]=v
                        while(glossary.size>128) glossary.remove(glossary.keys.first())
                    }
                }
            }
            persistGlossary()
        }
        return out
    }

    companion object {

        /** 离线兜底精简版提示词（与原仓库协议兼容；assets 缺失时用）。 */
        val PROMPT_FALLBACK = """你是漫画翻译机。把外语漫画文字翻译成简体中文。只输出合法 JSON，禁止任何解释或 markdown 代码块。
输入是 {"items":[{"id":数字,"text":"原文"}...],"glossary":{原文:已有译名}}。
输出必须且只包含 {"items":[{"id":数字,"translation":"译文"}...],"glossary_used":{新发现的译名}}。
要求：每个输入 id 必须原样返回且只返回一次，不得合并拆分；乱码或无意义文本的 translation 输出空字符串；按每个气泡独立翻译，不猜测说话人和上下文关系；专有名词参考 glossary 并把新译名写入 glossary_used。"""
    }
}
