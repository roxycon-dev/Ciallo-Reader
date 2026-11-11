package com.example.library

import com.example.i18n.tr

import com.example.ui.wenku.wenkuReveal
import com.example.ui.wenku.wenkuPagePart
import com.example.ui.wenku.WenkuTextButton
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuSkeletonBlock
import com.example.ui.wenku.WenkuSectionHeader
import com.example.ui.wenku.WenkuRevealAxis
import com.example.ui.wenku.WenkuLoadedContentSequence
import com.example.ui.wenku.WenkuIconButton
import com.example.ui.components.GlassCard
import com.example.ui.components.AppIconButton
import androidx.compose.ui.platform.LocalDensity
import com.example.ui.reader.ImageGalleryDialog
import com.example.ui.reader.CoverImageGallery
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import com.example.ui.theme.clickableWithFeedback

import com.example.ui.components.PullOutButton
import com.example.ui.components.PullOutTextButton
import com.example.ui.components.PullOutOutlinedButton
import com.example.ui.components.PullOutBottomSheet
import com.example.ui.components.pullOutClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.example.download.DownloadState
import com.example.source.SearchBook
import com.example.ui.theme.luminance

@Composable
private fun novelAccentColor(): Color {
    val primary = MaterialTheme.colorScheme.primary
    return if (MaterialTheme.colorScheme.surface.luminance() < .3f) lerp(primary, Color.White, .35f)
        else lerp(primary, Color.Black, .18f)
}

@Composable
internal fun novelActionColors(): ButtonColors {
    val background = lerp(MaterialTheme.colorScheme.primary, Color.Black, .12f)
    return ButtonDefaults.buttonColors(containerColor = background,
        contentColor = if (background.luminance() > .179f) Color(0xFF111111) else Color.White)
}

