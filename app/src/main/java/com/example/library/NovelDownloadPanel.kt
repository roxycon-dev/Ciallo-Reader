package com.example.library

import com.example.i18n.tr

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.download.*
import com.example.source.BookSource
import com.example.source.SearchBook
import com.example.ui.components.AppIconButton
import com.example.ui.components.GlassCard
import com.example.ui.components.pullOutCombinedClickable
import com.example.ui.favorite.FavoriteCover
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
    DownloadState.Pending -> tr("准备下载")
    is DownloadState.Downloading -> if (state.progress >= 1f) tr("校验入库…")
        else if (state.totalBytes > 0 || state.progress > 0) "${tr("下载中")} · ${(state.progress * 100).toInt()}%" else tr("下载中")
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
    val sources by model.sourceManager.allSources.collectAsStateWithLifecycle()
    val covers by model.downloadedCovers.collectAsStateWithLifecycle(emptyMap())
    val context = LocalContext.current
    val localCovers = remember(comics.keys, covers, context) {
        covers + comics.keys.mapNotNull { id ->
            covers[ComicDownloadManager.directory(context, id).absolutePath]?.let { id to it }
        }
    }
    val selected = rememberSaveable { mutableStateOf(emptyList<String>()) }
    val selecting = rememberSaveable { mutableStateOf(false) }
    GlassManagementPage(tr("下载任务"), "ARCHIVE / TRANSFERS", {
        if (selecting.value) {
            selected.value = emptyList()
            selecting.value = false
        } else onDismiss()
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            NovelDownloadPanel(
                tasks, preparing, states, model::pauseDownload, model::resumeDownload,
                model::cancelDownload, { model.startDownload(it, it.format.takeIf(String::isNotBlank)) }, onDismiss,
                comics.values.toList(), model::pauseComicChapter,
                { id -> comics[id]?.let { model.downloadComicChapter(it.book, it.chapter) } }, model::cancelComicChapter,
                modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize(),
                showHeader = false,
                sourceNames = sources.associate { it.id to it.name },
                sources = sources.associateBy { it.id },
                localCovers = localCovers,
                selection = selected,
                selectionMode = selecting,
            )
        }
    }
}

