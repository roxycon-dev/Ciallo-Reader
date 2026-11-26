package com.example.source

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.source.importer.SourceImporter
import com.example.source.js.JsSourceRepo
import com.example.source.js.ImportedComicSources
import com.example.source.js.JsComicSource
import com.example.i18n.tr
import com.example.source.storage.SharedPreferencesSourceStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    val ordering = sourceManager.ordering

    fun setSortMode(mode: SourceSortMode) = viewModelScope.launch {
        sourceManager.setSortMode(mode)
    }

    fun saveCustomOrder(ids: List<String>) = viewModelScope.launch {
        sourceManager.saveCustomOrder(ids)
    }

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            sourceManager.initialize()
        }
    }

    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    internal data class ComicImport(
        val candidates: List<ImportedComicSources.Candidate> = emptyList(),
        val busy: Boolean = true,
        val overflow: Int = 0,
    )

    private val _comicImport = MutableStateFlow<ComicImport?>(null)
    internal val comicImport = _comicImport.asStateFlow()

    fun previewComicSources(files: List<Uri>) {
        if (files.isEmpty() || _comicImport.value?.busy == true) return
        _comicImport.value = ComicImport()
        viewModelScope.launch {
            val candidates = ImportedComicSources.inspect(getApplication(), files)
            val seen = HashSet<String>()
            _comicImport.value = ComicImport(candidates.map { candidate ->
                val key = candidate.definition?.key
                if (key == null || seen.add(key)) candidate
                else candidate.copy(definition = null, error = tr("同一批次已包含这个源"))
            }, busy = false, overflow = (files.distinct().size - ImportedComicSources.MAX_BATCH).coerceAtLeast(0))
        }
    }

    fun dismissComicImport() {
        if (_comicImport.value?.busy == false) _comicImport.value = null
    }

    fun installComicSources(ids: Set<String>) {
        val preview = _comicImport.value ?: return
        if (preview.busy || ids.isEmpty()) return
        _comicImport.value = preview.copy(busy = true)
        viewModelScope.launch {
            var installed = 0
            val failures = mutableListOf<String>()
            for (candidate in preview.candidates) {
                val definition = candidate.definition?.takeIf { it.id in ids } ?: continue
                try {
                    val source = withContext(Dispatchers.IO) {
                        ImportedComicSources.install(getApplication(), definition, replace = candidate.installedVersion != null)
                    } ?: error(tr("这个源已安装"))
                    sourceManager.registerSource(source)
                    installed++
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failures.add(definition.name) }
            }
            _comicImport.value = null
            _importStatus.value = when {
                failures.isEmpty() -> tr("已导入 %d 个漫画源").format(installed)
                installed == 0 -> tr("漫画源导入失败") + " · " + failures.joinToString("、")
                else -> tr("已导入 %d 个漫画源，%d 个失败").format(installed, failures.size) + " · " + failures.joinToString("、")
            }
        }
    }

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
            if ((sourceManager.getSource(id) as? JsComicSource)?.isUserImported == true) {
                try { withContext(Dispatchers.IO) { ImportedComicSources.remove(getApplication(), id) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { _importStatus.value = tr("删除漫画源失败"); return@launch }
            }
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
                val installedIds = sources.mapTo(hashSetOf()) { it.id }
                sourceManager.allSources.value
                    .filterIsInstance<JsComicSource>()
                    .filter { !it.isUserImported && it.id !in installedIds }
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
