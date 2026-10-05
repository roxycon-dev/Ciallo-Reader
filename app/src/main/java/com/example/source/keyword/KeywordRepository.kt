package com.example.source.keyword

import android.content.Context
import com.example.data.AniListDao
import com.example.data.AppDatabase
import com.example.data.PreferencesManager
import com.example.source.anilist.TitleNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Search-only metadata. No dependency on OCR, translation, or credentials of a book source. */
internal class KeywordRepository(
    context: Context,
    private val network: OnlineKeywordLookup = OnlineKeywordLookup(context = context.applicationContext),
    private val titleLookup: (suspend (String, List<KeywordConcept>) -> List<KeywordConcept>)? = null,
) {
    private val app = context.applicationContext
    private val prefs = PreferencesManager(app)
    private val index by lazy { LocalKeywordIndex(app.assets.open("keyword_seed.tsv").reader(Charsets.UTF_8)) }
    private val cache = KeywordCache(File(app.filesDir, "keyword_lookup_v2"))
    private val legacyCache = KeywordCache(File(app.cacheDir, "keyword_lookup_v1"))
    private fun cachedEntry(input: String): KeywordCache.Entry? {
        cache.read(input)?.let { return it }
        val old = legacyCache.read(input)?.takeIf { it.concepts.isNotEmpty() } ?: return null
        runCatching { cache.write(input, old.concepts, if (old.fresh) 6 * 60 * 60_000L else 0L) }
        return old
    }
    private val dictionary = KeywordDictionary(app)
    private val onlineGates = Array(16) { Mutex() }
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var updateJob: kotlinx.coroutines.Job? = null
    private fun engine(forceRefresh: Boolean = false) = KeywordExpansion(::local, { emptyList() }, ::onlineAllowed,
        needsRefresh = { forceRefresh || cachedEntry(it)?.fresh == false },
        onlineEvents = { input, known -> online(input, known, forceRefresh) },
        onlineDisabledReason = ::onlineDisabledReason)

    fun expand(input: String): Flow<List<String>> = engine().expand(input).flowOn(Dispatchers.IO)
    fun observe(input: String, forceRefresh: Boolean = false): Flow<KeywordUpdate> = engine(forceRefresh).observe(input).flowOn(Dispatchers.IO)

    // A PIN protects shelf categories; it is not a global search-network switch.
    // Global incognito still prevents metadata queries and dictionary refreshes.
    private fun onlineAllowed(): Boolean = prefs.multiLanguageSearch && prefs.multiLanguageOnlineLookup &&
        !prefs.incognitoBrowsingEnabled

    private fun onlineDisabledReason(): String = when {
        !prefs.multiLanguageSearch -> "多语言搜索已关闭，仅使用原词"
        !prefs.multiLanguageOnlineLookup -> "在线补充已关闭，使用本地关键词"
        prefs.incognitoBrowsingEnabled -> "无痕浏览中，在线补充暂停，使用本地关键词"
        else -> "使用已有关键词"
    }

    private suspend fun local(input: String): List<KeywordConcept> {
        val seed = index.find(input)
        val saved = cachedEntry(input)?.concepts.orEmpty().map {
            if (it.source.contains("在线缓存")) it else it.copy(source = "在线缓存：${it.source}")
        }
        // A small seed enriches the larger dictionary; it must never hide it.
        val dictionaryNames = try { dictionary.find(input) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        val direct = KeywordConcepts.merge(seed + dictionaryNames + saved)
        val useTitles = direct.isEmpty() || direct.any { it.kind == "work" }
        val titles = if (!useTitles) emptyList() else if (titleLookup != null) titleLookup.invoke(input, direct) else {
            val dao = AppDatabase.getDatabase(app).anilistDao()
            if (dao.countTitles() == 0) AppDatabase.ensureBundledTitlesImported(app)
            linkTitles(dao, input, direct)
        }
        return KeywordConcepts.merge(direct + titles)
    }

    private fun online(input: String, known: List<KeywordConcept>, forceRefresh: Boolean): Flow<OnlineKeywordResult> = flow {
        val key = KeywordKeys.query(input)
        val gate = onlineGates[(key.hashCode() and Int.MAX_VALUE) % onlineGates.size]
        gate.withLock {
            if (!onlineAllowed()) return@withLock
            val entry = cachedEntry(input)
            var concepts = KeywordConcepts.merge(known + entry?.concepts.orEmpty())
            val states = entry?.states.orEmpty().associateBy { it.provider }.toMutableMap()
            val skip = states.values.filter { !forceRefresh && it.retryAt > System.currentTimeMillis() }.map { it.provider }.toSet()
            if (entry != null) emit(OnlineKeywordResult(entry.concepts, entry.states, cached = true))
            if (!forceRefresh && entry?.fresh == true && KeywordConcepts.complete(concepts)) return@withLock
            // Snapshot maintenance has its own job; it cannot consume the per-query deadline.
            if (updateJob?.isActive != true) updateJob = maintenanceScope.launch {
                try { dictionary.refreshIfDue(::onlineAllowed) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { /* Old dictionary remains usable. */ }
            }
            network.stream(input, concepts, skip).collect { result ->
                if (!onlineAllowed()) return@collect
                concepts = KeywordConcepts.merge(concepts + result.concepts)
                result.states.forEach { states[it.provider] = it }
                val ttl = if (KeywordConcepts.complete(concepts)) 30 * 24 * 60 * 60_000L else 6 * 60 * 60_000L
                val stored = runCatching {
                    cache.write(input, concepts, ttl, states.values.toList())
                    // Only actual provider entities become reverse aliases, never an arbitrary union.
                    result.concepts.forEach { cache.writeAliases(input, listOf(it), ttl) }
                }.isSuccess
                emit(result.copy(persisted = stored))
            }
        }

    }

    companion object {
        /** Exact known aliases bridge dictionaries, without pulling in spin-offs by substring. */
        suspend fun linkTitles(dao: AniListDao, input: String, known: List<KeywordConcept>): List<KeywordConcept> {
            val direct = titleConcepts(dao, input, exactOnly = known.any { it.kind == "work" } || KeywordKeys.match(input).length < 4)
            if (known.none { it.kind == "work" }) return direct
            val bridged = known.filter { it.kind == "work" }.flatMap { concept ->
                concept.names.filter { it.language != "zh" }.map { it.text }.distinct().take(4).flatMap { name ->
                    titleConcepts(dao, name, exactOnly = true)
                }
            }
            return KeywordConcepts.merge(direct + bridged)
        }
        /** Group and rank works before distributing slots to languages; ids aren't relevance. */
        suspend fun titleConcepts(dao: AniListDao, input: String, exactOnly: Boolean = false): List<KeywordConcept> {
            val normalized = TitleNormalizer.normalize(input)
            val compact = KeywordKeys.match(input)
            if (compact.length < 2) return emptyList()
            fun escape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val ids = dao.findMediaIds(normalized, compact).ifEmpty {
                if (exactOnly) emptyList() else dao.findMediaIdsContaining(escape(normalized), escape(compact), 8)
            }
            if (ids.isEmpty()) return emptyList()
            val groups = dao.getTitleRowsFor(ids).groupBy { it.mediaId }
            fun rank(text: String): Int {
                val key = KeywordKeys.match(text)
                return when {
                    key == compact -> 0
                    key.startsWith(compact) -> 100 + key.length - compact.length
                    key.contains(compact) -> 1000 + key.length - compact.length
                    else -> 100_000
                }
            }
            return groups.entries.sortedBy { (_, rows) -> rows.minOf { rank(it.rawTitle) } }.take(2).map { (id, rows) ->
                val names = rows.sortedBy { when (it.titleType) {
                    "ENGLISH" -> 0; "NATIVE" -> 1; "ROMAJI" -> 2; else -> 3
                } }.map { row -> KeywordName(row.rawTitle, when (row.titleType) {
                    "ENGLISH" -> "en"; "ROMAJI" -> "romaji";
                    "NATIVE" -> if (KeywordKeys.language(row.rawTitle) == "ja") "ja" else "native"
                    else -> if (KeywordKeys.language(row.rawTitle) == "en") "alias" else KeywordKeys.language(row.rawTitle)
                }) }
                KeywordConcept("anilist:media:$id", names, "AniList local titles")
            }
        }
    }
}
