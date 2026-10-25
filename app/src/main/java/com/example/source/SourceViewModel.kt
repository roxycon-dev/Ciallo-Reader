package com.example.source

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.source.importer.SourceImporter
import com.example.source.js.JsSourceRepo
import com.example.source.storage.SharedPreferencesSourceStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SourceViewModel(
    application: Application,
    val sourceManager: SourceManager
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        SourceManager(SharedPreferencesSourceStorage(application))
    )

    val allSources: StateFlow<List<BookSource>> = sourceManager.allSources
    val availableSources: StateFlow<List<BookSource>> = sourceManager.availableSources
    val activeSource: StateFlow<BookSource?> = sourceManager.activeSource
    val enabledStates: StateFlow<Map<String, Boolean>> = sourceManager.enabledStates

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            sourceManager.initialize()
        }
    }

    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    fun enableSource(id: String) {
        viewModelScope.launch {
            sourceManager.setSourceEnabled(id, true)
        }
    }

    fun disableSource(id: String) {
        viewModelScope.launch {
            sourceManager.setSourceEnabled(id, false)
        }
    }

    fun setActiveSource(id: String) {
        viewModelScope.launch {
            sourceManager.setActiveSource(id)
        }
    }

    fun isSourceEnabled(id: String): Boolean {
        return sourceManager.isSourceEnabled(id)
    }

    fun isCustomSource(id: String): Boolean {
        return sourceManager.isCustomSource(id)
    }

    fun removeSource(id: String) {
        viewModelScope.launch {
            sourceManager.unregisterSource(id)
        }
    }

    fun importSourceFromUri(uri: Uri) {
        viewModelScope.launch {
            val result = SourceImporter.importBatchFromUri(getApplication(), uri)
            if (result.imported.isEmpty()) {
                _importStatus.value = "导入失败: ${result.skipped.firstOrNull()?.second ?: "未找到可导入的书源"}"
            } else {
                result.imported.forEach { (source, rawJson) ->
                    sourceManager.registerSource(source, defaultEnabled = true, rawJson = rawJson)
                }
                _importStatus.value = buildImportStatus(result)
            }
        }
    }

    fun importSourceFromJsonString(jsonStr: String) {
        viewModelScope.launch {
            val result = SourceImporter.importBatchFromJsonString(jsonStr)
            if (result.imported.isEmpty()) {
                _importStatus.value = "导入失败: ${result.skipped.firstOrNull()?.second ?: "未找到可导入的书源"}"
            } else {
                result.imported.forEach { (source, rawJson) ->
                    sourceManager.registerSource(source, defaultEnabled = true, rawJson = rawJson)
                }
                _importStatus.value = buildImportStatus(result)
            }
        }
    }

    fun importSourceFromUrl(url: String) {
        viewModelScope.launch {
            _importStatus.value = "正在从网络获取书源..."
            val result = SourceImporter.importBatchFromUrl(url)
            if (result.imported.isEmpty()) {
                _importStatus.value = "网络导入失败: ${result.skipped.firstOrNull()?.second ?: "未找到可导入的书源"}"
            } else {
                result.imported.forEach { (source, rawJson) ->
                    sourceManager.registerSource(source, defaultEnabled = true, rawJson = rawJson)
                }
                _importStatus.value = buildImportStatus(result)
            }
        }
    }

    private fun buildImportStatus(result: SourceImporter.BatchImportResult): String {
        val base = "成功导入 ${result.importedCount} 个书源"
        return if (result.skippedCount > 0) {
            val firstSkipped = result.skipped.first().second
            "$base，跳过 ${result.skippedCount} 个（${firstSkipped}）"
        } else {
            base
        }
    }

    fun clearImportStatus() {
        _importStatus.value = null
    }

    /** 刷新 Venera 源仓库：移除现有 JS 源并从远程仓库重新安装。 */
    fun refreshJsSources() {
        viewModelScope.launch {
            _importStatus.value = "正在刷新 JS 源仓库…"
            val prefs = com.example.data.PreferencesManager(getApplication())
            val sources = JsSourceRepo.install(
                context = getApplication(),
                repoUrl = prefs.jsSourceRepoUrl,
                includeAdult = prefs.showAdultSources
            )
            if (sources.isNotEmpty()) {
                sourceManager.allSources.value
                    .filter { it.id.startsWith("js_") }
                    .forEach { sourceManager.unregisterSource(it.id) }
                sources.forEach { source ->
                    sourceManager.registerSource(source, defaultEnabled = true)
                }
                _importStatus.value = "源仓库刷新完成，共 ${sources.size} 个漫画源"
            } else {
                _importStatus.value = "源仓库刷新失败或仓库为空，请检查网络后重试"
            }
        }
    }

    /** Legacy callers can refresh the full catalog; the adult-content gate is retired. */
    fun setAdultSourcesEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) = refreshJsSources()
}
