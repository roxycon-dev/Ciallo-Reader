package com.example.library

import com.example.i18n.tr

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.download.*
import com.example.source.SearchBook
import com.example.ui.adaptive.AdaptiveSpec
import com.example.ui.components.AppIconButton
import com.example.ui.components.GlassCard
import com.example.ui.components.paperDismiss
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.glasskit.GlassManagementPage
import com.example.ui.wenku.*
import java.util.Locale

internal fun downloadBytes(bytes: Long): String = when {
    bytes < 0 -> tr("未知大小")
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
}

internal fun downloadStatusLabel(state: DownloadState): String = when (state) {
    DownloadState.Pending -> tr("准备下载 · 获取文件与可用线路")
    is DownloadState.Downloading -> if (state.progress >= 1f) tr("校验文件并存入书架…")
        else if (state.totalBytes > 0 || state.progress > 0) "下载中 · ${(state.progress * 100).toInt()}%" else tr("下载中 · 总大小未知")
    is DownloadState.Paused -> tr("已暂停")
    is DownloadState.Success -> tr("已完成 · 已存入书架")
    is DownloadState.Error -> tr("下载失败")
    DownloadState.Idle -> tr("等待下载")
}

@Composable
internal fun DownloadCenter(model: LibraryViewModel, onDismiss: () -> Unit) {
    val tasks by model.allDownloadTasks.collectAsStateWithLifecycle(emptyList())
    val preparing by model.preparingDownloads.collectAsStateWithLifecycle()
    val states by model.downloadStates.collectAsStateWithLifecycle()
    val comics by model.comicDownloadTasks.collectAsStateWithLifecycle()
    GlassManagementPage("下载任务", "ARCHIVE / TRANSFERS", onDismiss) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            NovelDownloadPanel(
                tasks, preparing, states, model::pauseDownload, model::resumeDownload,
                model::cancelDownload, { model.startDownload(it, it.format.takeIf(String::isNotBlank)) }, onDismiss,
                comics.values.toList(), model::pauseComicChapter,
                { id -> comics[id]?.let { model.downloadComicChapter(it.book, it.chapter) } }, model::cancelComicChapter,
                modifier = Modifier.widthIn(max = AdaptiveSpec.pageContentMaxWidth).fillMaxSize(),
                showHeader = false,
            )
        }
    }
}

