package com.example.source.keyword

import com.example.data.readImportBytes
import com.example.source.SharedHttpTransport
import com.example.source.executeCancellable
import android.content.Context
import com.example.source.zlibrary.network.SystemProxyResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Structured labels and aliases only. No translation, scraping, or source-specific syntax. */
internal class OnlineKeywordLookup(
    private val client: OkHttpClient = SharedHttpTransport.builder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).build(),
    private val wikiEndpoint: String = "https://www.wikidata.org/w/api.php",
    private val bangumiEndpoint: String = "https://api.bgm.tv/v0/",
    private val now: () -> Long = System::currentTimeMillis,
    private val aniListEndpoint: String = "https://graphql.anilist.co",
    context: Context? = null,
) {
    private val app = context?.applicationContext
    private var proxyClient: OkHttpClient? = null
    private var lastProxy: java.net.Proxy? = null
    @Synchronized private fun httpClient(): OkHttpClient {
        val proxy = app?.let(SystemProxyResolver::resolve) ?: return client
        if (proxyClient == null || lastProxy != proxy) {
            lastProxy = proxy
            proxyClient = client.newBuilder().proxy(proxy).build()
        }
        return proxyClient!!
    }
    private val cooldowns = ConcurrentHashMap<String, Long>()
    private class Unavailable(val reason: String = "资料服务暂不可用") : Exception(reason)
    private fun failureReason(e: Exception): String = when (e) {
        is Unavailable -> e.reason
        is java.net.UnknownHostException -> "域名解析失败"
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "网络连接超时"
        is javax.net.ssl.SSLException -> "安全连接失败"
        is java.net.ConnectException -> "无法连接资料服务，请检查网络或系统代理"
        is org.json.JSONException -> "资料服务返回了异常数据"
        else -> "资料服务连接失败"
    }

    private val bangumiSlots = Semaphore(2)
    private class Ambiguous : Exception()

    /** Compatibility helper. Failed providers are never interpreted as negative evidence. */
    suspend fun lookup(input: String): List<KeywordConcept>? {
        val updates = stream(input, emptyList()).toList()
        val concepts = KeywordConcepts.merge(updates.flatMap { it.concepts })
        return if (concepts.isEmpty() && updates.any { u -> u.states.any { it.outcome == "failed" } }) null else concepts
    }

    fun stream(input: String, known: List<KeywordConcept>, skip: Set<String> = emptySet()): Flow<OnlineKeywordResult> = channelFlow {
        val bridgeStarted = AtomicBoolean(false)
        val pending = ConcurrentHashMap<String, Job>()
        suspend fun runProvider(id: String, call: suspend () -> List<KeywordConcept>): List<KeywordConcept> {
            if (id in skip) return emptyList()
            var outcome = "found"
            var detail = ""
            val names = try { call() }
            catch (e: CancellationException) { throw e }
            catch (_: Ambiguous) { outcome = "ambiguous"; emptyList() }
            catch (e: Exception) { outcome = "failed"; detail = failureReason(e); emptyList() }
            if (names.isEmpty() && outcome == "found") outcome = "empty"
            val delay = when (outcome) { "found" -> 6 * 60 * 60_000L; "empty" -> 10 * 60_000L; "ambiguous" -> 60 * 60_000L; else -> 60_000L }
            send(OnlineKeywordResult(names, listOf(KeywordProviderState(id, outcome, now() + delay, detail))))
            if (KeywordConcepts.complete(names)) pending.filterKeys { it != id }.values.forEach { it.cancel() }
            return names
        }
        fun bridge(concepts: List<KeywordConcept>) {
            val target = concepts.firstOrNull { it.kind in setOf("work", "character", "person") } ?: return
            if ("anilist" in skip || !bridgeStarted.compareAndSet(false, true)) return
            val job = launch(start = CoroutineStart.LAZY) { runProvider("anilist") { anilist(target) } }
            pending["anilist"] = job
            job.start()
        }
        pending["wikidata"] = launch(start = CoroutineStart.LAZY) { runProvider("wikidata") { wikidata(input) } }
        val kinds = known.map { it.kind }.filter { it != "unknown" }.distinct()
        val resources = when {
            "common" in kinds -> emptyList()
            kinds == listOf("character") -> listOf("characters")
            kinds == listOf("person") -> listOf("persons")
            kinds == listOf("work") -> listOf("subjects")
            else -> listOf("characters", "persons", "subjects")
        }
        resources.forEach { resource -> pending["bangumi:$resource"] = launch(start = CoroutineStart.LAZY) {
            val found = runProvider("bangumi:$resource") { bangumi(input, resource, known) }
            if (found.isNotEmpty() && !KeywordConcepts.complete(found)) bridge(found)
        } }
        bridge(known)
        pending.values.forEach { it.start() }
    }

    private suspend fun json(request: Request): JSONObject {
        val host = request.url.host
        if ((cooldowns[host] ?: 0) > now()) throw Unavailable("资料服务限流，请稍后重试")
        suspend fun execute(): JSONObject {
            httpClient().newCall(request.newBuilder()
                .header("User-Agent", "CialloReader/1.2 (https://github.com/roxycon-dev/Ciallo-Reader; keyword metadata)")
                .header("Accept", "application/json").build()).executeCancellable().use { response ->
                if (response.code == 429 || response.code == 503) {
                    val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 86_400) ?: 300
                    cooldowns[host] = now() + seconds * 1_000
                    throw Unavailable("HTTP ${response.code}，资料服务暂时限流")
                }
                if (!response.isSuccessful) throw Unavailable("HTTP ${response.code}")
                val text = response.body?.byteStream()?.use { it.readImportBytes(1024 * 1024).toString(Charsets.UTF_8) }
                    ?: throw Unavailable()
                return JSONObject(text).also { if (it.has("error") || it.has("errors")) throw Unavailable() }
            }
        }
        // Only server rate limits suppress the whole host. A bad detail or one
        // resource's HTTP error must not poison other Bangumi lookups.
        return if (host == bangumiEndpoint.toHttpUrl().host) bangumiSlots.withPermit { execute() } else execute()
    }

    private fun wikiUrl(action: String) = wikiEndpoint.toHttpUrl().newBuilder()
        .addQueryParameter("action", action).addQueryParameter("format", "json")
        .addQueryParameter("maxlag", "5")

    private suspend fun wikidata(input: String): List<KeywordConcept> {
        val results = json(Request.Builder().url(wikiUrl("wbsearchentities")
            .addQueryParameter("search", input).addQueryParameter("language", "zh")
            .addQueryParameter("uselang", "zh").addQueryParameter("type", "item")
            .addQueryParameter("limit", "5").build()).build()).getJSONArray("search")
        val key = KeywordKeys.match(input)
        val ids = (0 until results.length()).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            val label = item.optString("label")
            val matched = item.optJSONObject("match")?.optString("text").orEmpty()
            item.optString("id").takeIf {
                it.matches(Regex("Q[0-9]+")) &&
                    (KeywordKeys.match(label) == key || KeywordKeys.match(matched) == key)
            }
        }.distinct().take(3)
        if (ids.isEmpty()) return emptyList()
        val entities = json(Request.Builder().url(wikiUrl("wbgetentities")
            .addQueryParameter("ids", ids.joinToString("|"))
            .addQueryParameter("props", "labels|aliases")
            .addQueryParameter("languages", "zh|zh-hans|zh-hant|en|ja").build()).build())
            .getJSONObject("entities")
        val verified = ids.mapNotNull { id ->
            val entity = entities.optJSONObject(id) ?: return@mapNotNull null
            val names = buildList {
                for (lang in listOf("en", "ja", "zh", "zh-hans", "zh-hant")) {
                    val normalizedLang = if (lang.startsWith("zh")) "zh" else lang
                    entity.optJSONObject("labels")?.optJSONObject(lang)?.optString("value")
                        ?.takeIf { it.isNotBlank() }?.let { add(KeywordName(it, normalizedLang)) }
                    val aliases = entity.optJSONObject("aliases")?.optJSONArray(lang)
                    if (aliases != null) for (i in 0 until minOf(aliases.length(), 32)) {
                        aliases.optJSONObject(i)?.optString("value")?.takeIf { it.isNotBlank() }
                            ?.let { add(KeywordName(it, normalizedLang)) }
                    }
                }
            }.filter { it.text.length <= 160 }.distinct()
            // Confirm the Chinese name against the entity itself, not just search ranking.
            if (names.none { it.language == "zh" && KeywordKeys.match(it.text) == key }) null
            else KeywordConcept("wikidata:$id", names, "Wikidata (CC0)")
        }
        if (verified.size > 1) throw Ambiguous()
        return verified
    }

    private suspend fun bangumi(input: String, resource: String, known: List<KeywordConcept>): List<KeywordConcept> {
        val body = JSONObject().put("keyword", input).put("filter", JSONObject())
        if (resource == "subjects") body.put("sort", "match")
        val response = json(Request.Builder().url(bangumiEndpoint.toHttpUrl().resolve("search/$resource?limit=5&offset=0")!!)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())
        val data = response.getJSONArray("data")
        val keys = (listOf(input) + known.flatMap { it.names }.map { it.text }).map(KeywordKeys::match).toSet()
        val rows = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        // Fetch aliases even when a Chinese search hit's display name is Japanese.
        val ranked = rows.sortedBy { row ->
            if (listOf(row.optString("name"), row.optString("name_cn")).any { KeywordKeys.match(it) in keys }) 0 else 1
        }.take(if (resource == "subjects") 3 else 5)
        val verified = mutableListOf<Pair<Int, KeywordConcept>>()
        for (row in ranked) {
            val id = row.optInt("id")
            if (id <= 0) continue
            // Current character/person search responses already include infobox
            // aliases. Use them directly instead of spending more round trips.
            val detail = if (row.has("infobox")) row else json(Request.Builder().url(bangumiEndpoint.toHttpUrl().resolve("$resource/$id")!!).build())
            val names = bangumiNames(detail)
            val rank = KeywordPersonNames.rank(names, keys, resource != "subjects")
            if (rank != null) {
                val kind = when (resource) { "subjects" -> "subject"; "characters" -> "character"; else -> "person" }
                verified += rank to KeywordConcept("bangumi:$kind:$id", names, "Bangumi")
            }
        }
        val bestRank = verified.minOfOrNull { it.first } ?: return emptyList()
        val matches = verified.filter { it.first == bestRank }.map { it.second }.distinctBy { it.id }
        if (matches.size > 1) throw Ambiguous()
        return matches
    }

    private fun bangumiNames(detail: JSONObject): List<KeywordName> = buildList {
        fun add(text: String, lang: String = KeywordKeys.language(text)) {
            if (text.isNotBlank() && text != "null" && text.length <= 160) add(KeywordName(text, lang))
        }
        val native = detail.optString("name")
        add(native, if (KeywordKeys.language(native) == "ja") "ja" else "native")
        add(detail.optString("name_cn"), "zh")
        val info = detail.optJSONArray("infobox")
        if (info != null) for (i in 0 until info.length()) {
            val row = info.optJSONObject(i) ?: continue
            val key = row.optString("key")
            if (key !in setOf("别名", "中文名", "简体中文名", "繁体中文名", "英文名", "日文名", "罗马字", "罗马音")) continue
            fun lang(label: String, text: String): String = when {
                label.contains("中文") -> "zh"
                label.contains("罗马") -> "romaji"
                label.contains("英文") -> "en"
                label.contains("日文") || label.contains("假名") -> "ja"
                KeywordKeys.language(text) == "en" -> "latin"
                else -> KeywordKeys.language(text)
            }
            when (val value = row.opt("value")) {
                is String -> add(value, lang(key, value))
                is JSONArray -> for (j in 0 until minOf(value.length(), 32)) {
                    val item = value.optJSONObject(j) ?: continue
                    val text = item.optString("v")
                    add(text, lang(item.optString("k"), text))
                }
            }
        }
    }.distinct()

    /** Only verified aliases bridge to AniList; a top-ranked unrelated result is insufficient. */
    private suspend fun anilist(concept: KeywordConcept): List<KeywordConcept> {
        val terms = concept.names.filter { it.language in setOf("ja", "native", "romaji", "latin", "en") }
            .sortedBy { if (it.language in setOf("ja", "native")) 0 else 1 }.map { it.text }.distinct().take(2)
        val keys = concept.names.map { KeywordKeys.match(it.text) }.toSet()
        val kind = concept.kind
        val field = when (kind) { "work" -> "media"; "character" -> "characters"; "person" -> "staff"; else -> return emptyList() }
        val selection = if (kind == "work") "id title { english native romaji } synonyms"
            else "id name { full native alternative }"
        for (term in terms) {
            val query = "query (${ '$' }search: String) { Page(perPage: 5) { $field(search: ${ '$' }search) { $selection } } }"
            val body = JSONObject().put("query", query).put("variables", JSONObject().put("search", term))
            val page = json(Request.Builder().url(aniListEndpoint)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build())
                .getJSONObject("data").getJSONObject("Page")
            val rows = page.getJSONArray(field)
            val matches = (0 until rows.length()).mapNotNull { i ->
                val row = rows.optJSONObject(i) ?: return@mapNotNull null
                val names = buildList {
                    fun add(text: String, lang: String) { if (text.isNotBlank() && text != "null") add(KeywordName(text, lang)) }
                    if (kind == "work") {
                        val title = row.optJSONObject("title") ?: return@mapNotNull null
                        add(title.optString("english"), "en"); add(title.optString("native"), "native"); add(title.optString("romaji"), "romaji")
                        val aliases = row.optJSONArray("synonyms")
                        if (aliases != null) for (j in 0 until minOf(aliases.length(), 32)) add(aliases.optString(j), "alias")
                    } else {
                        val name = row.optJSONObject("name") ?: return@mapNotNull null
                        add(name.optString("full"), "latin"); add(name.optString("native"), "native")
                        val aliases = name.optJSONArray("alternative")
                        if (aliases != null) for (j in 0 until minOf(aliases.length(), 32)) add(aliases.optString(j), "alias")
                    }
                }.filter { it.text.length <= 160 }.distinct()
                if (names.none { KeywordKeys.match(it.text) in keys }) null
                else KeywordConcept("anilist:${if (kind == "work") "media" else if (kind == "person") "staff" else "character"}:${row.optInt("id")}", names, "AniList online")
            }
            if (matches.size > 1) throw Ambiguous()
            if (matches.isNotEmpty()) return matches
        }
        return emptyList()
    }
}
