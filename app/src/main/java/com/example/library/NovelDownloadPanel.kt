package com.example.library

import com.example.ui.components.PullOutTextButton
import com.example.ui.components.PullOutIconButton
import com.example.ui.glasskit.GlassKitCard
import com.example.ui.feedback.LocalReduceMotion
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
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
    DownloadState.Pending -> "准备中"
    is DownloadState.Downloading -> if (state.progress >= 1f) "校验入库…"
        else if (state.totalBytes > 0 || state.progress > 0) "下载中 · ${(state.progress * 100).toInt()}%" else "下载中"
    is DownloadState.Paused -> "已暂停"
    is DownloadState.Success -> "已完成"
    is DownloadState.Error -> "下载失败"
    DownloadState.Idle -> "等待中"
}

@Composable
internal fun DownloadCenterEntry(model: LibraryViewModel, onClick: () -> Unit) {
    val novels by model.downloadStates.collectAsStateWithLifecycle()
    val comics by model.comicDownloadTasks.collectAsStateWithLifecycle()
    val active=novels.values.filter { it is DownloadState.Pending || it is DownloadState.Downloading } +
        comics.values.filter { it.status==ComicDownloadStatus.DOWNLOADING }.map { DownloadState.Downloading(0,0,it.progress) }
    val paused=novels.values.any { it is DownloadState.Paused } || comics.values.any { it.status==ComicDownloadStatus.PAUSED }
    val failed=novels.values.any { it is DownloadState.Error } || comics.values.any { it.status==ComicDownloadStatus.FAILED }
    val completed=novels.values.any { it is DownloadState.Success } || comics.values.any { it.status==ComicDownloadStatus.SUCCESS }
    val tint=if(failed && active.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val determinate=active.isNotEmpty() && active.all { it is DownloadState.Downloading && (it.totalBytes>0 || it.progress>0) }
    val progress=active.filterIsInstance<DownloadState.Downloading>().map { it.progress }.average().toFloat().takeIf { it.isFinite() } ?: 0f
    val reduce=LocalReduceMotion.current
    PullOutIconButton(onClick=onClick,modifier=Modifier.testTag("download_tasks_entry")) {
        BadgedBox(badge={if(active.isNotEmpty()) Badge { Text(active.size.toString()) }}) {
            Box(Modifier.size(32.dp),contentAlignment=Alignment.Center) {
                if(active.isNotEmpty()) {
                    if(determinate || reduce) CircularProgressIndicator(progress={if(determinate) progress.coerceIn(0f,1f) else .25f},modifier=Modifier.fillMaxSize(),strokeWidth=2.dp)
                    else CircularProgressIndicator(Modifier.fillMaxSize(),strokeWidth=2.dp)
                }
                Icon(when {
                    active.isNotEmpty() -> Icons.Default.Download
                    failed -> Icons.Default.ErrorOutline
                    paused -> Icons.Default.Pause
                    completed -> Icons.Default.Check
                    else -> Icons.Default.Download
                },"下载任务",tint=tint,modifier=Modifier.size(if(active.isEmpty()) 24.dp else 18.dp))
            }
        }
    }
}

@Composable
internal fun DownloadCenter(model: LibraryViewModel, onDismiss: () -> Unit) {
    val tasks by model.allDownloadTasks.collectAsStateWithLifecycle()
    val preparing by model.preparingDownloads.collectAsStateWithLifecycle()
    val states by model.downloadStates.collectAsStateWithLifecycle()
    val comics by model.comicDownloadTasks.collectAsStateWithLifecycle()
    val sources by model.availableSources.collectAsStateWithLifecycle()
    NovelDownloadPanel(tasks, preparing, states, model::pauseDownload, model::resumeDownload,
        model::cancelDownload, { model.startDownload(it, it.format.takeIf(String::isNotBlank)) }, onDismiss,
        comics.values.toList(), model::pauseComicChapter,
        { id -> comics[id]?.let { model.downloadComicChapter(it.book,it.chapter) } }, model::cancelComicChapter, sources.associate { it.id to it.name })
}

@Composable
internal fun NovelDownloadPanel(
    tasks: List<DownloadTaskEntity>, preparations: Map<String, LibraryViewModel.DownloadPreparation>,
    states: Map<String, DownloadState>, onPause: (String) -> Unit, onResume: (String) -> Unit,
    onCancel: (String) -> Unit, onRetry: (SearchBook) -> Unit,
    onDismiss: () -> Unit, comics: List<ComicDownloadTask> = emptyList(),
    onComicPause: (String) -> Unit = {}, onComicResume: (String) -> Unit = {}, onComicDelete: (String) -> Unit = {}, sourceNames: Map<String,String> = emptyMap()
) {
    var deletion by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .8f
    GlassKitCard(shape = MaterialTheme.shapes.extraLarge, modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal=16.dp).testTag("download_task_panel")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("下载", style=MaterialTheme.typography.titleLarge, modifier=Modifier.weight(1f))
                PullOutTextButton(onClick = com.example.ui.components.paperDismiss(onDismiss)) { Text("收起") }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill=false), contentPadding=PaddingValues(vertical=12.dp)) {
                val keys = tasks.map { it.id }.toSet()
                items(preparations.filterKeys { it !in keys }.entries.toList(), key={ "prep:"+it.key }) { (key, prep) ->
                    DownloadTaskRow(prep.book.title, sourceNames[prep.book.sourceId] ?: prep.book.sourceId, prep.state,
                        { onPause(key) }, { onResume(key) }, { deletion=prep.book.title to { onCancel(key) } }, { onRetry(prep.book) })
                }
                items(tasks.sortedWith(compareBy<DownloadTaskEntity> { it.status==DownloadStatus.COMPLETED }.thenByDescending { it.updatedAt }),key={it.id}) { t ->
                    val key=DownloadManager.taskId(t.sourceId,DownloadManager.originalBookId(t.id,t.sourceId))
                    val state=states[key] ?: when(t.status) {
                        DownloadStatus.COMPLETED -> DownloadState.Success(t.filePath)
                        DownloadStatus.FAILED -> DownloadState.Error(t.errorMessage ?: "下载失败")
                        DownloadStatus.PAUSED -> DownloadState.Paused(t.downloadedBytes,t.totalBytes)
                        DownloadStatus.DOWNLOADING -> DownloadState.Downloading(t.downloadedBytes,t.totalBytes,t.progress)
                        else -> DownloadState.Pending
                    }
                    val book=SearchBook(id=DownloadManager.originalBookId(t.id,t.sourceId),sourceId=t.sourceId,title=t.title,author=t.author,format=t.format)
                    DownloadTaskRow(t.title,"${t.format.uppercase()} · ${sourceNames[t.sourceId] ?: t.sourceId}",state,
                        {onPause(key)},{onResume(key)},{deletion=t.title to {onCancel(key)}},{onRetry(book)})
                }
                items(comics, key={ComicDownloadManager.taskId(it.book,it.chapterId)}) { t ->
                    val id=ComicDownloadManager.taskId(t.book,t.chapterId)
                    val state=when(t.status) {
                        ComicDownloadStatus.DOWNLOADING -> DownloadState.Downloading(0,0,t.progress)
                        ComicDownloadStatus.PAUSED -> DownloadState.Paused(0,0)
                        ComicDownloadStatus.FAILED -> DownloadState.Error(t.error ?: "下载失败")
                        ComicDownloadStatus.SUCCESS -> DownloadState.Success("")
                    }
                    DownloadTaskRow(t.book.title,"${t.chapter.title} · ${sourceNames[t.book.sourceId] ?: t.book.sourceId}",state,
                        {onComicPause(id)},{onComicResume(id)},{deletion=t.book.title to {onComicDelete(id)}},{onComicResume(id)})
                }
                if(tasks.isEmpty() && preparations.isEmpty() && comics.isEmpty()) item { Text("暂无下载任务",Modifier.padding(vertical=24.dp)) }
            }
        }
    }
    deletion?.let { pending ->
        AlertDialog(onDismissRequest={deletion=null}, title={Text("删除记录？")},
            text={Text("未完成任务会停止，书架文件保留。")},
            confirmButton={TextButton(onClick={deletion=null;pending.second()}) {Text("删除")}},
            dismissButton={TextButton(onClick={deletion=null}) {Text("取消")}})
    }
}

