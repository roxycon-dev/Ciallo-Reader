package com.example.ui

import com.example.i18n.tr

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
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import com.example.data.ListeningChapter
import com.example.data.PreferencesManager
import com.example.data.SpokenParagraph
import com.example.data.TtsManager
import com.example.data.spokenParagraphs
import com.example.ui.reader.ReaderListeningPanel
import com.example.ui.reader.ReaderDictionarySheet
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalContext
import com.example.ui.reader.ReaderSelectionSheet
import com.example.ui.reader.ReaderTextSelection
import com.example.ui.reader.ReaderMark
import com.example.ui.reader.readerTextLongPress
import com.example.ui.reader.addReaderMarks
import com.example.ui.reader.OnlineNovelMarks
import androidx.compose.ui.text.buildAnnotatedString
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
    listeningBookKey: String,
    ttsManager: TtsManager,
    prefs: PreferencesManager,
    state: OnlineNovelChapterState,
    hasNextChapter: Boolean,
    hasPrevChapter: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadPrev: () -> Unit,
    onLoadNext: () -> Unit,
    onRecordTime: (Long) -> Unit = {},
    onSessionEnd: (ReadingSession) -> Unit = {},
    listeningContinuation: (suspend (ListeningChapter) -> ListeningChapter?)? = null,
    onOpenListeningChapter: ((String) -> Unit)? = null
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

    var showListening by remember { mutableStateOf(false) }
    var dictionaryPassage by remember { mutableStateOf<String?>(null) }
    var dictionaryWord by remember { mutableStateOf("") }
    var selection by remember(page?.chapter?.id) { mutableStateOf<ReaderTextSelection?>(null) }
    val context = LocalContext.current
    val markStore = remember(context, listeningBookKey, page?.chapter?.id) {
        OnlineNovelMarks(context, listeningBookKey, page?.chapter?.id.orEmpty())
    }
    var marks by remember(markStore) { mutableStateOf(markStore.load()) }
    var showMarks by remember(markStore) { mutableStateOf(false) }
    val visibleMarks = remember(page?.text, marks) {
        val text = page?.text.orEmpty()
        marks.filter { it.mark.start >= 0 && it.mark.end <= text.length &&
            it.mark.start < it.mark.end && text.regionMatches(it.mark.start, it.text, 0, it.text.length) }
    }
    var followListening by remember(ttsManager) { mutableStateOf(ttsManager.followText) }
    var pendingNext by remember(listeningBookKey) { mutableStateOf<String?>(null) }
    val listening by ttsManager.state.collectAsState()
    val ownsListening = listening.chapter?.bookKey == listeningBookKey
    val isListening = ownsListening && (listening.playing || listening.preparing)
    val listeningChapter = remember(page, listeningBookKey, bookTitle) {
        page?.let { ListeningChapter(listeningBookKey, it.chapter.id, bookTitle.orEmpty(), it.chapter.title,
            spokenParagraphs(it.text)) }
    }
    val activeParagraph = listening.paragraph.takeIf {
        isListening && followListening && listening.chapter?.chapterKey == page?.chapter?.id
    }

    fun play(from: Int? = null, useReadingPosition: Boolean = false) {
        val existing = listening.chapter?.takeIf { ownsListening && !useReadingPosition }
        val target = existing ?: listeningChapter ?: return
        val start = from ?: if (useReadingPosition || (existing == null && ttsManager.savedParagraph(target) == null))
            (listState.firstVisibleItemIndex - 1).coerceAtLeast(0) else null
        ttsManager.play(target, start, prefs.ttsSpeed, prefs.ttsPitch, listeningContinuation)
    }

    var observedListeningChapter by remember(listeningBookKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(listening.chapter?.chapterKey, ownsListening) {
        val previous = observedListeningChapter
        val next = listening.chapter?.chapterKey?.takeIf { ownsListening }
        observedListeningChapter = next
        if (next != null && previous == page?.chapter?.id && next != previous) onOpenListeningChapter?.invoke(next)
    }

    LaunchedEffect(listening.completion) {
        val finished = listening.chapter ?: return@LaunchedEffect
        if (!ownsListening || !ttsManager.awaitingChapter(finished)) return@LaunchedEffect
        if (finished.chapterKey == page?.chapter?.id && canNext) {
            pendingNext = finished.chapterKey
            onLoadNext()
        } else ttsManager.finishBook()
    }
    LaunchedEffect(pendingNext, page?.chapter?.id, state.loading, state.error, isListening) {
        val previous = pendingNext ?: return@LaunchedEffect
        if (state.error != null || !isListening) {
            pendingNext = null
            if (ownsListening) ttsManager.finishBook()
        } else if (!state.loading && listeningChapter != null && listeningChapter.chapterKey != previous) {
            pendingNext = null
            ttsManager.continueWith(listeningChapter, prefs.ttsSpeed, prefs.ttsPitch)
        }
    }
    LaunchedEffect(activeParagraph, listState) {
        val item = (activeParagraph ?: return@LaunchedEffect) + 1
        val visible = listState.layoutInfo.visibleItemsInfo.any {
            it.index == item && it.offset >= listState.layoutInfo.viewportStartOffset &&
                it.offset < listState.layoutInfo.viewportEndOffset * .75f
        }
        if (!visible) listState.scrollToItem(item)
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
                                Modifier.semantics { contentDescription = com.example.i18n.AppLocale.t("正在加载正文") }
                            } else Modifier.clearAndSetSemantics { }
                        )
                        state.error != null -> OnlineNovelFailure(state.error, onRetry, Modifier.align(Alignment.Center))
                        else -> Text(tr("本章没有内容"), Modifier.align(Alignment.Center), color = colors.onSurfaceVariant)
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
                        highlighted = activeParagraph.takeIf { active },
                        marks = visibleMarks.map { it.mark }.takeIf { active }.orEmpty(),
                        onSelect = if (active) ({ value: ReaderTextSelection -> selection = value }) else null,
                        paragraphs = if (active) listeningChapter?.paragraphs.orEmpty() else remember(chapter.text) {
                            spokenParagraphs(chapter.text)
                        },
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
                    AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, tr("返回"), onBack, tint = colors.onSurface)
                },
                actions = {
                    if (marks.isNotEmpty()) {
                        AppIconButton(Icons.Filled.Bookmarks, tr("范围批注"), { showMarks = true })
                    }
                    AppIconButton(Icons.AutoMirrored.Filled.MenuBook, tr("词典查词"), {
                        val paragraphs = listeningChapter?.paragraphs.orEmpty()
                        dictionaryPassage = paragraphs.drop((listState.firstVisibleItemIndex - 1).coerceAtLeast(0))
                            .take(6).joinToString("\n") { it.text }.take(1200)
                    }, enabled = page != null)
                    AppIconButton(if (isListening) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Headphones,
                        tr("听书模式"), { followListening = ttsManager.followText; showListening = true },
                        enabled = page != null, active = isListening,
                        tint = if (isListening) colors.primary else colors.onSurface)
                    PullOutTextButton(
                        onClick = { fontSizeSp = (fontSizeSp - 1f).coerceAtLeast(12f) },
                        enabled = fontSizeSp > 12f,
                        modifier = Modifier.semantics { contentDescription = com.example.i18n.AppLocale.t("缩小字号") }
                    ) { Text("A−") }
                    PullOutTextButton(
                        onClick = { fontSizeSp = (fontSizeSp + 1f).coerceAtMost(26f) },
                        enabled = fontSizeSp < 26f,
                        modifier = Modifier.semantics { contentDescription = com.example.i18n.AppLocale.t("放大字号") }
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
                state.loading && delayedLoading -> tr("正在准备章节，当前正文仍可阅读") to false
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
                        AppIconButton(Icons.Filled.Refresh, tr("重试"), onRetry, enabled = state.error != null, tint = colors.primary)
                    }
                }
            }
        }
        if (showListening) {
            ReaderListeningPanel(ttsManager,
                if (ownsListening) listening else listening.copy(chapter = null, playing = false, preparing = false),
                listeningChapter, prefs, followListening,
                onFollow = { followListening = it; ttsManager.followText = it },
                onPlay = { play(it) }, onPlayFromReading = { play(useReadingPosition = true) },
                onDismiss = { showListening = false })
        }
        if (showMarks) {
            var close by remember { mutableStateOf(false) }
            var target by remember { mutableStateOf<Int?>(null) }
            WenkuSheetDialog(
                onDismissRequest = {
                    showMarks = false
                    val offset = target
                    val paragraphs = listeningChapter?.paragraphs.orEmpty()
                    if (offset != null && paragraphs.isNotEmpty()) scope.launch {
                        val index = paragraphs.indexOfLast { it.start <= offset }.coerceAtLeast(0)
                        listState.scrollToItem(index + 1)
                    }
                }, requestDismiss = close
            ) {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("范围批注"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        AppIconButton(Icons.Filled.Close, tr("关闭"), { close = true })
                    }
                    marks.forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).pullOutClickable {
                                target = item.mark.start
                                close = true
                            }.padding(vertical = 8.dp)) {
                                Text(item.text, maxLines = 2)
                                if (item.note.isNotBlank()) Text(item.note, maxLines = 2,
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            AppIconButton(Icons.Filled.DeleteOutline, tr("删除"), {
                                marks = markStore.remove(marks, item.id)
                            })
                        }
                        HorizontalDivider(color = colors.outlineVariant)
                    }
                }
            }
        }
        selection?.let {
            ReaderSelectionSheet(it, bookTitle.orEmpty(), title,
                onDismiss = { selection = null },
                onDictionary = { word -> dictionaryWord = word; dictionaryPassage = word },
                onMark = { start, end, text, note, underline ->
                    marks = markStore.save(marks, start, end, text, note, underline)
                })
        }
        dictionaryPassage?.let { ReaderDictionarySheet(it, dictionaryWord,
            onDismiss = { dictionaryPassage = null; dictionaryWord = "" }) }
    }
}

