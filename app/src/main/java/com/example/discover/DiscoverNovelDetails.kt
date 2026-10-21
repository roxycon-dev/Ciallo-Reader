package com.example.discover

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.library.LibraryViewModel
import com.example.library.NovelDetailSheet
import com.example.source.SearchBook
import com.example.source.SourceResult
import com.example.download.DownloadState
import kotlinx.coroutines.CancellationException

@Composable
internal fun DiscoverNovelDetails(book: SearchBook?, model: LibraryViewModel,
    onDismiss: () -> Unit, onRead: (com.example.data.Book) -> Unit) {
    if (book == null) return
    val context = LocalContext.current
    var detail by remember(book.sourceId, book.id) { mutableStateOf(book) }
    var loading by remember(book.sourceId, book.id) { mutableStateOf(true) }
    var error by remember(book.sourceId, book.id) { mutableStateOf<String?>(null) }
    var local by remember(book.sourceId, book.id) { mutableStateOf<com.example.data.Book?>(null) }
    val sources by model.sourceManager.availableSources.collectAsStateWithLifecycle()
    val downloads by model.downloadStates.collectAsStateWithLifecycle()
    val task = com.example.download.DownloadManager.taskId(book.sourceId, book.id)
    val state = downloads[task] ?: DownloadState.Idle
    LaunchedEffect(book.sourceId, book.id) {
        try {
            when (val result = model.novelDetail(book)) {
                is SourceResult.Success -> detail = result.data
                is SourceResult.Error -> error = result.exception.message ?: "详情暂时无法加载"
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "详情暂时无法加载" }
        finally { loading = false }
    }
    LaunchedEffect(book.sourceId, book.id, state is DownloadState.Success) { local = model.localNovel(book) }
    NovelDetailSheet(detail, sources.firstOrNull { it.id == book.sourceId }?.name.orEmpty(),
        coil.Coil.imageLoader(context), loading, error, state, hasLocal = local != null,
        onDismiss = onDismiss, onDownload = { model.startDownload(detail, detail.format) },
        onRead = { local?.let(onRead) },
        onPause = { model.pauseDownload(task) }, onResume = { model.resumeDownload(task) }, onCancel = { model.cancelDownload(task) })
}