@Composable
private fun DownloadTaskRow(title:String, subtitle:String, state:DownloadState,
    onPause:()->Unit,onResume:()->Unit,onDelete:()->Unit,onRetry:()->Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical=10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                Text(subtitle,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when(state) {
                is DownloadState.Downloading -> IconButton(onClick=onPause) { Icon(Icons.Default.Pause,"暂停") }
                is DownloadState.Paused -> IconButton(onClick=onResume) { Icon(Icons.Default.PlayArrow,"继续") }
                is DownloadState.Error -> IconButton(onClick=onRetry) { Icon(Icons.Default.Refresh,"重新获取并重试") }
                else -> Unit
            }
            IconButton(onClick=onDelete) { Icon(Icons.Default.Close,"删除记录") }
        }
        Text(if(state is DownloadState.Error) DownloadWorker.failureMessage(state.message) else downloadStatusLabel(state),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,
            color=if(state is DownloadState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        when(state) {
            DownloadState.Pending -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=6.dp))
            is DownloadState.Downloading -> {
                if(state.totalBytes>0 || state.progress>0) LinearProgressIndicator(progress={state.progress.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(top=6.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=6.dp))
                if(state.downloadedBytes>0) Text("${downloadBytes(state.downloadedBytes)} / ${if(state.totalBytes>0) downloadBytes(state.totalBytes) else "未知大小"}",style=MaterialTheme.typography.labelSmall)
            }
            is DownloadState.Error -> Unit
            else -> Unit
        }
    }
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
}
