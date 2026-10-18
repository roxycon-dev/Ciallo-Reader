package com.example.source

import com.example.source.impl.JsonBookSource
import com.example.source.importer.SourceImporter
import com.example.source.storage.SourceStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

class SourceManager(private val storage: SourceStorage? = null) {
    private val sourcesMap = LinkedHashMap<String, BookSource>()
    private val enabledStatesMap = mutableMapOf<String, Boolean>()
    private val customJsonsMap = mutableMapOf<String, String>()

    private val _allSources = MutableStateFlow<List<BookSource>>(emptyList())
    val allSources: StateFlow<List<BookSource>> = _allSources.asStateFlow()

    private val _enabledStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val enabledStates: StateFlow<Map<String, Boolean>> = _enabledStates.asStateFlow()

    private val _availableSources = MutableStateFlow<List<BookSource>>(emptyList())
    val availableSources: StateFlow<List<BookSource>> = _availableSources.asStateFlow()

    private val _activeSource = MutableStateFlow<BookSource?>(null)
    val activeSource: StateFlow<BookSource?> = _activeSource.asStateFlow()

    private val initialized = AtomicBoolean(false)
    private val mutex = Mutex()

    suspend fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        mutex.withLock {
            loadPersistedDataInternal()
        }
    }

    suspend fun loadPersistedData() {
        initialize()
    }

    /** Refresh only local definitions after a portable import; no network installation. */
    suspend fun reloadAfterTransfer() {
        mutex.withLock { loadPersistedDataInternal() }
    }

    private suspend fun loadPersistedDataInternal() {
        storage?.let { store ->
            val savedStates = store.getSourceStates()
            enabledStatesMap.putAll(savedStates)
            _enabledStates.value = enabledStatesMap.toMap()

            // Load user-imported custom sources
            val customJsons = store.getCustomSourceJsons()
            customJsons.forEach { (sourceId, jsonStr) ->
                when (val result = SourceImporter.importFromJsonString(jsonStr)) {
                    is SourceResult.Success -> {
                        sourcesMap[sourceId] = result.data
                        customJsonsMap[sourceId] = jsonStr
                    }
                    else -> {}
                }
            }

            val savedActiveId = store.getActiveSourceId()
            updateFlowsInternal()

            if (!savedActiveId.isNullOrEmpty() && sourcesMap.containsKey(savedActiveId) && isSourceEnabledInternal(savedActiveId)) {
                _activeSource.value = sourcesMap[savedActiveId]
            } else if (_activeSource.value == null) {
                _activeSource.value = _availableSources.value.firstOrNull()
            }
        }
    }

    suspend fun registerSource(source: BookSource, defaultEnabled: Boolean = true, rawJson: String? = null) {
        mutex.withLock {
            sourcesMap[source.id] = source
            if (!enabledStatesMap.containsKey(source.id)) {
                enabledStatesMap[source.id] = defaultEnabled
                _enabledStates.value = enabledStatesMap.toMap()
            }
            if (rawJson != null) {
                customJsonsMap[source.id] = rawJson
                storage?.saveCustomSourceJson(source.id, rawJson)
            }
            updateFlowsInternal()
            if (_activeSource.value == null && isSourceEnabledInternal(source.id)) {
                setActiveSourceInternal(source.id)
            }
        }
    }

    suspend fun addSource(source: BookSource) {
        registerSource(source, defaultEnabled = true)
    }

    suspend fun registerCustomSource(source: BookSource, rawJson: String? = null) {
        registerSource(source, defaultEnabled = true, rawJson = rawJson)
    }

    suspend fun unregisterCustomSource(sourceId: String) {
        unregisterSource(sourceId)
    }

    suspend fun unregisterSource(sourceId: String) {
        mutex.withLock {
            sourcesMap.remove(sourceId)
            enabledStatesMap.remove(sourceId)
            _enabledStates.value = enabledStatesMap.toMap()
            customJsonsMap.remove(sourceId)
            storage?.removeCustomSourceJson(sourceId)
            if (_activeSource.value?.id == sourceId) {
                _activeSource.value = _availableSources.value.firstOrNull { it.id != sourceId }
            }
            updateFlowsInternal()
        }
    }

    suspend fun removeSource(sourceId: String) {
        unregisterSource(sourceId)
    }

    suspend fun setActiveSource(sourceId: String) {
        mutex.withLock {
            setActiveSourceInternal(sourceId)
        }
    }

    private suspend fun setActiveSourceInternal(sourceId: String) {
        val source = sourcesMap[sourceId]
        if (source != null && isSourceEnabledInternal(sourceId)) {
            _activeSource.value = source
            storage?.saveActiveSourceId(sourceId)
        }
    }

    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        mutex.withLock {
            enabledStatesMap[sourceId] = enabled
            _enabledStates.value = enabledStatesMap.toMap()
            storage?.saveSourceState(sourceId, enabled)
            updateFlowsInternal()
            if (!enabled && _activeSource.value?.id == sourceId) {
                _activeSource.value = _availableSources.value.firstOrNull()
                _activeSource.value?.let { setActiveSourceInternal(it.id) }
            }
        }
    }

    fun getSource(id: String): BookSource? {
        return sourcesMap[id]
    }

    fun isSourceEnabled(id: String): Boolean {
        return enabledStatesMap[id] ?: true
    }

    private fun isSourceEnabledInternal(id: String): Boolean {
        return enabledStatesMap[id] ?: true
    }

    fun isCustomSource(sourceId: String): Boolean {
        val src = sourcesMap[sourceId]
        return (src is JsonBookSource && src.config.isCustom) || customJsonsMap.containsKey(sourceId)
    }

    private fun updateFlowsInternal() {
        val allList = sourcesMap.values.toList()
        _allSources.update { allList }
        val availableList = allList.filter { isSourceEnabledInternal(it.id) }
        _availableSources.update { availableList }
        if (_activeSource.value != null && !availableList.contains(_activeSource.value)) {
            _activeSource.value = availableList.firstOrNull()
        } else if (_activeSource.value == null && availableList.isNotEmpty()) {
            _activeSource.value = availableList.first()
        }
    }
}