@Composable
private fun NovelCover(book: SearchBook, loader: ImageLoader, modifier: Modifier, flightKey: String? = null) {
    Box(modifier.clip(RoundedCornerShape(12.dp))
        .then(if (flightKey != null) Modifier.onGloballyPositioned {
            com.example.ui.wenku.WenkuFlightSources.put(flightKey, it.boundsInRoot())
        } else Modifier)
        .background(Brush.linearGradient(listOf(
        MaterialTheme.colorScheme.primary.copy(alpha = .22f), MaterialTheme.colorScheme.secondaryContainer)))) {
        @Composable fun Placeholder() {
            Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(Icons.Default.MenuBook, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(book.title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Text(book.format.uppercase(), fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        val context = LocalContext.current
        val request = remember(context, book.cover, book.sourceId) { novelCoverRequest(context, book) }
        if (book.cover.isNullOrBlank()) Placeholder() else SubcomposeAsyncImage(
            model = request, imageLoader = loader, contentDescription = book.title,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
        ) {
            if (painter.state is AsyncImagePainter.State.Success) {
                // 详情面板的「阅读」要飞这一张：把已解码的 painter 登记成飞行贴图。
                if (flightKey != null) com.example.ui.wenku.WenkuCoverSnapshot(flightKey, painter)
                SubcomposeAsyncImageContent()
            } else Placeholder()
        }
    }
}

internal fun novelCoverRequest(context: android.content.Context, book: SearchBook): ImageRequest =
    ImageRequest.Builder(context).data(book.cover).apply {
        when (book.sourceId) {
            "ixdzs8" -> addHeader("Referer", "https://ixdzs8.com/")
            "wenku8_library" -> addHeader("Referer", "https://wenku8.ywy.moe/")
        }
    }.build()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NovelLabels(labels: List<String>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.filter(String::isNotBlank).distinct().forEach { label ->
            Text(label, fontSize = 11.sp, color = novelAccentColor(),
                modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .08f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp))
        }
    }
}

@Composable
fun NovelSearchCard(book: SearchBook, sourceName: String, imageLoader: ImageLoader,
    state: DownloadState = DownloadState.Idle, downloaded: Boolean = false,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    val info = book.novelInfo
    Surface(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickableWithFeedback(onClick = onClick),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NovelCover(book, imageLoader, Modifier.width(86.dp).height(124.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(book.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(listOfNotNull(book.author.takeIf(String::isNotBlank), sourceName).joinToString(" · "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                NovelLabels(listOfNotNull(info?.status, info?.category, info?.translation ?: book.language, book.format.uppercase()))
                info?.latestChapter?.takeIf(String::isNotBlank)?.let {
                    Text("最新 · $it", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                info?.synopsis?.takeIf(String::isNotBlank)?.let {
                    Text(it, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f))
                }
                val facts = listOfNotNull(info?.wordCount, info?.volumeCount?.let { "$it 卷" }, info?.chapterCount?.let { "$it 章" })
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val downloadLabel = when (state) {
                    is DownloadState.Success -> tr("已在书架 · 离线阅读")
                    is DownloadState.Downloading -> "整本下载 ${(state.progress * 100).toInt()}%"
                    is DownloadState.Pending -> tr("正在准备整本下载")
                    is DownloadState.Paused -> tr("整本下载已暂停")
                    is DownloadState.Error -> if (downloaded) tr("可读离线版本 · 更新下载失败") else tr("下载失败 · 点开重试")
                    else -> if (downloaded) tr("已在书架 · 离线阅读") else tr("查看详情 · 整本下载")
                }
                Text(downloadLabel, fontSize = 11.sp, color = novelAccentColor(), fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun NovelDetailContent(book: SearchBook, sourceName: String, imageLoader: ImageLoader,
    loading: Boolean = false, error: String? = null, modifier: Modifier = Modifier,
    flightKey: String? = null, onOpenCover: (() -> Unit)? = null) {
    val info = book.novelInfo
    Column(modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints {
            val wide = maxWidth >= 650.dp
            val coverWidth = if (wide) 150.dp else 118.dp
            val coverAction = if (onOpenCover != null && !book.cover.isNullOrBlank()) {
                Modifier.semantics { contentDescription = tr("查看封面") }
                    .pullOutClickable(role = Role.Button, onClick = onOpenCover)
            } else {
                Modifier
            }
            val cover: @Composable () -> Unit = {
                NovelCover(book, imageLoader, Modifier.size(coverWidth, coverWidth * 1.4f).then(coverAction), flightKey)
            }
            val identity: @Composable (Modifier) -> Unit = { identityModifier ->
                Column(identityModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        book.title,
                        modifier = Modifier.wenkuReveal(0, axis = WenkuRevealAxis.Horizontal),
                        style = MaterialTheme.typography.headlineSmall,
                        fontSize = if (wide) 28.sp else 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    if (book.author.isNotBlank()) {
                        Text(
                            book.author,
                            modifier = Modifier.wenkuReveal(1, axis = WenkuRevealAxis.Horizontal),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(sourceName, fontSize = 12.sp, color = novelAccentColor())
                    NovelLabels(listOfNotNull(info?.status, info?.category, book.format.uppercase(), book.language))
                }
            }
            if (!wide && fontScale >= 1.45f) {
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    cover()
                    identity(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(if (wide) 28.dp else 20.dp)) {
                    cover()
                    identity(Modifier.weight(1f))
                }
            }
        }
        if (loading) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                WenkuSkeletonBlock(Modifier.fillMaxWidth(.55f).height(14.dp))
                WenkuSkeletonBlock(Modifier.fillMaxWidth().height(14.dp))
                WenkuSkeletonBlock(Modifier.fillMaxWidth(.78f).height(14.dp))
            }
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        val facts = listOfNotNull(
            info?.wordCount?.let { com.example.i18n.tr("字数") to it }, info?.chapterCount?.let { com.example.i18n.tr("源站目录") to "$it 章" },
            info?.volumeCount?.let { com.example.i18n.tr("收录卷数") to "$it 卷" }, book.size?.let { com.example.i18n.tr("下载大小") to "%.1f MB".format(it / 1024.0 / 1024) },
            info?.publisher?.let { com.example.i18n.tr("出版社") to it }, info?.translation?.let { com.example.i18n.tr("阅读版本") to it })
        if (facts.isNotEmpty()) GlassCard(Modifier.wenkuPagePart(2)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                facts.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, value) -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                } }
            }
        }
        if (!info?.latestChapter.isNullOrBlank() || !info?.updatedAt.isNullOrBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(tr("连载进度"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                info?.latestChapter?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                info?.updatedAt?.let { Text("源站更新 $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        info?.originalTitle?.takeIf { it != book.title && it.isNotBlank() }?.let {
            Text("原名 · $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (info?.tags?.isNotEmpty() == true) NovelLabels(info.tags.take(12))
        val synopsis = info?.synopsis?.takeIf(String::isNotBlank) ?: book.description
        if (!synopsis.isNullOrBlank()) {
            var expanded by remember(book.id, book.sourceId) { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WenkuSectionHeader("01", "SYNOPSIS", com.example.i18n.tr("作品简介"), Modifier.wenkuPagePart(3))
                Text(synopsis, style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 7, overflow = TextOverflow.Ellipsis)
                if (synopsis.length > 180) {
                    WenkuTextButton(
                        if (expanded) tr("收起简介") else tr("展开完整简介"),
                        onClick = { expanded = !expanded },
                        compact = true,
                        wenkuSymbol = if (expanded) WenkuSymbol.Up else WenkuSymbol.Down,
                    )
                }
            }
        }
        info?.notice?.takeIf(String::isNotBlank)?.let { notice ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f), RoundedCornerShape(14.dp))
                .padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailSheet(book: SearchBook, sourceName: String, imageLoader: ImageLoader, loading: Boolean,
    error: String?, state: DownloadState, hasLocal: Boolean = false, onDismiss: () -> Unit, onDownload: (close: (() -> Unit) -> Unit) -> Unit,
    onRead: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit,
    flightKey: String? = null) {
    val context = LocalContext.current
    var previewBook by remember { mutableStateOf<SearchBook?>(null) }
    PullOutBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        val close = com.example.ui.components.LocalOverlayClose.current
        val download = { onDownload(close) }
        val read: () -> Unit = {
            val bounds = flightKey?.let(com.example.ui.wenku.WenkuFlightSources::get)
            val image = flightKey?.let(com.example.ui.wenku.WenkuCoverSnapshots::get)
            close {
                if (flightKey != null && bounds != null) {
                    com.example.ui.wenku.WenkuFlightBridge.begin(flightKey, bounds, image, radius = 12.dp)
                }
                onRead()
            }
        }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f)) {
            Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp)) {
                WenkuIconButton(
                    Icons.Default.Close,
                    com.example.i18n.tr("关闭小说详情"),
                    onClick = { close {} },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr("小说详情"), style = MaterialTheme.typography.titleSmall)
                    Text("ARCHIVE / DETAIL", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            WenkuLoadedContentSequence(
                contentKey = book.sourceId to book.id,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                NovelDetailContent(
                    book, sourceName, imageLoader, loading, error,
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp),
                    flightKey,
                    onOpenCover = { previewBook = book },
                )
            }
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (state) {
                        DownloadState.Pending, is DownloadState.Downloading -> {
                            val transfer = state as? DownloadState.Downloading
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (transfer == null) tr("准备整本文件…") else downloadStatusLabel(transfer),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (transfer != null) {
                                    val known = transfer.totalBytes > 0 || transfer.progress > 0f
                                    AppIconButton(
                                        Icons.Default.Pause,
                                        "暂停",
                                        onClick = onPause,
                                        modifier = Modifier.downloadRing(transfer.progress.takeIf { known }),
                                        glyphSize = 16.dp,
                                        wenkuSymbol = WenkuSymbol.Pause,
                                    )
                                }
                                WenkuIconButton(Icons.Default.Close, "取消", onClick = onCancel)
                            }
                        }
                        is DownloadState.Paused -> WenkuTextButton(
                            com.example.i18n.tr("继续整本下载"), onResume, Modifier.fillMaxWidth(),
                            selected = true, wenkuSymbol = WenkuSymbol.Play,
                        )
                        is DownloadState.Success -> WenkuTextButton(
                            if (hasLocal) tr("打开书架里的小说") else tr("整本下载到书架"),
                            if (hasLocal) read else download,
                            Modifier.fillMaxWidth(), selected = true,
                            wenkuSymbol = if (hasLocal) WenkuSymbol.Book else WenkuSymbol.Download,
                        )
                        else -> {
                            if (state is DownloadState.Error) {
                                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            WenkuTextButton(
                                if (state is DownloadState.Error) tr("重试整本下载") else tr("整本下载到书架"),
                                download,
                                Modifier.fillMaxWidth(), selected = true, enabled = !loading,
                                wenkuSymbol = if (state is DownloadState.Error) WenkuSymbol.Refresh else WenkuSymbol.Download,
                            )
                        }
                    }
                    if (hasLocal && state !is DownloadState.Success) {
                        WenkuTextButton(tr("阅读已下载版本"), read, Modifier.fillMaxWidth(), wenkuSymbol = WenkuSymbol.Book)
                    }
                }
            }
        }
    }
    previewBook?.let { preview ->
        val request = remember(context, preview) { novelCoverRequest(context, preview) }
        ImageGalleryDialog(onDismiss = { previewBook = null }) { closeGallery ->
            CoverImageGallery("小说封面", preview.title, request, imageLoader, closeGallery)
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelUpdateSheet(update: LibraryViewModel.NovelUpdate, state: DownloadState, onDismiss: () -> Unit,
    onRetry: () -> Unit, onDownload: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    PullOutBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr("检查小说更新"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(update.local.title, style = MaterialTheme.typography.titleSmall)
            Text(update.sourceName, fontSize = 12.sp, color = novelAccentColor())
            when {
                update.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr("正在获取源站最新信息…")) }
                update.error != null -> { Text(update.error, color = MaterialTheme.colorScheme.error); PullOutOutlinedButton(onClick = onRetry) { Text(tr("重试检查")) } }
                else -> {
                    Text(when (update.changed) { true -> tr("源站信息有更新"); false -> tr("源站信息未变化"); null -> tr("已获取源站当前信息") }, fontWeight = FontWeight.SemiBold)
                    update.remote?.novelInfo?.let { info ->
                        info.latestChapter?.let { Text("源站最新 · $it", fontSize = 14.sp) }
                        info.updatedAt?.let { Text("更新于 $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        info.chapterCount?.let { Text("源站目录 $it 章", fontSize = 12.sp) }
                        info.notice?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Text(tr("整本新版验证成功后替换，保留阅读位置、书签和笔记。"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (state) {
                        is DownloadState.Success -> {
                            if (state.path != update.local.filePath.removePrefix("file://")) {
                                Text(tr("新版已存入书架"), color = novelAccentColor())
                                PullOutButton(onClick = com.example.ui.components.paperDismiss(onDismiss), colors = novelActionColors(), modifier = Modifier.fillMaxWidth()) { Text(tr("完成，继续阅读")) }
                            } else PullOutButton(onClick = onDownload, colors = novelActionColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp)) { Text(if (update.changed == true) tr("下载整本新版") else tr("重新下载整本")) }
                        }
                        is DownloadState.Downloading -> { LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth()); Text("正在下载整本新版 ${(state.progress * 100).toInt()}%"); Row { PullOutTextButton(onClick = onPause) { Text(tr("暂停")) }; PullOutTextButton(onClick = onCancel) { Text(tr("取消")) } } }
                        is DownloadState.Pending -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr("正在准备整本新版…")) }
                        is DownloadState.Paused -> PullOutButton(onClick = onResume, colors = novelActionColors(), modifier = Modifier.fillMaxWidth()) { Text(tr("继续下载新版")) }
                        else -> {
                            if (state is DownloadState.Error) Text(state.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            PullOutButton(onClick = onDownload, colors = novelActionColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp)) {
                                Text(if (update.changed == true) tr("下载整本新版") else tr("重新下载整本"))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NovelUpdatePanel(viewModel: LibraryViewModel, localBook: com.example.data.Book?) {
    val update by viewModel.novelUpdate.collectAsState()
    val states by viewModel.downloadStates.collectAsState()
    val current = update ?: return
    if (localBook?.id != current.local.id) {
        LaunchedEffect(current.local.id, localBook?.id) { viewModel.dismissNovelUpdate() }
        return
    }
    val taskId = com.example.download.DownloadManager.taskId(current.local.sourceId.orEmpty(), current.local.comicId.orEmpty())
    NovelUpdateSheet(current, states[taskId] ?: DownloadState.Idle, onDismiss = viewModel::dismissNovelUpdate,
        onRetry = { viewModel.checkNovelUpdate(current.local) }, onDownload = { current.remote?.let(viewModel::downloadNovelUpdate) },
        onPause = { viewModel.pauseDownload(taskId) }, onResume = { viewModel.resumeDownload(taskId) }, onCancel = { viewModel.cancelDownload(taskId) })
}