private data class TransferRow(
    val key: String,
    val id: String,
    val book: SearchBook,
    val state: DownloadState,
    val chapter: String? = null,
    val localCover: String? = null,
    val isComic: Boolean = false,
)

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    sourceNames: Map<String, String> = emptyMap(),
    sources: Map<String, BookSource> = emptyMap(),
    localCovers: Map<String, String> = emptyMap(),
    selection: MutableState<List<String>> = rememberSaveable { mutableStateOf(emptyList()) },
    selectionMode: MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
) {
    val rows = remember(tasks, preparations, states, comics, localCovers) {
        val knownIds = tasks.mapTo(hashSetOf()) { DownloadManager.taskId(it.sourceId, DownloadManager.originalBookId(it.id, it.sourceId)) }
        buildList {
            preparations.forEach { (id, prep) ->
                if (id !in knownIds) add(TransferRow("novel:$id", id, prep.book, prep.state))
            }
            tasks.forEach { task ->
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
                    author = task.author, cover = task.coverUrl, format = task.format)
                // A legacy row keeps its actual database ID for deletion, even when its progress uses a canonical ID.
                val key = if (task.id != id && tasks.any { it.id == id }) task.id else id
                add(TransferRow("novel:$key", task.id, book, state,
                    localCover = localCovers[task.filePath.removePrefix("file://")]))
            }
            comics.forEach { task ->
                val id = ComicDownloadManager.taskId(task.book, task.chapterId)
                val state = when (task.status) {
                    ComicDownloadStatus.DOWNLOADING -> DownloadState.Downloading(0, 0, task.progress)
                    ComicDownloadStatus.PAUSED -> DownloadState.Paused(0, 0)
                    ComicDownloadStatus.FAILED -> DownloadState.Error(task.error ?: "下载失败")
                    ComicDownloadStatus.SUCCESS -> DownloadState.Success("")
                }
                add(TransferRow(id, id, task.book, state, task.chapter.title, localCovers[id], isComic = true))
            }
        }.sortedWith(compareBy<TransferRow> { it.state is DownloadState.Success }.thenBy { it.key })
    }
    val keys = remember(rows) { rows.map { it.key } }
    val selected = selection.value.toSet().intersect(keys.toSet())
    val selecting = selectionMode.value
    val allSelected = selecting && selected.size == rows.size
    var deletion by remember { mutableStateOf<List<TransferRow>>(emptyList()) }
    var closingDeletion by remember { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current

    fun exitSelection() {
        selection.value = emptyList()
        selectionMode.value = false
    }

    fun toggle(key: String) {
        selectionMode.value = true
        selection.value = (if (key in selected) selected - key else selected + key).toList()
    }

    BackHandler(selecting && deletion.isEmpty()) { exitSelection() }
    LaunchedEffect(rows.isEmpty()) { if (rows.isEmpty()) exitSelection() }

    Column(modifier.fillMaxWidth()) {
        if (showHeader) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("下载任务"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                AppIconButton(Icons.Default.Close, tr("收起"), onClick = onDismiss)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val columns = if (maxWidth >= 840.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.3f) 2 else 1
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().testTag("download_task_panel"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "overview", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (selecting) "${tr("已选择")} ${selected.size} / ${rows.size}" else "${rows.size} ${tr("项")}",
                            Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AppIconButton(
                            if (selecting) Icons.Default.Close else Icons.Default.CheckCircle,
                            if (selecting) tr("退出多选") else tr("多选"),
                            onClick = { if (selecting) exitSelection() else selectionMode.value = true },
                            enabled = rows.isNotEmpty(),
                            wenkuSymbol = if (selecting) WenkuSymbol.Close else WenkuSymbol.Selected,
                        )
                    }
                }
                items(rows, key = TransferRow::key) { row ->
                    val sourceName = sourceNames[row.book.sourceId]
                    val detail = row.chapter ?: row.book.format.uppercase(Locale.ROOT).takeIf { it.isNotBlank() }
                    DownloadTaskRow(
                        row, listOfNotNull(detail, sourceName).joinToString(" · "), sources[row.book.sourceId],
                        selecting, row.key in selected, { toggle(row.key) },
                        onPause = { if (row.isComic) onComicPause(row.id) else onPause(row.id) },
                        onResume = { if (row.isComic) onComicResume(row.id) else onResume(row.id) },
                        onRetry = { if (row.isComic) onComicResume(row.id) else onRetry(row.book) },
                        onDelete = { deletion = listOf(row) },
                        modifier = if (reduceMotion) Modifier else Modifier.animateItem(),
                    )
                }
                if (rows.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    WenkuContentPanel(Modifier.fillMaxWidth().wenkuPagePart(1)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            WenkuIcon(WenkuSymbol.CloudDownload, size = 24.dp, tint = MaterialTheme.colorScheme.primary, entrance = false)
                            Text(tr("暂无下载任务"), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(selecting, enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandVertically(),
            exit = if (reduceMotion) ExitTransition.None else fadeOut() + shrinkVertically()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WenkuTextButton(
                    if (allSelected) tr("取消全选") else tr("全选"),
                    { selection.value = if (allSelected) emptyList() else keys },
                    selected = allSelected,
                    wenkuSymbol = if (allSelected) WenkuSymbol.Selected else WenkuSymbol.CheckCircle,
                )
                WenkuTextButton(tr("删除所选"), { deletion = rows.filter { it.key in selected } },
                    enabled = selected.isNotEmpty(), color = MaterialTheme.colorScheme.error, wenkuSymbol = WenkuSymbol.Delete)
            }
        }
    }
    if (deletion.isNotEmpty()) {
        WenkuActionSheet(
            tr("删除下载记录？"),
            onDismiss = { deletion = emptyList(); closingDeletion = false },
            requestDismiss = closingDeletion,
            dismissEnabled = !closingDeletion,
        ) {
            Text(
                if (deletion.size == 1) deletion.single().book.title else "${deletion.size} ${tr("条下载记录")}",
                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(tr("未完成的任务会停止，已存入书架的书籍保留。"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WenkuTextButton(
                tr("删除"), onClick = {
                    if (!closingDeletion) {
                        closingDeletion = true
                        deletion.forEach { if (it.isComic) onComicDelete(it.id) else onCancel(it.id) }
                        if (selecting) exitSelection()
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = !closingDeletion,
                color = MaterialTheme.colorScheme.error, wenkuSymbol = WenkuSymbol.Delete,
            )
            WenkuTextButton(tr("保留"), onClick = { closingDeletion = true }, modifier = Modifier.fillMaxWidth(),
                wenkuSymbol = WenkuSymbol.Close, enabled = !closingDeletion)
        }
    }
}

@Composable
private fun DownloadTaskRow(
    row: TransferRow,
    subtitle: String,
    source: BookSource?,
    selecting: Boolean,
    checked: Boolean,
    onSelect: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = row.state
    val colors = MaterialTheme.colorScheme
    val reduceMotion = LocalReduceMotion.current
    val tint by animateColorAsState(if (checked) colors.primaryContainer else colors.surface.copy(alpha = .82f),
        if (reduceMotion) snap() else tween(180), label = "transferSelection")
    GlassCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), tint = tint) {
        Row(
            Modifier.fillMaxWidth()
                .pullOutCombinedClickable(onClick = { if (selecting) onSelect() }, onLongClick = onSelect)
                .semantics { if (selecting) selected = checked }
                .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp, 62.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant),
                contentAlignment = Alignment.Center) {
                WenkuIcon(if (row.isComic) WenkuSymbol.Image else WenkuSymbol.Book,
                    size = 18.dp, tint = colors.onSurfaceVariant, entrance = false)
                FavoriteCover(row.localCover, row.book.cover, row.book.sourceId, source, row.book.title, Modifier.matchParentSize())
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                if (subtitle.isNotEmpty()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                val status = when {
                    state is DownloadState.Success -> tr("已完成")
                    state is DownloadState.Error -> DownloadWorker.failureMessage(state.message)
                    else -> downloadStatusLabel(state)
                }
                Text(status, style = MaterialTheme.typography.labelSmall, maxLines = if (state is DownloadState.Error) 2 else 1,
                    overflow = TextOverflow.Ellipsis, color = if (state is DownloadState.Error) colors.error else colors.primary)
                if (state is DownloadState.Downloading && state.downloadedBytes > 0 && state.totalBytes > 0) {
                    Text("${downloadBytes(state.downloadedBytes)} / ${downloadBytes(state.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            AnimatedContent(selecting, transitionSpec = {
                if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(tween(140)) + scaleIn(tween(180), initialScale = .88f)) togetherWith fadeOut(tween(100))
            }, label = "transferActions") { inSelection ->
                if (inSelection) {
                    AppIconButton(
                        if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        if (checked) tr("取消选择") else tr("选择"), onClick = onSelect,
                        wenkuSymbol = if (checked) WenkuSymbol.Selected else WenkuSymbol.Circle,
                        tint = if (checked) colors.primary else colors.onSurfaceVariant,
                    )
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    TransferStatusAction(state, onPause, onResume, onRetry)
                    AppIconButton(Icons.Default.Delete, tr("删除记录"), onClick = onDelete,
                        wenkuSymbol = WenkuSymbol.Delete)
                }
            }
        }
    }
}

@Composable
private fun TransferStatusAction(state: DownloadState, onPause: () -> Unit, onResume: () -> Unit, onRetry: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val latest by rememberUpdatedState(state)
    // Progress samples update the ring without restarting the status transition.
    AnimatedContent(state::class, transitionSpec = {
        if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
        else (fadeIn(tween(150)) + scaleIn(tween(190), initialScale = .82f)) togetherWith fadeOut(tween(110))
    }, label = "transferStatus") { kind ->
        when (kind) {
            DownloadState.Pending::class -> Spacer(Modifier.size(48.dp).downloadRing(null))
            DownloadState.Downloading::class -> {
                val running = latest as? DownloadState.Downloading
                val progress = running?.progress?.takeIf { it > 0f || running.totalBytes > 0 }
                AppIconButton(Icons.Default.Pause, tr("暂停"), onClick = onPause,
                    modifier = Modifier.downloadRing(progress), wenkuSymbol = WenkuSymbol.Pause, glyphSize = 16.dp)
            }
            DownloadState.Paused::class -> AppIconButton(Icons.Default.PlayArrow, tr("继续"), onClick = onResume,
                wenkuSymbol = WenkuSymbol.Play)
            DownloadState.Error::class -> AppIconButton(Icons.Default.Refresh, tr("重新获取并重试"), onClick = onRetry,
                wenkuSymbol = WenkuSymbol.Refresh)
            DownloadState.Success::class -> Unit
            else -> Unit
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
