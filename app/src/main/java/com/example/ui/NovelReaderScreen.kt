package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ReadingSession
import com.example.library.OnlineNovelChapterState
import com.example.library.OnlineNovelPage
import com.example.ui.components.AppIconButton
import com.example.ui.components.PullOutTextButton
import com.example.ui.components.pullOutClickable
import com.example.ui.wenku.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelReaderScreen(
    bookTitle: String?,
    state: OnlineNovelChapterState,
    hasNextChapter: Boolean,
    hasPrevChapter: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadPrev: () -> Unit,
    onLoadNext: () -> Unit,
    onRecordTime: (Long) -> Unit = {},
    onSessionEnd: (ReadingSession) -> Unit = {}
) {
    ReadingTimerEffect(
        bookId = null,
        bookTitle = bookTitle ?: "在线小说",
        onFlush = onRecordTime,
        onSessionEnd = onSessionEnd
    )
    val page = state.page
    var fontSizeSp by remember { mutableFloatStateOf(17f) }
    var showBars by remember { mutableStateOf(true) }
    val chrome = rememberWenkuReaderChrome(showBars)
    val motion = rememberWenkuAccessibility().decorativeMotion && LocalWenkuDecorativeMotion.current
    val colors = MaterialTheme.colorScheme
    val listState = key(page?.chapter?.id) { rememberLazyListState() }
    val pane = remember(page, listState) { page?.let { it to listState } }
    val scope = rememberCoroutineScope()
    val canPrevious = hasPrevChapter && !state.loading
    val canNext = hasNextChapter && !state.loading
    val title = page?.chapter?.title ?: state.requested?.title.orEmpty()
    var delayedLoading by remember { mutableStateOf(false) }
    LaunchedEffect(state.requested?.id, state.loading, page != null) {
        delayedLoading = false
        if (state.loading && page != null) {
            delay(500)
            delayedLoading = true
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        WenkuLoadingHandoff(
            loaded = pane != null,
            modifier = Modifier.fillMaxSize(),
            skeleton = {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    when {
                        state.loading || page != null -> OnlineNovelSkeleton(
                            title = title,
                            modifier = if (state.loading && page == null) {
                                Modifier.semantics { contentDescription = "正在加载正文" }
                            } else Modifier.clearAndSetSemantics { }
                        )
                        state.error != null -> OnlineNovelFailure(state.error, onRetry, Modifier.align(Alignment.Center))
                        else -> Text("本章没有内容", Modifier.align(Alignment.Center), color = colors.onSurfaceVariant)
                    }
                }
            }
        ) {
            AnimatedContent(
                targetState = pane,
                contentKey = { it?.first?.chapter?.id },
                transitionSpec = {
                    val direction = targetState?.first?.direction ?: 1
                    if (!motion || initialState == null || targetState == null) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        slideInHorizontally(tween(WenkuMotion.ENTER_MS, easing = WenkuMotion.plane)) { it * direction } togetherWith
                            slideOutHorizontally(tween(WenkuMotion.ENTER_MS, easing = WenkuMotion.plane)) { -it * direction }
                    }
                },
                modifier = Modifier.fillMaxSize().clipToBounds(),
                label = "onlineNovelChapter"
            ) { current ->
                if (current != null) {
                    val (chapter, scroll) = current
                    val active = current == pane
                    OnlineNovelBody(
                        page = chapter,
                        listState = scroll,
                        fontSizeSp = fontSizeSp,
                        motion = motion,
                        active = active,
                        canPrevious = canPrevious,
                        canNext = canNext,
                        onToggleChrome = { showBars = !showBars },
                        onPrevious = onLoadPrev,
                        onNext = onLoadNext
                    )
                } else Spacer(Modifier.fillMaxSize())
            }
        }

        WenkuReaderChromeLayer(chrome, showBars, Modifier.align(Alignment.TopCenter)) {
            TopAppBar(
                title = {
                    Column {
                        Text(bookTitle ?: "阅读", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(title, color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background.copy(alpha = .97f)),
                navigationIcon = {
                    AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack, tint = colors.onSurface)
                },
                actions = {
                    PullOutTextButton(
                        onClick = { fontSizeSp = (fontSizeSp - 1f).coerceAtLeast(12f) },
                        enabled = fontSizeSp > 12f,
                        modifier = Modifier.semantics { contentDescription = "缩小字号" }
                    ) { Text("A−") }
                    PullOutTextButton(
                        onClick = { fontSizeSp = (fontSizeSp + 1f).coerceAtMost(26f) },
                        enabled = fontSizeSp < 26f,
                        modifier = Modifier.semantics { contentDescription = "放大字号" }
                    ) { Text("A+") }
                }
            )
        }

        if (page != null) {
            OnlineNovelControls(
                chrome = chrome,
                visible = showBars,
                title = title,
                scroll = listState,
                canPrevious = canPrevious,
                canNext = canNext,
                onPrevious = onLoadPrev,
                onNext = onLoadNext,
                onSeek = { index -> scope.launch { listState.scrollToItem(index) } },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
            val showNotice = (state.loading && delayedLoading) || state.error != null
            val notice = when {
                state.error != null -> state.error to true
                state.loading && delayedLoading -> "正在准备章节，当前正文仍可阅读" to false
                else -> null
            }
            var displayedNotice by remember { mutableStateOf("" to false) }
            if (notice != null) displayedNotice = notice
            AnimatedVisibility(
                visible = showNotice,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 68.dp),
                enter = if (motion) slideInVertically(tween(240, easing = WenkuMotion.ease)) { -it / 2 } else EnterTransition.None,
                exit = if (motion) slideOutVertically(tween(180, easing = WenkuMotion.ease)) { -it / 2 } else ExitTransition.None
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp)
                        .wenkuReaderCapsule(colors.surface, colors.outlineVariant)
                        .then(if (showNotice) Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            else Modifier.clearAndSetSemantics { }.wenkuBlockPointer())
                        .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(displayedNotice.first, modifier = Modifier.weight(1f, fill = false), fontSize = 13.sp, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                    if (displayedNotice.second) {
                        AppIconButton(Icons.Filled.Refresh, "重试", onRetry, enabled = state.error != null, tint = colors.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineNovelBody(
    page: OnlineNovelPage,
    listState: LazyListState,
    fontSizeSp: Float,
    motion: Boolean,
    active: Boolean,
    canPrevious: Boolean,
    canNext: Boolean,
    onToggleChrome: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val paragraphs = remember(page.text) { page.text.split("\n\n").map(String::trim).filter(String::isNotEmpty) }
    val colors = MaterialTheme.colorScheme
    val input = if (active) Modifier.pullOutClickable(onClickLabel = "切换阅读控件", onClick = onToggleChrome)
        else Modifier.clearAndSetSemantics { }.wenkuBlockPointer()
    WenkuLoadedContentSequence(contentKey = page.chapter.id, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().then(input),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 80.dp, bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "title") {
                Column(if (motion) Modifier.wenkuReveal(0) else Modifier) {
                    Text(page.chapter.title, fontSize = (fontSizeSp + 4).sp, fontWeight = FontWeight.Bold,
                        color = colors.onSurface)
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f))
                }
            }
            items(paragraphs.size) { index ->
                Text(
                    paragraphs[index], fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * 1.75f).sp,
                    color = colors.onSurface.copy(alpha = .9f),
                    modifier = if (motion) Modifier.wenkuReveal(index + 1) else Modifier
                )
            }
            if (paragraphs.isEmpty()) item(key = "empty") { Text("本章没有内容", color = colors.onSurfaceVariant) }
            item(key = "nav") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    WenkuPillButton("上一章", onPrevious, primary = false, enabled = active && canPrevious,
                        icon = Icons.Filled.SkipPrevious)
                    WenkuPillButton("下一章", onNext, primary = false, enabled = active && canNext,
                        icon = Icons.Filled.SkipNext)
                }
            }
        }
    }
}