@Composable
private fun OnlineNovelBody(
    page: OnlineNovelPage,
    listState: LazyListState,
    fontSizeSp: Float,
    motion: Boolean,
    active: Boolean,
    highlighted: Int?,
    paragraphs: List<SpokenParagraph>,
    marks: List<ReaderMark>,
    onSelect: ((ReaderTextSelection) -> Unit)?,
    canPrevious: Boolean,
    canNext: Boolean,
    onToggleChrome: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val input = if (active) Modifier.pullOutClickable(onClickLabel = tr("切换阅读控件"), onClick = onToggleChrome)
        else Modifier.clearAndSetSemantics { }.wenkuBlockPointer()
    WenkuLoadedContentSequence(contentKey = page.chapter.id, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().then(input)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 760.dp).fillMaxSize(),
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
                val paragraph = paragraphs[index]
                val offset = paragraph.start + page.text.substring(paragraph.start, paragraph.end).indexOf(paragraph.text).coerceAtLeast(0)
                var layout by remember(paragraph) { mutableStateOf<TextLayoutResult?>(null) }
                Text(
                    buildAnnotatedString {
                        append(paragraph.text)
                        addReaderMarks(marks, offset, length)
                        if (highlighted == index) addStyle(SpanStyle(background = colors.primary.copy(alpha = .16f)), 0, length)
                    }, fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * 1.75f).sp,
                    color = colors.onSurface.copy(alpha = .9f),
                    onTextLayout = { layout = it },
                    modifier = (if (motion) Modifier.wenkuReveal(index + 1) else Modifier)
                        .readerTextLongPress(paragraph.text, offset, { layout }, onSelect)
                )
            }
            if (paragraphs.isEmpty()) item(key = "empty") { Text(tr("本章没有内容"), color = colors.onSurfaceVariant) }
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
                AppIconButton(Icons.Filled.SkipPrevious, tr("上一章"), onPrevious, enabled = canPrevious, tint = colors.onSurface)
                WenkuReaderProgressSlider(
                    value = value, label = tr("本章进度"), format = { "${(it * 100).roundToInt()}%" },
                    tint = colors.onSurface, enabled = scrollable, modifier = Modifier.weight(1f),
                    onValueChange = { preview = it },
                    onCommit = {
                        val target = preview
                        preview = null
                        if (target != null && lastItem > 0) onSeek((target * lastItem).roundToInt())
                    }
                )
                AppIconButton(Icons.Filled.SkipNext, tr("下一章"), onNext, enabled = canNext, tint = colors.onSurface)
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