@Composable
internal fun NovelDownloadPanel(
    tasks: List<DownloadTaskEntity>,
    preparations: Map<String, LibraryViewModel.DownloadPreparation>,
    states: Map<String, DownloadState>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (SearchBook) -> Unit,
    onDismiss: () -> Unit,
    comics: List<ComicDownloadTask> = emptyList(),
    onComicPause: (String) -> Unit = {},
    onComicResume: (String) -> Unit = {},
    onComicDelete: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    var deletion by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val sortedTasks = remember(tasks) {
        tasks.sortedWith(compareBy<DownloadTaskEntity> { it.status == DownloadStatus.COMPLETED }
            .thenByDescending { it.updatedAt })
    }
    val knownIds = remember(tasks) { tasks.map { it.id }.toSet() }
    val pending = remember(preparations, knownIds) { preparations.filterKeys { it !in knownIds }.entries.toList() }
    val count = pending.size + tasks.size + comics.size
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("download_task_panel"),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "overview") {
            Column(Modifier.fillMaxWidth().wenkuPagePart(1)) {
                if (showHeader) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("下载任务"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        AppIconButton(Icons.Default.Close, tr("收起"), onClick = onDismiss)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("离线任务"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("$count 项", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(tr("任务在后台继续，完成后可在书架阅读。"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        itemsIndexed(pending, key = { _, entry -> "prep:${entry.key}" }) { index, (id, prep) ->
            DownloadTaskRow(
                prep.book.title, prep.book.sourceId, prep.state,
                { onPause(id) }, { onResume(id) }, { deletion = prep.book.title to { onCancel(id) } }, { onRetry(prep.book) },
                modifier = Modifier.wenkuPagePart(index + 2),
            )
        }
        itemsIndexed(sortedTasks, key = { _, task -> "novel:${task.id}" }) { index, task ->
            val originalId = DownloadManager.originalBookId(task.id, task.sourceId)
            val id = DownloadManager.taskId(task.sourceId, originalId)
            val state = states[id] ?: when (task.status) {
                DownloadStatus.COMPLETED -> DownloadState.Success(task.filePath)
                DownloadStatus.FAILED -> DownloadState.Error(task.errorMessage ?: "下载失败")
                DownloadStatus.PAUSED -> DownloadState.Paused(task.downloadedBytes, task.totalBytes)
                DownloadStatus.DOWNLOADING -> DownloadState.Downloading(task.downloadedBytes, task.totalBytes, task.progress)
                else -> DownloadState.Pending
            }
            val book = SearchBook(id = originalId, sourceId = task.sourceId, title = task.title,
                author = task.author, format = task.format)
            DownloadTaskRow(
                task.title, "${task.format.uppercase(Locale.ROOT)} · ${task.sourceId}", state,
                { onPause(id) }, { onResume(id) }, { deletion = task.title to { onCancel(id) } }, { onRetry(book) },
                modifier = Modifier.wenkuPagePart(pending.size + index + 2),
            )
        }
        itemsIndexed(comics, key = { _, task -> "comic:${ComicDownloadManager.taskId(task.book, task.chapterId)}" }) { index, task ->
            val id = ComicDownloadManager.taskId(task.book, task.chapterId)
            val state = when (task.status) {
                ComicDownloadStatus.DOWNLOADING -> DownloadState.Downloading(0, 0, task.progress)
                ComicDownloadStatus.PAUSED -> DownloadState.Paused(0, 0)
                ComicDownloadStatus.FAILED -> DownloadState.Error(task.error ?: "下载失败")
                ComicDownloadStatus.SUCCESS -> DownloadState.Success("")
            }
            DownloadTaskRow(
                task.book.title, "${task.chapter.title} · ${task.book.sourceId}", state,
                { onComicPause(id) }, { onComicResume(id) }, { deletion = task.book.title to { onComicDelete(id) } },
                { onComicResume(id) }, modifier = Modifier.wenkuPagePart(pending.size + tasks.size + index + 2),
                completionLabel = tr("已完成 · 漫画章节可离线阅读"),
            )
        }
        if (count == 0) {
            item(key = "empty") {
                WenkuContentPanel(Modifier.fillMaxWidth().wenkuPagePart(2)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WenkuIcon(WenkuSymbol.CloudDownload, size = 24.dp, tint = MaterialTheme.colorScheme.primary, entrance = false)
                        Column {
                            Text(tr("暂无下载任务"), style = MaterialTheme.typography.titleSmall)
                            Text(tr("从书库或漫画章节开始下载。"), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    deletion?.let { (title, remove) ->
        WenkuActionSheet(tr("删除下载记录？"), onDismiss = { deletion = null }) {
            Text("“$title”的下载记录将被移除。未完成的任务会停止，已存入书架的书籍保留。")
            WenkuTextButton(
                "删除", onClick = paperDismiss(remove), modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error, wenkuSymbol = WenkuSymbol.Delete,
            )
            WenkuTextButton(
                com.example.i18n.tr("保留"), onClick = paperDismiss {}, modifier = Modifier.fillMaxWidth(),
                wenkuSymbol = WenkuSymbol.Close,
            )
        }
    }
}

@Composable
private fun DownloadTaskRow(
    title: String,
    subtitle: String,
    state: DownloadState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    completionLabel: String = "已完成 · 已存入书架",
) {
    val colors = MaterialTheme.colorScheme
    GlassCard(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant)
                Text(if (state is DownloadState.Success) completionLabel else downloadStatusLabel(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is DownloadState.Error) colors.error else colors.primary)
                when (state) {
                    is DownloadState.Downloading -> if (state.downloadedBytes > 0) {
                        val total = if (state.totalBytes > 0) downloadBytes(state.totalBytes) else tr("未知大小")
                        Text("${downloadBytes(state.downloadedBytes)} / $total", style = MaterialTheme.typography.labelSmall)
                    }
                    is DownloadState.Error -> Text(state.message, style = MaterialTheme.typography.labelSmall, color = colors.error)
                    else -> Unit
                }
            }
            when (state) {
                DownloadState.Pending -> Spacer(Modifier.size(48.dp).downloadRing(0f))
                is DownloadState.Downloading -> {
                    val known = state.totalBytes > 0 || state.progress > 0f
                    AppIconButton(
                        Icons.Default.Pause, "暂停", onClick = onPause,
                        modifier = Modifier.downloadRing(state.progress.takeIf { known }),
                        wenkuSymbol = WenkuSymbol.Pause, glyphSize = 16.dp,
                    )
                }
                is DownloadState.Paused -> AppIconButton(Icons.Default.PlayArrow, tr("继续"), onClick = onResume,
                    wenkuSymbol = WenkuSymbol.Play)
                is DownloadState.Error -> AppIconButton(Icons.Default.Refresh, tr("重新获取并重试"), onClick = onRetry,
                    wenkuSymbol = WenkuSymbol.Refresh)
                is DownloadState.Success -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    WenkuIcon(WenkuSymbol.Check, size = 20.dp, tint = colors.primary)
                }
                DownloadState.Idle -> Unit
            }
            AppIconButton(Icons.Default.Delete, tr("删除记录"), onClick = onDelete, wenkuSymbol = WenkuSymbol.Delete)
        }
    }
}

@Composable
internal fun Modifier.downloadRing(progress: Float?): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val color = MaterialTheme.colorScheme.primary
    val fill = animateFloatAsState(
        targetValue = (progress ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(if (reduceMotion) 0 else 240, easing = EaseOutCubic),
        label = "downloadRingFill",
    )
    val turn = if (progress == null && !reduceMotion) {
        rememberInfiniteTransition(label = "downloadRingTurn").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "downloadRingAngle",
        )
    } else null
    return drawBehind {
        val diameter = 25.dp.toPx()
        val topLeft = center - Offset(diameter / 2, diameter / 2)
        val stroke = Stroke(1.65.dp.toPx(), cap = StrokeCap.Round)
        val size = Size(diameter, diameter)
        drawArc(color.copy(alpha = .18f), -84f, 348f, false, topLeft, size, style = stroke)
        val sweep = if (progress == null) 80f else 348f * fill.value
        if (sweep > 0f) drawArc(color, -84f + (turn?.value ?: 0f), sweep, false, topLeft, size, style = stroke)
    }
}
