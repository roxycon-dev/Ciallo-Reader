package com.example.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val slots = Semaphore(3)

    fun configure(sourceId: String, dimension: DiscoverDimension, range: String, feedId: String? = null, categoryId: String? = null) {
        val adult = store.flag("adult", false)
        val nextKey = "$sourceId:${dimension.name}:$range:${feedId.orEmpty()}:${categoryId.orEmpty()}:$adult"
        val previousRows = if (cacheKey == nextKey) _state.value.entries else emptyList()
        job?.cancel()
        sources = manager.availableSources.value.filterIsInstance<ComicSource>().filter {
            it.isComicSource && (store.flag("adult", false) || !isAdultDiscoverSource(it)) &&
                (sourceId == "aggregate" || it.id == sourceId) }
        selectedSource = sourceId
        query = DiscoverQuery(dimension, range, feedId = feedId, categoryId = categoryId, includeAdult = adult)
        cacheKey = nextKey
        pages.clear(); cursors.clear(); cursorHistory.clear(); noProgress.clear()
        _state.value = DiscoverState(entries = previousRows, loading = true, catalogs = catalogs.toMap())
        job = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val start = if (dimension == DiscoverDimension.UPDATES && feedId == null && categoryId == null) DiscoverEngine.rangeStart(range, now) else null
            val saved = if (store.flag("cache")) withContext(Dispatchers.IO) { store.readCache(cacheKey) } else emptyList()
            val cached = previousRows.ifEmpty { saved }.filter { e -> sources.any { it.id == e.book.sourceId } &&
                (start == null || (e.updatedAt != null && e.updatedAt >= start && e.updatedAt <= now)) }
            _state.value = DiscoverState(entries = cached, cached = cached.isNotEmpty(), loading = true, catalogs = catalogs.toMap())
            fetch(sources, append = false)
        }
    }
    fun refresh() = configure(selectedSource, query.dimension, query.range, query.feedId, query.categoryId)
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
        _state.update { it.copy(loading = true) }
        val completed = Channel<Triple<String, Int, SourceResult<DiscoverPage>>>(Channel.UNLIMITED)
        targets.forEach { source -> launch {
            val page = (pages[source.id] ?: 0) + 1
            val result = try { slots.withPermit { withTimeout(25_000) {
                if (source.id !in catalogs || (query.feedId != null && catalogs[source.id]?.dynamicCategories == true)) {
                    val catalog = source.discoverCatalog(resolveDynamic = query.feedId != null || query.categoryId != null)
                    if (catalog is SourceResult.Success) catalogs[source.id] = catalog.data
                }
                source.discover(query.copy(page = page, cursor = cursors[source.id],
                    offset = if (append) _state.value.entries.count { it.book.sourceId == source.id } else 0))
            } } }
                catch (_: TimeoutCancellationException) { SourceResult.Error(SourceException.ParseError("请求超时，点击重试")) }
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
                    data.catalog?.let { if (it.categories.isNotEmpty() || catalogs[id]?.categories.isNullOrEmpty()) catalogs[id] = it }
                    errors.remove(id)
                    notices.remove(id)
                    data.notice?.let { notices[id] = it }
                    if (!data.supported) notices[id] = data.notice ?: "未提供此维度数据"
                    val repeatedCursor = data.nextCursor?.let { !cursorHistory.getOrPut(id) { mutableSetOf() }.add(it) } == true
                    cursors[id] = data.nextCursor
                    val stalled = (noProgress[id] ?: 0) >= 2 || repeatedCursor
                    if (stalled) notices[id] = "原站连续返回重复页，已停止加载；可下拉刷新"
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
        if (errors.isEmpty() && store.flag("cache")) withContext(Dispatchers.IO) { store.writeCache(cacheKey, rows) }
    }
}
