package com.example.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.json.JSONArray

data class DiscoverState(val entries: List<DiscoverEntry> = emptyList(), val loading: Boolean = false,
    val more: Set<String> = emptySet(), val errors: Map<String, String> = emptyMap(),
    val notices: Map<String, String> = emptyMap(), val cached: Boolean = false,
    val catalogs: Map<String, DiscoverCatalog> = emptyMap())

class DiscoverViewModel(private val manager: SourceManager, private val store: DiscoverStore, private val partition: DiscoverPartition = DiscoverPartition.COMIC) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var sources: List<BookSource> = emptyList()
    private var query = DiscoverQuery(DiscoverDimension.UPDATES, "最新")
    private var pages = mutableMapOf<String, Int>()
    private val cursors = mutableMapOf<String, String?>()
    private val cursorHistory = mutableMapOf<String, MutableSet<String>>()
    private val noProgress = mutableMapOf<String, Int>()
    private val catalogs = mutableMapOf<String, DiscoverCatalog>()
    private var selectedSource = "aggregate"
    private var cacheKey = ""
    private var diskCacheKey = ""
    private val slots = Semaphore(20)
    private val directorySlots = Semaphore(20)
    private var handledRefreshRequest = 0
    fun consumeRefreshRequest(request: Int): Boolean {
        if (request <= handledRefreshRequest) return false
        handledRefreshRequest = request
        return true
    }
    private data class Session(val state: DiscoverState, val pages: Map<String, Int>, val cursors: Map<String, String?>,
        val history: Map<String, Set<String>>, val progress: Map<String, Int>)
    private val sessions = LinkedHashMap<String, Session>(16, .75f, true)
    private val refreshTimes = mutableMapOf<String, Long>()
    private val catalogOwners = mutableMapOf<String, BookSource>()
    private val dynamicResolved = mutableSetOf<String>()
    private val categoryLoading = mutableSetOf<String>()
    private val catalogLocks = mutableMapOf<String, Mutex>()
    private val viewports = mutableMapOf<String, Pair<Int, Int>>()
    private val metadataJobs = mutableMapOf<String, Job>()
    private val metadataAttempts = LinkedHashMap<Pair<String, String>, Long>()
    private fun enrich(source: BookSource, entries: List<DiscoverEntry>, key: String) {
        if (!source.supportsDiscoverMetadata) return
        val previous = metadataJobs[source.id]
        metadataJobs[source.id] = viewModelScope.launch {
            previous?.join()
            for (entry in entries.filter { serialLabel(it.book.comicInfo?.status) == "状态未知" || it.book.comicInfo?.tags.isNullOrEmpty() }.take(8)) {
                ensureActive()
                if (key != cacheKey) return@launch
                val bookKey = source.id to entry.book.id
                if (System.currentTimeMillis() - (metadataAttempts[bookKey] ?: 0L) < 300_000) continue
                metadataAttempts[bookKey] = System.currentTimeMillis()
                if (metadataAttempts.size > 500) metadataAttempts.remove(metadataAttempts.keys.first())
                val result = try { withTimeout(15_000) { source.getDiscoverMetadata(entry.book.id) } }
                    catch (e: TimeoutCancellationException) { continue }
                    catch (e: CancellationException) { throw e }
                if (result is SourceResult.Success && key == cacheKey) {
                    val detail = result.data
                    _state.update { state -> state.copy(entries = state.entries.map { row ->
                        if (row.book.sourceId == source.id && row.book.id == entry.book.id) row.copy(
                            book = row.book.copy(author = detail.author.ifBlank { row.book.author },
                                comicInfo = detail.comicInfo?.copy(
                                    status = detail.comicInfo.status?.takeIf { serialLabel(it) in setOf("连载", "完结", "停更") } ?: row.book.comicInfo?.status,
                                    tags = (row.book.comicInfo?.tags.orEmpty() + detail.comicInfo.tags).distinct()) ?: row.book.comicInfo),
                            updatedAt = DiscoverEngine.parseDate(detail.comicInfo?.updatedAt) ?: row.updatedAt)
                        else row
                    }) }
                }
            }
            if (key == cacheKey) {
                rememberSession()
                if (store.flag("cache")) withContext(Dispatchers.IO) { store.writeCache(diskCacheKey, _state.value.entries) }
            }
        }
    }
    fun saveViewport(key: String, index: Int, offset: Int) { viewports[key] = index to offset }
    fun viewport(key: String): Pair<Int, Int>? = viewports[key]
    fun loadCategories(sourceId: String) {
        if (sourceId in categoryLoading) return
        val source = manager.availableSources.value.firstOrNull { it.id == sourceId && partition.accepts(it) } ?: return
        if (sourceId in dynamicResolved && catalogOwners[sourceId] === source) return
        categoryLoading.add(sourceId)
        viewModelScope.launch {
            try {
                val result = directorySlots.withPermit { catalogFor(source, true) }
                if (result is SourceResult.Success && manager.availableSources.value.any { it === source })
                    _state.update { it.copy(catalogs = catalogs.toMap()) }
            } catch (_: TimeoutCancellationException) {
                // Keep existing rows; a subsequent open can retry this category directory.
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            finally { categoryLoading.remove(sourceId) }
        }
    }
    private suspend fun catalogFor(source: BookSource, resolveDynamic: Boolean): SourceResult<DiscoverCatalog> =
        catalogLocks.getOrPut(source.id) { Mutex() }.withLock {
            val previous = catalogs[source.id]
            if (catalogOwners[source.id] === source && previous != null &&
                (!resolveDynamic || !previous.dynamicCategories || source.id in dynamicResolved))
                return@withLock SourceResult.Success(previous)
            val result = withTimeout(50_000) { source.discoverCatalog(resolveDynamic) }
            if (result is SourceResult.Success) {
                catalogs[source.id] = result.data.copy(dated = result.data.dated || previous?.dated == true,
                    capabilities = (previous?.capabilities.orEmpty().keys + result.data.capabilities.keys).associateWith { kind ->
                        (previous?.capabilities?.get(kind).orEmpty() + result.data.capabilities[kind].orEmpty()).distinct() })
                catalogOwners[source.id] = source
                if (resolveDynamic) dynamicResolved.add(source.id) else dynamicResolved.remove(source.id)
            }
            result
        }
    private fun rememberSession() {
        if (cacheKey.isBlank()) return
        sessions[cacheKey] = Session(_state.value.copy(loading = false), pages.toMap(), cursors.toMap(),
            cursorHistory.mapValues { it.value.toSet() }, noProgress.toMap())
        while (sessions.size > 32 || (sessions.size > 1 && sessions.values.sumOf { it.state.entries.size } > 8000))
            sessions.remove(sessions.keys.first { it != cacheKey })
    }

    fun configure(sourceId: String, dimension: DiscoverDimension, range: String, feedId: String? = null,
        categoryId: String? = null, standardCategories: Set<String> = emptySet(), force: Boolean = false, status: String? = null) {
        val nextSources = manager.availableSources.value.filter {
            partition.accepts(it) && (sourceId == "aggregate" || it.id == sourceId) }
        val signature = nextSources.sortedBy { it.id }.joinToString { "${it.id}@${(it as? com.example.source.js.JsComicSource)?.version.orEmpty()}" }
        val window = if (dimension == DiscoverDimension.UPDATES && feedId == null && categoryId == null) DiscoverEngine.rangeStart(range, System.currentTimeMillis()) ?: 0L else 0L
        val nextKey = "$sourceId:${dimension.name}:$range:${feedId.orEmpty()}:${categoryId.orEmpty()}:${standardCategories.sorted()}:$signature:$status:$window:v3"
        if (!force && nextKey == cacheKey) { sources = nextSources; return }
        rememberSession()
        job?.cancel()
        metadataJobs.values.forEach { it.cancel() }; metadataJobs.clear()
        sources = nextSources
        selectedSource = sourceId
        query = DiscoverQuery(dimension, range, feedId = feedId, categoryId = categoryId, includeAdult = true,
            standardCategories = standardCategories, status = status)
        cacheKey = nextKey
        diskCacheKey = "$sourceId:${dimension.name}:$range:${feedId.orEmpty()}:${categoryId.orEmpty()}:${standardCategories.sorted()}:$signature:${com.example.BuildConfig.VERSION_CODE}:$status:$window:v5"
        pages.clear(); cursors.clear(); cursorHistory.clear(); noProgress.clear()
        val session = sessions[nextKey]
        if (!force && session != null) {
            pages.putAll(session.pages); cursors.putAll(session.cursors); noProgress.putAll(session.progress)
            cursorHistory.putAll(session.history.mapValues { it.value.toMutableSet() })
            _state.value = session.state.copy(catalogs = catalogs.toMap())
            val missing = sources.filter { it.id !in pages && it.id !in session.state.errors }
            if (missing.isNotEmpty()) job = viewModelScope.launch { fetch(missing, append = true) }
            return
        }
        val previousRows = session?.state?.entries.orEmpty()
        _state.value = DiscoverState(entries = previousRows, loading = true, catalogs = catalogs.toMap())
        val key = diskCacheKey
        job = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val start = if (dimension == DiscoverDimension.UPDATES && feedId == null && categoryId == null) DiscoverEngine.rangeStart(range, now) else null
            val saved = if (store.flag("cache")) withContext(Dispatchers.IO) { store.readCache(key) } else emptyList()
            val cached = previousRows.ifEmpty { saved }.filter { e -> sources.any { it.id == e.book.sourceId } &&
                (start == null || (e.updatedAt != null && e.updatedAt >= start && e.updatedAt <= now)) }
            val navigation = if (!force && saved.isNotEmpty()) withContext(Dispatchers.IO) { store.readNavigation(key) } else null
            if (navigation != null && cached.size == saved.size && navigation.optInt("entryCount", -1) == saved.size && restoreNavigation(navigation)) {
                _state.value = DiscoverState(entries = cached, cached = true, more = sources.map { it.id }.filter { navigation.optJSONArray("more")?.let { rows -> (0 until rows.length()).any { i -> rows.getString(i) == it } } == true }.toSet(), catalogs = catalogs.toMap())
                rememberSession()
                return@launch
            }
            _state.value = DiscoverState(entries = cached, cached = cached.isNotEmpty(), loading = true, catalogs = catalogs.toMap())
            fetch(sources, append = false)
        }
    }
    fun refresh() {
        if (job?.isActive == true) return
        val now = System.currentTimeMillis()
        if (now - (refreshTimes[cacheKey] ?: 0L) < 3000) return
        refreshTimes[cacheKey] = now
        configure(selectedSource, query.dimension, query.range, query.feedId, query.categoryId, query.standardCategories, force = true, status = query.status)
    }
    fun more() {
        if (_state.value.loading || _state.value.more.isEmpty()) return
        val targets = sources.filter { it.id in _state.value.more && it.id !in _state.value.errors }
        if (targets.isEmpty()) return
        job = viewModelScope.launch { fetch(targets, append = true) }
    }
    fun retry() {
        if (_state.value.loading) return
        val targets = sources.filter { it.id in _state.value.errors }
        if (targets.isEmpty()) return
        job = viewModelScope.launch { fetch(targets, append = true) }
    }
    private suspend fun fetch(targets: List<BookSource>, append: Boolean) = coroutineScope {
        val key = cacheKey
        val requested = query
        _state.update { it.copy(loading = true) }
        val completed = Channel<Triple<String, Int, SourceResult<DiscoverPage>>>(Channel.UNLIMITED)
        targets.forEach { source -> launch {
            val page = (pages[source.id] ?: 0) + 1
            val result = try {
                val dynamic = requested.standardCategories.isNotEmpty() || requested.categoryId?.startsWith("dynamic:") == true
                if (catalogOwners[source.id] !== source || (dynamic && source.id !in dynamicResolved && catalogs[source.id]?.dynamicCategories == true)) {
                    directorySlots.withPermit { catalogFor(source, dynamic) }
                }
                val catalog = catalogs[source.id]
                val routes = if (requested.categoryId == null) catalog?.routes(requested.standardCategories).orEmpty() else emptyList()
                if (requested.feedId == null && requested.categoryId == null && catalog?.supports(requested.dimension, requested.range) == false &&
                    (catalog.explicit || (catalog.feeds.isEmpty() && catalog.categories.isEmpty() && catalog.capabilities.isNotEmpty())))
                    SourceResult.Success(DiscoverPage(supported = false, catalog = catalog))
                else slots.withPermit { withTimeout(50_000) { source.discover(requested.copy(page = page, cursor = cursors[source.id], categoryIds = routes,
                    offset = if (append) _state.value.entries.count { it.book.sourceId == source.id } else 0)) } }
            }
                catch (_: TimeoutCancellationException) { SourceResult.Error(SourceException.NetworkError("连接较慢，请稍后重试")) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { SourceResult.Error(SourceException.Unknown(e.message ?: "网络异常", e)) }
            completed.send(Triple(source.id, page, result))
        } }
        // Publish each completed source independently: one unavailable site cannot block the rest.
        val errors = if (append) _state.value.errors.toMutableMap() else mutableMapOf()
        val notices = if (append) _state.value.notices.toMutableMap() else mutableMapOf()
        val more = if (append) _state.value.more.toMutableSet() else mutableSetOf()
        val cachedRows = _state.value.entries
        var rows = cachedRows
        repeat(targets.size) {
            val (id, page, result) = completed.receive()
            ensureActive()
            if (key != cacheKey) return@coroutineScope
            when (result) {
                is SourceResult.Success -> {
                    pages[id] = page
                    val data = result.data
                    val oldIds = rows.filter { it.book.sourceId == id }.map { it.book.id }.toSet()
                    val fresh = data.entries.any { it.book.id !in oldIds }
                    noProgress[id] = if (fresh || !append) 0 else (noProgress[id] ?: 0) + 1
                    if (!append) rows = rows.filterNot { it.book.sourceId == id }
                    rows = ((if (data.supported) data.entries else emptyList()) + rows).distinctBy { it.book.sourceId to it.book.id }
                        .sortedWith(compareBy<DiscoverEntry> { it.book.sourceId }.thenBy { it.nativeOrder ?: Int.MAX_VALUE })
                    data.catalog?.let { next ->
                        val previous = catalogs[id]
                        catalogs[id] = next.copy(dated = next.dated || previous?.dated == true,
                            categories = next.categories.ifEmpty { previous?.categories.orEmpty() },
                            capabilities = (previous?.capabilities.orEmpty().keys + next.capabilities.keys).associateWith { kind ->
                                (previous?.capabilities?.get(kind).orEmpty() + next.capabilities[kind].orEmpty()).distinct() })
                    }
                    errors.remove(id)
                    if (!data.supported) notices[id] = data.notice ?: "该站未提供当前榜单或时间范围"
                    else if (data.entries.isEmpty() && !data.hasMore) notices[id] = "该站当前条件没有作品"
                    else notices.remove(id)
                    val repeatedCursor = data.nextCursor?.let { !cursorHistory.getOrPut(id) { mutableSetOf() }.add(it) } == true
                    cursors[id] = data.nextCursor
                    val stalled = (noProgress[id] ?: 0) >= 2 || repeatedCursor
                    if (data.hasMore && data.supported && !stalled) more.add(id) else more.remove(id)
                }
                is SourceResult.Error -> {
                    errors[id] = result.exception.message ?: "加载失败"
                    if (!append) rows = (rows + cachedRows.filter { it.book.sourceId == id })
                        .distinctBy { it.book.sourceId to it.book.id }
                }
            }
            val enriched = _state.value.entries.associateBy { it.book.sourceId to it.book.id }
            rows = rows.map { row -> enriched[row.book.sourceId to row.book.id]?.takeIf { row.book.sourceId != id && it.updatedAt != null } ?: row }
            _state.value = DiscoverState(rows, true, more.toSet(), errors.toMap(), notices.toMap(), errors.isNotEmpty(), catalogs.toMap())
            if (result is SourceResult.Success && result.data.supported) sources.firstOrNull { it.id == id }?.let { enrich(it, result.data.entries, key) }
        }
        completed.close()
        _state.update { it.copy(loading = false) }
        rememberSession()
        val persistentKey = diskCacheKey
        if (rows.isNotEmpty() && store.flag("cache")) {
            val navigation = navigationSnapshot()
            val persistedRows = _state.value.entries
            withContext(Dispatchers.IO) { store.writeCache(persistentKey, persistedRows, navigation) }
        }
    }

    private fun navigationSnapshot(): JSONObject = JSONObject().put("entryCount", _state.value.entries.size).put("pages", JSONObject(pages.toMap()))
        .put("cursors", JSONObject(cursors.filterValues { it != null })).put("more", JSONArray(_state.value.more.toList()))
        .put("history", JSONObject().apply { cursorHistory.forEach { (id, values) -> put(id, JSONArray(values.toList())) } })
        .put("progress", JSONObject(noProgress.toMap()))
        .put("catalogs", JSONObject().apply { catalogs.forEach { (id, catalog) -> put(id, JSONObject()
            .put("feeds", JSONArray(catalog.feeds.map { JSONObject().put("id", it.id).put("label", it.label).put("kind", it.kind) }))
            .put("categories", JSONArray(catalog.categories.map { JSONObject().put("id", it.id).put("label", it.label).put("group", it.group) }))
            .put("capabilities", JSONObject().apply { catalog.capabilities.forEach { (kind, ranges) -> put(kind, JSONArray(ranges)) } })
            .put("dynamic", catalog.dynamicCategories).put("explicit", catalog.explicit).put("declared", catalog.declared).put("dated", catalog.dated).put("statuses", JSONArray(catalog.statuses))) } })

    /** Only complete pages can be resumed. Old entry-only caches still revalidate. */
    private fun restoreNavigation(json: JSONObject): Boolean = runCatching {
        val savedPages = json.getJSONObject("pages")
        if (sources.any { !savedPages.has(it.id) }) return@runCatching false
        val savedCatalogs = json.getJSONObject("catalogs")
        sources.forEach { source ->
            val c = savedCatalogs.getJSONObject(source.id)
            val feeds = c.getJSONArray("feeds"); val categories = c.getJSONArray("categories"); val capabilities = c.getJSONObject("capabilities")
            catalogs[source.id] = DiscoverCatalog(
                feeds = (0 until feeds.length()).map { i -> feeds.getJSONObject(i).let { DiscoverFeed(it.getString("id"), it.getString("label"), it.optString("kind").takeUnless { kind -> kind.isBlank() || kind == "null" }) } },
                categories = (0 until categories.length()).map { i -> categories.getJSONObject(i).let { DiscoverNativeCategory(it.getString("id"), it.getString("label"), it.optString("group")) } },
                dynamicCategories = c.optBoolean("dynamic"), explicit = c.optBoolean("explicit"), declared = c.optBoolean("declared"), dated = c.optBoolean("dated"),
                statuses = c.optJSONArray("statuses")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                capabilities = capabilities.keys().asSequence().associateWith { kind -> capabilities.getJSONArray(kind).let { a -> (0 until a.length()).map { a.getString(it) } } })
            pages[source.id] = savedPages.getInt(source.id)
            noProgress[source.id] = json.optJSONObject("progress")?.optInt(source.id) ?: 0
            json.optJSONObject("history")?.optJSONArray(source.id)?.let { a -> cursorHistory[source.id] = (0 until a.length()).map { a.getString(it) }.toMutableSet() }
            cursors[source.id] = json.optJSONObject("cursors")?.optString(source.id)?.takeIf { it.isNotBlank() }
        }
        true
    }.getOrDefault(false)
}
