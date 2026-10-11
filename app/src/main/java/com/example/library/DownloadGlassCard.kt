package com.example.library

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import com.example.ui.feedback.AppMotion
import com.example.ui.feedback.LocalReduceMotion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.download.DownloadState
import com.example.source.SearchBook
import com.example.ui.components.FlowingGradientProgressBar
import com.example.ui.components.MascotEmptyState
import com.example.ui.mascot.MascotSpriteSheet
import com.example.ui.components.AppActionButton
import com.example.ui.components.AppButtonSize
import com.example.ui.components.AppButtonVariant
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild

/**
 * 居中悬浮下载卡片/管理中心：大尺寸、完全不透明卡片、居中弹簧动画。
 * 支持正在下载的书籍进度展示与无任务时的空状态展示。
 */
@Composable
fun DownloadGlassCard(
    book: SearchBook?,
    state: DownloadState,
    hazeState: HazeState?,
    onDismiss: (() -> Unit)? = null,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = rememberZLibraryImageLoader(context)

    val taskKey = "${book?.sourceId}:${book?.id}"
    val reduceMotion = LocalReduceMotion.current
    var lastBytes by remember(taskKey) { mutableLongStateOf(-1L) }
    var lastTime by remember(taskKey) { mutableLongStateOf(0L) }
    var speedBps by remember(taskKey) { mutableLongStateOf(0L) }

    LaunchedEffect(state) {
        if (state is DownloadState.Downloading) {
            val now = SystemClock.elapsedRealtime()
            if (lastBytes >= 0 && lastTime > 0) {
                val dt = now - lastTime
                if (dt > 400) {
                    speedBps = (((state.downloadedBytes - lastBytes) * 1000L) / dt).coerceAtLeast(0L)
                    lastBytes = state.downloadedBytes
                    lastTime = now
                }
            } else {
                lastBytes = state.downloadedBytes
                lastTime = now
            }
        }
    }

    val progress = when (state) {
        is DownloadState.Downloading -> state.progress.coerceIn(0f, 1f)
        is DownloadState.Paused -> (state.downloadedBytes.toFloat() / (state.totalBytes.takeIf { it > 0 } ?: 1L)).coerceIn(0f, 1f)
        is DownloadState.Success -> 1f
        else -> 0f
    }
    var previousProgress by remember(taskKey) { mutableFloatStateOf(progress) }
    val progressTarget = when (state) {
        is DownloadState.Downloading, is DownloadState.Paused -> maxOf(previousProgress, progress)
        is DownloadState.Error -> previousProgress
        else -> progress
    }
    SideEffect { previousProgress = progressTarget }
    val animatedProgress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = tween(if (reduceMotion) 0 else 160, easing = AppMotion.easeOutCubic),
        label = "downloadProgress"
    )

    val statusText = when (state) {
        is DownloadState.Pending -> "等待下载…"
        is DownloadState.Downloading -> "下载中"
        is DownloadState.Paused -> "已暂停"
        is DownloadState.Success -> "下载完成"
        is DownloadState.Error -> "下载失败"
        else -> ""
    }
    val statusColor = when (state) {
        is DownloadState.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }

    val cardShape = RoundedCornerShape(28.dp)
    
    Box(
        modifier = modifier
            .padding(22.dp)
    ) {
        if (book == null) {
            // 空状态下载管理中心
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "下载管理中心",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (onDismiss != null) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                MascotEmptyState(
                    mascotResId = MascotSpriteSheet.idleDrawable,
                    title = "暂无进行中的下载任务",
                    description = "在书库中搜索图书并点击“下载”，离线书籍将自动同步存入本地书架。",
                    testTagPrefix = "download_panel_empty"
                )

                Spacer(modifier = Modifier.height(20.dp))

                AppActionButton(
                    text = "知道了",
                    onClick = { onDismiss?.invoke() },
                    variant = AppButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                    ) {
                        if (!book.cover.isNullOrBlank()) {
                            AsyncImage(
                                model = book.cover,
                                imageLoader = imageLoader,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(96.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = book.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = statusText,
                            fontSize = 14.sp,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (onDismiss != null) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                FlowingGradientProgressBar(
                    progress = animatedProgress,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                )

                Spacer(modifier = Modifier.height(12.dp))

                AnimatedContent(
                    targetState = state, contentKey = { it.javaClass },
                    transitionSpec = { fadeIn(tween(if (reduceMotion) 0 else 180, delayMillis = if (reduceMotion) 0 else 65)) togetherWith fadeOut(tween(if (reduceMotion) 0 else 90)) },
                    label = "downloadStatus",
                ) { state -> when (state) {
                    is DownloadState.Downloading -> {
                        val remaining = state.totalBytes - state.downloadedBytes
                        val speedText = if (speedBps > 0) {
                            "${formatSize(speedBps)}/s"
                        } else {
                            "计算速度…"
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${(progressTarget * 100).toInt()}%",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(speedText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "剩余 ${formatSize(remaining)} / 共 ${formatSize(state.totalBytes)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is DownloadState.Success -> {
                        androidx.compose.foundation.layout.Box {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                com.example.ui.components.CompletionGlyph(MaterialTheme.colorScheme.secondary, Modifier.size(26.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "已加入书架",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    is DownloadState.Error -> {
                        Text(
                            state.message,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Start
                        )
                    }
                    is DownloadState.Paused -> {
                        Text("已暂停", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        Text("准备中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } }

                // 下载控制：下载中/等待 -> 暂停+取消；已暂停/失败 -> 继续+取消
                if (state is DownloadState.Pending ||
                    state is DownloadState.Downloading ||
                    state is DownloadState.Paused ||
                    state is DownloadState.Error
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        val primaryAction = when (state) {
                            is DownloadState.Paused, is DownloadState.Error -> onResume
                            else -> onPause
                        }
                        if (primaryAction != null) {
                            AppActionButton(
                                text = when (state) {
                                    is DownloadState.Paused -> "继续下载"
                                    is DownloadState.Error -> "重试"
                                    else -> "暂停"
                                },
                                onClick = primaryAction,
                                variant = AppButtonVariant.Secondary,
                                buttonSize = AppButtonSize.Small,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        if (onCancel != null) {
                            AppActionButton(
                                text = "取消",
                                onClick = onCancel,
                                variant = AppButtonVariant.Destructive,
                                buttonSize = AppButtonSize.Small,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${String.format("%.0f", kb)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${String.format("%.1f", mb)} MB"
    return "${String.format("%.2f", mb / 1024.0)} GB"
}
