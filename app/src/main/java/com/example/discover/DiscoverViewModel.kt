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

data class DiscoverState(val entries: List<DiscoverEntry> = emptyList(), val loading: Boolean = false,
    val more: Set<String> = emptySet(), val errors: Map<String, String> = emptyMap(),
    val notices: Map<String, String> = emptyMap(), val cached: Boolean = false,
    val catalogs: Map<String, DiscoverCatalog> = emptyMap())

class DiscoverViewModel(private val manager: SourceManager, private val store: DiscoverStore) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var sources: List<ComicSource> = emptyList()
    private var query = DiscoverQuery(DiscoverDimension.UPDATES, "最新")
    private var pages = mutableMapOf<String, Int>()
    private val cursors = mutableMapOf<String, String?>()
    private val cursorHistory = mutableMapOf<String, MutableSet<String>>()
    private val noProgress = mutableMapOf<String, Int>()
    private val catalogs = mutableMapOf<String, DiscoverCatalog>()
    private var selectedSource = "aggregate"
    private var cacheKey = ""
    private var diskCacheKey = ""
    private val slots = Semaphore(3)
    private data class Session(val state: DiscoverState, val pages: Map<String, Int>, val cursors: Map<String, String?>,
        val history: Map<String, Set<String>>, val progress: Map<String, Int>)
    private val sessions = LinkedHashMap<String, Session>(16, .75f, true)
    private val refreshTimes = mutableMapOf<String, Long>()
    private val catalogOwners = mutableMapOf<String, ComicSource>()
    private val dynamicResolved = mutableSetOf<String>()
    private val categoryLoading = mutableSetOf<String>()
    private val catalogLocks = mutableMapOf<String, Mutex>()
    private val viewports = mutableMapOf<String, Pair<Int, Int>>()
    fun saveViewport(key: String, index: Int, offset: Int) { viewports[key] = index to offset }
    fun viewport(key: String): Pair<Int, Int>? = viewports[key]
    fun loadCategories(sourceId: String) {
        if (sourceId in categoryLoading) return
        val source = manager.availableSources.value.filterIsInstance<ComicSource>().firstOrNull { it.id == sourceId } ?: return
        if (sourceId in dynamicResolved && catalogOwners[sourceId] === source) return
        categoryLoading.add(sourceId)
        viewModelScope.launch {
            try {
                val result = slots.withPermit { catalogFor(source, true) }
                if (result is SourceResult.Success && manager.availableSources.value.any { it === source })
                    _state.update { it.copy(catalogs = catalogs.toMap()) }
            } catch (_: TimeoutCancellationException) {
                // Keep existing rows; a subsequent open can retry this category directory.
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            finally { categoryLoading.remove(sourceId) }
        }
    }
    private suspend fun catalogFor(source: ComicSource, resolveDynamic: Boolean): SourceResult<DiscoverCatalog> =
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
        while (sessions.size > 12 || (sessions.size > 1 && sessions.values.sumOf { it.state.entries.size } > 8000))
            sessions.remove(sessions.keys.first { it != cacheKey })
    }

    fun configure(sourceId: String, dimension: DiscoverDimension, range: String, feedId: String? = null,
        categoryId: String? = null, standardCategories: Set<String> = emptySet(), force: Boolean = false) {
        val nextSources = manager.availableSources.value.filterIsInstance<ComicSource>().filter {
            it.isComicSource && (sourceId == "aggregate" || it.id == sourceId) }
        val signature = nextSources.sortedBy { it.id }.joinToString { "${it.id}@${System.identityHashCode(it)}" }
        val nextKey = "$sourceId:${dimension.name}:$range:${feedId.orEmpty()}:${categoryId.orEmpty()}:${standardCategories.sorted()}:$signature:v2"
        if (!force && nextKey == cacheKey) return
        rememberSession()
        job?.cancel()
        sources = nextSources
        selectedSource = sourceId
        query = DiscoverQuery(dimension, range, feedId = feedId, categoryId = categoryId, includeAdult = true,
            standardCategories = standardCategories)
        cacheKey = nextKey
        diskCacheKey = "$sourceId:${dimension.name}:$range:${feedId.orEmpty()}:${categoryId.orEmpty()}:${standardCategories.sorted()}:${nextSources.map { it.id }.sorted()}:${com.example.BuildConfig.VERSION_CODE}:v3"
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
            _state.value = DiscoverState(entries = cached, cached = cached.isNotEmpty(), loading = true, catalogs = catalogs.toMap())
            fetch(sources, append = false)
        }
    }
    fun refresh() {
        if (job?.isActive == true) return
        val now = System.currentTimeMillis()
        if (now - (refreshTimes[cacheKey] ?: 0L) < 3000) return
        refreshTimes[cacheKey] = now
        configure(selectedSource, query.dimension, query.range, query.feedId, query.categoryId, query.standardCategories, force = true)
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
    private suspend fun fetch(targets: List<ComicSource>, append: Boolean) = coroutineScope {
        val key = cacheKey
        val requested = query
        _state.update { it.copy(loading = true) }
        val completed = Channel<Triple<String, Int, SourceResult<DiscoverPage>>>(Channel.UNLIMITED)
        targets.forEach { source -> launch {
            val page = (pages[source.id] ?: 0) + 1
            val result = try { slots.withPermit {
                val dynamic = requested.standardCategories.isNotEmpty() || requested.categoryId?.startsWith("dynamic:") == true
                if (catalogOwners[source.id] !== source || (dynamic && source.id !in dynamicResolved && catalogs[source.id]?.dynamicCategories == true)) {
                    catalogFor(source, dynamic)
                }
                val catalog = catalogs[source.id]
                val routes = if (requested.categoryId == null) catalog?.routes(requested.standardCategories).orEmpty() else emptyList()
                if (requested.feedId == null && requested.categoryId == null && routes.isEmpty() &&
                    catalog?.supports(requested.dimension, requested.range) == false)
                    SourceResult.Success(DiscoverPage(supported = false, catalog = catalog))
                else withTimeout(50_000) { source.discover(requested.copy(page = page, cursor = cursors[source.id], categoryIds = routes,
                    offset = if (append) _state.value.entries.count { it.book.sourceId == source.id } else 0)) }
            } }
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
                    rows = (data.entries + rows).distinctBy { it.book.sourceId to it.book.id }
                        .sortedWith(compareBy<DiscoverEntry> { it.book.sourceId }.thenBy { it.nativeOrder ?: Int.MAX_VALUE })
                    data.catalog?.let { next ->
                        val previous = catalogs[id]
                        catalogs[id] = next.copy(dated = next.dated || previous?.dated == true,
                            categories = next.categories.ifEmpty { previous?.categories.orEmpty() },
                            capabilities = (previous?.capabilities.orEmpty().keys + next.capabilities.keys).associateWith { kind ->
                                (previous?.capabilities?.get(kind).orEmpty() + next.capabilities[kind].orEmpty()).distinct() })
                    }
                    errors.remove(id)
                    notices.remove(id)
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
            _state.value = DiscoverState(rows, true, more.toSet(), errors.toMap(), notices.toMap(), errors.isNotEmpty(), catalogs.toMap())
        }
        completed.close()
        _state.update { it.copy(loading = false) }
        rememberSession()
        val persistentKey = diskCacheKey
        if (rows.isNotEmpty() && store.flag("cache")) withContext(Dispatchers.IO) { store.writeCache(persistentKey, rows) }
    }
}
