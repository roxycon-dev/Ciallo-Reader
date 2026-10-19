package com.example.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import com.example.ui.design.DesignTokens
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.download.*
import com.example.source.SearchBook
import java.util.Locale

internal fun downloadBytes(bytes: Long): String = when {
    bytes < 0 -> "未知大小"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
}

internal fun downloadStatusLabel(state: DownloadState): String = when (state) {
    DownloadState.Pending -> "准备下载 · 获取文件与可用线路"
    is DownloadState.Downloading -> if (state.progress >= 1f) "校验文件并存入书架…"
        else if (state.totalBytes > 0) "下载中 · ${(state.progress * 100).toInt()}%" else "下载中 · 总大小未知"
    is DownloadState.Paused -> "已暂停"
    is DownloadState.Success -> "已完成 · 已存入书架"
    is DownloadState.Error -> "下载失败"
    DownloadState.Idle -> "等待下载"
}

@Composable
internal fun NovelDownloadPanel(
    tasks: List<DownloadTaskEntity>, preparations: Map<String, LibraryViewModel.DownloadPreparation>,
    states: Map<String, DownloadState>, onPause: (String) -> Unit, onResume: (String) -> Unit,
    onCancel: (String) -> Unit, onRetry: (SearchBook) -> Unit,
    onDismiss: () -> Unit, comicContent: (@Composable () -> Unit)? = null
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .78f
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
        .padding(DesignTokens.SpaceLg).testTag("download_task_panel"), verticalArrangement = Arrangement.spacedBy(DesignTokens.SpaceMd)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("下载任务", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = onDismiss) { Text("收起") }
        }
        Text("完成后自动存入书架；收起面板后可继续下载。", style = MaterialTheme.typography.bodySmall)
        if (tasks.isEmpty() && preparations.isEmpty() && comicContent == null) Text("暂无下载任务")
        val taskKeys = tasks.map { DownloadManager.taskId(it.sourceId, DownloadManager.originalBookId(it.id, it.sourceId)) }.toSet()
        preparations.filterKeys { it !in taskKeys }.forEach { (key, prep) ->
            DownloadTaskRow(prep.book, prep.state, null, { onPause(key) }, { onResume(key) },
                { onCancel(key) }, { onRetry(prep.book) })
        }
        tasks.sortedWith(compareBy<DownloadTaskEntity> { it.status == DownloadStatus.COMPLETED }.thenByDescending { it.updatedAt }).forEach { task ->
            val key = DownloadManager.taskId(task.sourceId, DownloadManager.originalBookId(task.id, task.sourceId))
            val book = SearchBook(id = DownloadManager.originalBookId(task.id, task.sourceId), sourceId = task.sourceId,
                title = task.title, author = task.author, cover = task.coverUrl, format = task.format)
            val state = states[key] ?: DownloadState.Pending
            DownloadTaskRow(book, state, task, { onPause(key) }, { onResume(key) },
                { onCancel(key) }, { onRetry(book) })
        }
        comicContent?.invoke()
    }
    }
}

@Composable
private fun DownloadTaskRow(book: SearchBook, state: DownloadState, task: DownloadTaskEntity?,
    onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(DesignTokens.SpaceLg), verticalArrangement = Arrangement.spacedBy(DesignTokens.SpaceSm)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${book.format.uppercase().ifBlank { "正在获取格式" }} · ${if (book.sourceId == "zlibrary") "Z-Library" else book.sourceId}",
                style = MaterialTheme.typography.bodySmall)
            Text(downloadStatusLabel(state), color = if (state is DownloadState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium)
            when (state) {
                DownloadState.Pending -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is DownloadState.Downloading -> {
                    if (state.totalBytes > 0 || state.progress > 0) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${downloadBytes(state.downloadedBytes)} / ${if (state.totalBytes > 0) downloadBytes(state.totalBytes) else "未知大小"}")
                }
                is DownloadState.Paused -> Text("${downloadBytes(state.downloadedBytes)} / ${if (state.totalBytes > 0) downloadBytes(state.totalBytes) else "未知大小"}")
                is DownloadState.Error -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                is DownloadState.Success -> task?.let { Text("文件：${java.io.File(it.filePath).name}\n大小：${downloadBytes(it.downloadedBytes)}", style = MaterialTheme.typography.bodySmall) }
                else -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpaceSm)) {
                when (state) {
                    is DownloadState.Downloading -> TextButton(onClick = onPause) { Text("暂停") }
                    is DownloadState.Paused -> TextButton(onClick = onResume) { Text("继续") }
                    is DownloadState.Error -> TextButton(onClick = onRetry) { Text("重新获取并重试") }
                    else -> Unit
                }
                if (state !is DownloadState.Success) TextButton(onClick = onCancel) { Text("取消任务") }
            }
        }
    }
}