@Composable
private fun OnlineNovelControls(
    chrome: State<Float>,
    visible: Boolean,
    title: String,
    scroll: LazyListState,
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var preview by remember(scroll, visible) { mutableStateOf<Float?>(null) }
    Column(
        modifier.widthIn(max = 480.dp).fillMaxWidth().navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        WenkuReaderChromeLayer(chrome, visible, part = WenkuReaderChromePart.Progress) {
            val onstage by remember(chrome) { derivedStateOf { chrome.value > WenkuReaderChromePart.Progress.start } }
            val lastItem = if (onstage) (scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0) else 0
            val scrollable = onstage && (scroll.canScrollForward || scroll.canScrollBackward)
            val value = preview ?: when {
                !scrollable -> 0f
                !scroll.canScrollForward -> 1f
                else -> scroll.firstVisibleItemIndex.toFloat() / lastItem.coerceAtLeast(1)
            }
            Row(
                Modifier.fillMaxWidth().wenkuReaderCapsule(colors.background.copy(alpha = .97f), colors.outlineVariant)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIconButton(Icons.Filled.SkipPrevious, "上一章", onPrevious, enabled = canPrevious, tint = colors.onSurface)
                WenkuReaderProgressSlider(
                    value = value, label = "本章进度", format = { "${(it * 100).roundToInt()}%" },
                    tint = colors.onSurface, enabled = scrollable, modifier = Modifier.weight(1f),
                    onValueChange = { preview = it },
                    onCommit = {
                        val target = preview
                        preview = null
                        if (target != null && lastItem > 0) onSeek((target * lastItem).roundToInt())
                    }
                )
                AppIconButton(Icons.Filled.SkipNext, "下一章", onNext, enabled = canNext, tint = colors.onSurface)
            }
        }
        WenkuReaderChromeLayer(chrome, visible, part = WenkuReaderChromePart.Dock) {
            val label = preview?.let { "$title · ${(it * 100).roundToInt()}%" } ?: title
            Text(
                label,
                Modifier.fillMaxWidth().wenkuReaderCapsule(colors.background.copy(alpha = .97f), colors.outlineVariant)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = colors.onSurface,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun OnlineNovelSkeleton(title: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 80.dp)) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        repeat(12) { index ->
            WenkuSkeletonBlock(
                Modifier.fillMaxWidth(if (index % 4 == 3) .64f else .94f)
                    .padding(start = if (index % 4 == 0) 24.dp else 0.dp).height(11.dp)
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun OnlineNovelFailure(error: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        WenkuPillButton("重试", onRetry, icon = Icons.Filled.Refresh)
    }
}
