package com.example.ui

import com.example.ui.components.pullOutClickable
import com.example.ui.components.PullOutButton
import com.example.ui.components.PullOutOutlinedButton
import com.example.ui.components.PullOutVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.source.ComicChapter
import com.example.ui.components.AppIconButton
import com.example.ui.components.AppLiquidButton
import com.example.ui.components.ChasingDots
import com.example.ui.theme.MintPrimary

/**
 * 在线文字阅读器：展示小说章节正文（段落以 \n\n 分隔），
 * 支持字号调节、上一章/下一章切换、章节标题栏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelReaderScreen(
    bookTitle: String?,
    chapter: ComicChapter?,
    text: String,
    loading: Boolean,
    error: String?,
    hasNextChapter: Boolean,
    hasPrevChapter: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadPrev: () -> Unit,
    onLoadNext: () -> Unit,
    onRecordTime: (Long) -> Unit = {},
    onSessionEnd: (com.example.data.ReadingSession) -> Unit = {}
) {
    // 在线阅读计时（阅读统计修复①）：与在线漫画同款——只在 App 前台 + 屏幕亮时累计，
    // 此前在线小说阅读完全不写阅读记录，统计页看不到读过的网文
    com.example.ui.ReadingTimerEffect(
        bookId = null,
        bookTitle = bookTitle ?: "在线小说",
        onFlush = { seconds -> onRecordTime(seconds) },
        onSessionEnd = { session -> onSessionEnd(session) }
    )
    var fontSizeSp by remember { mutableFloatStateOf(17f) }
    var showBars by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // 切章后回到顶部
    LaunchedEffect(chapter?.id, text) {
        listState.scrollToItem(0)
    }

    val paragraphs = remember(text) {
        text.split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏
            PullOutVisibility(visible = showBars) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = bookTitle ?: "阅读",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = chapter?.title ?: "",
                                fontSize = 11.sp,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        AppIconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        AppIconButton(onClick = { if (fontSizeSp > 12f) fontSizeSp -= 1f }) {
                            Text("A-", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                        }
                        AppIconButton(onClick = { if (fontSizeSp < 26f) fontSizeSp += 1f }) {
                            Text("A+", color = MintPrimary, fontSize = 15.sp)
                        }
                    }
                )
            }

            when {
                loading && paragraphs.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ChasingDots(size = 46.dp, color = MintPrimary)
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("正在加载正文…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                error != null && paragraphs.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 32.dp),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            AppLiquidButton(text = "重试", onClick = onRetry)
                        }
                    }
                }

                paragraphs.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("本章没有内容", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pullOutClickable { showBars = !showBars },
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "title") {
                            Text(
                                text = chapter?.title ?: "",
                                fontSize = (fontSizeSp + 4).sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                        items(paragraphs.size) { i ->
                            Text(
                                text = paragraphs[i],
                                fontSize = fontSizeSp.sp,
                                lineHeight = (fontSizeSp * 1.75f).sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                            )
                        }
                        // 底部翻章
                        item(key = "nav") {
                            Spacer(modifier = Modifier.height(18.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (hasPrevChapter) {
                                    PullOutOutlinedButton(
                                        onClick = onLoadPrev,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.NavigateBefore,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("上一章", fontSize = 13.sp)
                                    }
                                }
                                if (hasNextChapter) {
                                    PullOutButton(
                                        onClick = onLoadNext,
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = MintPrimary)
                                    ) {
                                        Text("下一章", fontSize = 13.sp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            Icons.AutoMirrored.Filled.NavigateNext,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}
