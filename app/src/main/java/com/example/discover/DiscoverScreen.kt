package com.example.discover

import com.example.i18n.tr

import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton
import com.example.ui.wenku.WenkuSectionHeader
import com.example.ui.components.pullOutClickable
import com.example.ui.components.PullOutTextButton
import com.example.ui.components.PullOutIconButton
import com.example.ui.components.PullOutBottomSheet
import com.example.ui.components.LocalOverlayClose
import com.example.ui.components.pullOutSelectable
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.favorite.*
import com.example.source.*
import com.example.ui.components.*
import com.example.ui.mascot.MascotSpriteSheet
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.feedback.LocalHapticsEnabled
import com.example.ui.theme.LocalAppBottomInset
import com.example.ui.theme.glassTitleColor
import com.example.ui.wenku.WenkuLoadedContentSequence
import com.example.ui.wenku.wenkuReveal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DiscoverScreen(manager: SourceManager, favorites: List<FavoriteItem>, favoriteKeys: Set<String>,
    onFollow: (SearchBook, Boolean) -> Unit, onOpen: (SearchBook) -> Unit,
    onLatest: (SearchBook) -> Unit, onSourceManagement: () -> Unit, readingTitles: Set<String> = emptySet(), refreshRequest: Int = 0, onOpenDownloads: () -> Unit = {}) {
    Box(Modifier.fillMaxSize()) {
    var partition by rememberSaveable { mutableStateOf(DiscoverPartition.COMIC) }
    val holder = rememberSaveableStateHolder()
    com.example.ui.wenku.WenkuPlaneTabSwitcher(
        targetState = partition,
        modifier = Modifier.fillMaxSize(),
        direction = { from, to -> to.ordinal - from.ordinal }
    ) { displayed ->
        holder.SaveableStateProvider(displayed.name) {
            DiscoverPartitionContent(manager, favorites, favoriteKeys, onFollow, onOpen, onLatest,
                onSourceManagement, readingTitles, refreshRequest, displayed, { partition = it })
        }
    }
        PullOutIconButton(
            icon = Icons.Outlined.Download,
            contentDescription = com.example.i18n.AppLocale.t("下载任务"),
            onClick = onOpenDownloads,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = LocalAppBottomInset.current + 8.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DiscoverPartitionContent(manager: SourceManager, favorites: List<FavoriteItem>, favoriteKeys: Set<String>,
    onFollow: (SearchBook, Boolean) -> Unit, onOpen: (SearchBook) -> Unit,
    onLatest: (SearchBook) -> Unit, onSourceManagement: () -> Unit, readingTitles: Set<String>, refreshRequest: Int,
    partition: DiscoverPartition, onPartition: (DiscoverPartition) -> Unit) {
    val (store, revision) = rememberDiscoverStore(partition)
    val model: DiscoverViewModel = viewModel(key = "discover_${partition.name}", factory = remember(manager, store) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = DiscoverViewModel(manager, store, partition) as T
        }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val all by manager.allSources.collectAsStateWithLifecycle()
    val enabled by manager.enabledStates.collectAsStateWithLifecycle()
    val sources = all.filter(partition::accepts)
    var sourceId by rememberSaveable { mutableStateOf(store.string("last_source", "aggregate")) }
    var dimension by rememberSaveable { mutableStateOf(DiscoverDimension.entries.firstOrNull {
        it in listOf(DiscoverDimension.UPDATES, DiscoverDimension.RANKING) && it.name == store.string("last_dimension", "UPDATES") } ?: DiscoverDimension.UPDATES) }
    LaunchedEffect(dimension) {
        if (dimension !in listOf(DiscoverDimension.UPDATES, DiscoverDimension.RANKING)) dimension = DiscoverDimension.UPDATES
    }
    var range by rememberSaveable { mutableStateOf(store.string("range_${dimension.name}", dimension.ranges.first()).takeIf { it in dimension.ranges } ?: dimension.ranges.first()) }
    val layout = DiscoverLayout.entries.firstOrNull { it.name == store.string("layout", "GRID") } ?: DiscoverLayout.GRID
    val selected = store.selection("categories") - DiscoverTaxonomy.roots.map { it.id }.toSet()
    val blocked = store.selection("blocked")
    var expanded by rememberSaveable { mutableStateOf(false) }
    var statusFilter by rememberSaveable(sourceId) { mutableStateOf(com.example.i18n.AppLocale.t("全部")) }
    var nativeFeed by rememberSaveable(sourceId) { mutableStateOf<String?>(store.string("feed_$sourceId", "native").ifBlank { null }) }
    var nativeCategory by rememberSaveable(sourceId) { mutableStateOf<String?>(store.string("category_$sourceId", "").ifBlank { null }) }
    var noticesExpanded by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<DiscoverWork?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var menuWork by remember { mutableStateOf<DiscoverWork?>(null) }
    var menuBounds by remember { mutableStateOf(Rect.Zero) }
    var screenOffset by remember { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    // library_reflow.dart: content re-ordering keeps each card position-anchored (420ms).
    val discoverReflow = com.example.ui.wenku.rememberWenkuReflowState()
    val reduce = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalHapticsEnabled.current
    fun light() { if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val statusScope = discoverStatusScope(dimension, range, nativeFeed, nativeCategory, state.catalogs[sourceId])
    val effectiveStatus = statusScope.effectiveStatus(statusFilter)
    val rows = remember(state.entries, sourceId, dimension, range, state.catalogs, selected, blocked, statusFilter, favorites, readingTitles, revision, nativeFeed, nativeCategory) {
        val merged = filterWorkStatus(DiscoverEngine.merge(state.entries, dimension, sourceId == "aggregate"), effectiveStatus).filter { work ->
            work.key !in blocked && work.variants.none { DiscoverEngine.identity(it) in blocked } && DiscoverTaxonomy.matches(work.tags, selected)
        }
        val favoriteAuthors = favorites.map { it.favorite.author }.filter { it.isKnownComicAuthor() }.toSet()
        if (store.flag("personalization") && nativeFeed == null && nativeCategory == null)
            DiscoverEngine.personalize(merged, dimension) { w ->
                (if (w.variants.any { it.book.author in favoriteAuthors }) 1 else 0) -
                    (if (w.variants.any { it.book.title in readingTitles }) 1 else 0)
            } else merged
    }
    LaunchedEffect(refreshRequest) {
        if (model.consumeRefreshRequest(refreshRequest)) { grid.scrollToItem(0); model.refresh() }
    }
    LaunchedEffect(sources, enabled, sourceId, dimension, range, nativeFeed, nativeCategory, selected, effectiveStatus) {
        if (sourceId != "aggregate" && sources.none { it.id == sourceId }) { sourceId = "aggregate"; return@LaunchedEffect }
        model.configure(sourceId, dimension, range, nativeFeed, nativeCategory, selected)
        store.put("last_source", sourceId); store.put("last_dimension", dimension.name)
        store.put("range_${dimension.name}", range)
        store.put("feed_$sourceId", nativeFeed.orEmpty()); store.put("category_$sourceId", nativeCategory.orEmpty())
        if (nativeFeed != null) store.put("native_feed_$sourceId", nativeFeed!!)
    }
    val nearEnd by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 && (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, state.loading, state.more) { if (nearEnd && !state.loading && state.more.isNotEmpty()) model.more() }
    val viewportKey = "$sourceId:${dimension.name}:$range:$nativeFeed:$nativeCategory:${selected.sorted()}:$statusFilter"
    var filterScanCount by remember(viewportKey, statusFilter) { mutableIntStateOf(0) }
    LaunchedEffect(rows.size, state.loading, state.more, viewportKey, statusFilter) {
        if (!state.loading && rows.size < 4 && state.more.isNotEmpty() && filterScanCount < 3 &&
            (selected.isNotEmpty() || effectiveStatus != com.example.i18n.AppLocale.t("全部") || (nativeFeed == null && dimension == DiscoverDimension.UPDATES && range != com.example.i18n.AppLocale.t("最新")))) {
            filterScanCount++; model.more()
        }
    }
    var lastViewportKey by rememberSaveable { mutableStateOf(viewportKey) }
    var restoreViewport by remember { mutableStateOf(false) }
    LaunchedEffect(viewportKey, state.loading) {
        if (lastViewportKey != viewportKey) {
            model.saveViewport(lastViewportKey, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
            lastViewportKey = viewportKey; restoreViewport = true; grid.scrollToItem(0)
        }
        if (restoreViewport && !state.loading) {
            model.viewport(viewportKey)?.let { (index, offset) -> grid.scrollToItem(index.coerceAtMost((grid.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)), offset) }
            restoreViewport = false
        }
    }
    fun open(work: DiscoverWork) {
        if (work.variants.size > 1) { choice = work }
        else onOpen(work.book)
    }
    fun switch(next: DiscoverDimension) { light(); nativeFeed = null; nativeCategory = null
        dimension = next; statusFilter = com.example.i18n.AppLocale.t("全部"); range = store.string("range_${next.name}", next.ranges.first()).takeIf { it in next.ranges } ?: next.ranges.first() }
    fun follow(work: DiscoverWork, next: Boolean) {
        light()
        val followed = work.variants.filter { favoriteKey(it.book.sourceId, it.book.id) in favoriteKeys }
        if (next && followed.isEmpty()) onFollow(work.book, true)
        else if (!next) followed.forEach { onFollow(it.book, false) }
    }
    fun block(work: DiscoverWork) { store.block(work) }
    fun share(work: DiscoverWork) { scope.launch {
        com.example.library.FavoriteShareHelper.shareFavorites(context,
            listOf(FavoriteEntity(work.book.sourceId, work.book.id, work.book.title, work.book.author, work.book.cover)), manager::getSource)
            ?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
    } }

    val activeSources = sources.filter { enabled[it.id] != false && (sourceId == "aggregate" || it.id == sourceId) }
    val currentCatalogs = activeSources.mapNotNull { state.catalogs[it.id] }
    val availableDimensions = listOf(DiscoverDimension.UPDATES, DiscoverDimension.RANKING).filter { d -> sourceId == "aggregate" || currentCatalogs.isEmpty() || currentCatalogs.any { it.ranges(d).isNotEmpty() || it.feeds.any { f -> f.kind == null } } }
    val availableRanges = dimension.ranges.filter { r -> sourceId == "aggregate" || currentCatalogs.isEmpty() || currentCatalogs.any { r in it.ranges(dimension) || it.feeds.any { f -> f.kind == null } } }
    LaunchedEffect(sourceId, state.loading, availableDimensions, availableRanges, nativeFeed, nativeCategory) {
        if (sourceId != "aggregate" && !state.loading && nativeFeed == null && nativeCategory == null && (dimension !in availableDimensions || range !in availableRanges)) {
            if (availableRanges.isNotEmpty()) range = availableRanges.first() else nativeFeed = "native"
        }
    }
    val windowWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val gridLayout = layout == DiscoverLayout.GRID &&
        (dimension != DiscoverDimension.RANKING || nativeFeed != null || nativeCategory != null || selected.isNotEmpty())
    val columns = when {
        !gridLayout -> 1
        windowWidth >= 840 -> 4
        windowWidth >= 600 -> 3
        else -> 2
    }
    val headerDistance = with(LocalDensity.current) { 150.dp.toPx() }
    val headerFraction = remember(grid, headerDistance) { { if (grid.firstVisibleItemIndex > 0) 1f else (grid.firstVisibleItemScrollOffset / headerDistance).coerceIn(0f, 1f) } }
    Box(Modifier.fillMaxSize().testTag("discover_screen").onGloballyPositioned { screenOffset = it.positionInRoot() }) {
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = if (gridLayout) 1040.dp else 760.dp).fillMaxSize()) {
            CollapsingTabScreenHeader(fractionSource = headerFraction, modifier = Modifier.statusBarsPadding(), title = com.example.i18n.AppLocale.t("发现"), subtitle = "DISCOVER",
                onTitleClick = { scope.launch { if (reduce) grid.scrollToItem(0) else grid.animateScrollToItem(0) } },
                titleColor = glassTitleColor(), trailing = {
                    PullOutIconButton(
                                    icon = Icons.Outlined.Tune,
                                    contentDescription = com.example.i18n.AppLocale.t("发现设置"),
                                    onClick = { showSettings = true }
                                )
                })
            PullToRefreshBox(isRefreshing = state.loading && state.entries.isNotEmpty(), onRefresh = { light(); model.refresh() }, modifier = Modifier.weight(1f),
                ) {
                WenkuLoadedContentSequence(
                    contentKey = viewportKey,
                    loading = state.loading && rows.isEmpty(),
                    modifier = Modifier.fillMaxSize()
                ) {
                com.example.ui.wenku.WenkuReflowScope(state = discoverReflow, trigger = dimension to (selected.toList() to nativeFeed), modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid,
                    modifier = Modifier.fillMaxSize().testTag("discover_content"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = LocalAppBottomInset.current + 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(key = "partition", span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth().testTag("discover_partition"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DiscoverPartition.entries.forEach { option ->
                                WenkuTextButton(
                                    option.label, { onPartition(option) },
                                    modifier = Modifier.weight(1f).semantics { this.selected = partition == option },
                                    selected = partition == option,
                                    wenkuSymbol = if (option == DiscoverPartition.COMIC) WenkuSymbol.Image else WenkuSymbol.Book,
                                )
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val native = nativeFeed != null || nativeCategory != null
                        val feeds = if (nativeFeed != null && sourceId != "aggregate") currentCatalogs.flatMap { it.feeds }.distinctBy { it.id } else emptyList()
                        val ranges = if (!native && availableRanges.size > 1) availableRanges else emptyList()
                        val statusKnown = currentCatalogs.any { c -> c.statuses.isNotEmpty() || c.categories.any { serialLabel(it.label) in setOf(com.example.i18n.AppLocale.t("连载"), com.example.i18n.AppLocale.t("完结")) } } ||
                            state.entries.any { e -> activeSources.any { it.id == e.book.sourceId } && serialLabel(e.book.discoverStatus) in setOf(com.example.i18n.AppLocale.t("连载"), com.example.i18n.AppLocale.t("完结")) }
                        val unknownStatus = state.entries.any { serialLabel(it.book.discoverStatus) == com.example.i18n.AppLocale.t("状态未知") }
                        DiscoverFilters(
                            tabs = listOf(com.example.i18n.AppLocale.t("推荐") to native) + availableDimensions.map { com.example.i18n.tr(it.label) to (!native && dimension == it) },
                            onTab = { index -> if (index == 0) { light(); nativeFeed = store.string("native_feed_$sourceId", "native"); nativeCategory = null } else switch(availableDimensions[index - 1]) },
                            source = if (sourceId == "aggregate") com.example.i18n.AppLocale.t("聚合书源") else sources.firstOrNull { it.id == sourceId }?.name ?: com.example.i18n.AppLocale.t("选择书源"),
                            onSource = { showSources = true }, selected = selected, onCategory = { expanded = true },
                            onRemove = { store.put("categories", selected - it) }, onClear = { store.put("categories", emptySet()) },
                            refreshing = state.loading, onRefresh = { light(); model.refresh() },
                            sorts = if (feeds.isNotEmpty()) feeds.map { com.example.i18n.tr(it.label) to (nativeFeed == it.id || (nativeFeed == "native" && it == feeds.first())) } else ranges.map { com.example.discover.discoverRangeLabel(it) to (range == it) },
                            onSort = { index -> light(); if (feeds.isNotEmpty()) { nativeFeed = feeds[index].id; nativeCategory = null; statusFilter = com.example.i18n.AppLocale.t("全部") } else { range = ranges[index]; statusFilter = com.example.i18n.AppLocale.t("全部") } },
                            statuses = if (statusKnown && statusScope.showStatusChoices) listOf(com.example.i18n.AppLocale.t("全部"), com.example.i18n.AppLocale.t("连载"), com.example.i18n.AppLocale.t("完结")) + (if (state.entries.any { serialLabel(it.book.discoverStatus) == com.example.i18n.AppLocale.t("停更") }) listOf(com.example.i18n.AppLocale.t("停更")) else emptyList()) + (if (unknownStatus) listOf(com.example.i18n.AppLocale.t("状态未知")) else emptyList()) else emptyList(),
                            status = effectiveStatus, statusHint = statusScope.hint, onStatus = { light(); statusFilter = it })
                    }
                    if (sourceId == "aggregate" || rows.isNotEmpty()) {
                        item(key = "discover_results_heading", span = { GridItemSpan(maxLineSpan) }) {
                            val sourceProgress: (@Composable () -> Unit)? = if (sourceId == "aggregate") {
                                val participantCount = rows.flatMap { it.variants }.map { it.book.sourceId }.distinct().size
                                val action: @Composable () -> Unit = {
                                    WenkuTextButton(
                                        com.example.i18n.sourcesOf(participantCount, activeSources.size),
                                        { noticesExpanded = true },
                                        compact = true,
                                        wenkuSymbol = if (state.loading) WenkuSymbol.Sync else WenkuSymbol.Info,
                                    )
                                }
                                action
                            } else null
                            if (rows.isEmpty()) {
                                sourceProgress?.invoke()
                            } else {
                                WenkuSectionHeader("01", "BROWSE / CATALOGUE", com.example.i18n.AppLocale.t("浏览结果"), trailing = sourceProgress)
                            }
                        }
                    }
                    if (state.errors.isNotEmpty()) item(key = "discover_error_summary", span = { GridItemSpan(maxLineSpan) }) {
                        GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (rows.isEmpty()) com.example.i18n.AppLocale.t("连接中断，已保留上次内容") else "${state.errors.size} 个书源暂时连接不上",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                PullOutIconButton(Icons.Outlined.Refresh, com.example.i18n.AppLocale.t("重试"), onClick = model::retry, glyphSize = 18.dp)
                                PullOutIconButton(Icons.Outlined.Info, com.example.i18n.AppLocale.t("查看详情"), onClick = { noticesExpanded = true }, glyphSize = 18.dp)
                            }
                        }
                    }
                    if (rows.isEmpty() && !state.loading) item(span = { GridItemSpan(maxLineSpan) }) {
                        DiscoverGlass(Modifier.fillMaxWidth()) {
                            DiscoverEmptyState(if (selected.isNotEmpty()) MascotSpriteSheet.sadDrawable else MascotSpriteSheet.idleDrawable,
                                if (state.more.isNotEmpty()) com.example.i18n.AppLocale.t("已加载作品暂无匹配") else if (selected.isNotEmpty() || statusFilter != com.example.i18n.AppLocale.t("全部")) com.example.i18n.AppLocale.t("换个分类，遇见新故事") else com.example.i18n.AppLocale.t("这个书源暂时没有数据"),
                                if (state.more.isNotEmpty()) com.example.i18n.AppLocale.t("还有更多原站内容，可以继续查找") else if (selected.isNotEmpty()) com.example.i18n.AppLocale.t("试试其他分类，或清除筛选继续浏览") else com.example.i18n.AppLocale.t("下拉刷新，或换个书源继续发现"), actionLabel = if (state.more.isNotEmpty()) com.example.i18n.AppLocale.t("继续查找") else if (selected.isNotEmpty() || statusFilter != com.example.i18n.AppLocale.t("全部")) com.example.i18n.AppLocale.t("清除筛选") else com.example.i18n.AppLocale.t("原站推荐"),
                                onActionClick = { if (state.more.isNotEmpty()) model.more() else if (selected.isNotEmpty() || statusFilter != com.example.i18n.AppLocale.t("全部")) { store.put("categories", emptySet()); statusFilter = com.example.i18n.AppLocale.t("全部") }
                                    else { nativeFeed = "native"; nativeCategory = null } })
                        }
                    }
                    itemsIndexed(rows, key = { _, w -> w.key }) { index, work ->
                        com.example.ui.wenku.WenkuReflowItem(key = work.key, state = discoverReflow, order = index) {
                        DiscoverCard(work, dimension, if (dimension == DiscoverDimension.RANKING && nativeFeed == null && nativeCategory == null && selected.isEmpty()) DiscoverLayout.LIST else layout,
                            rank = if (dimension == DiscoverDimension.RANKING && nativeFeed == null && nativeCategory == null && selected.isEmpty()) { if (sourceId == "aggregate") index + 1 else work.variants.first().rank ?: index + 1 } else null,
                            followed = work.variants.any { favoriteKey(it.book.sourceId, it.book.id) in favoriteKeys },
                            unread = false,
                            reverseColors = store.flag("green_rise", false), onClick = { open(work) },
                            onFollow = { follow(work, it) }, onBlock = { block(work) },
                            onMenu = { menuWork = work; menuBounds = it }, menuActive = menuWork?.key == work.key,
                            sourceLabel = { id -> sources.firstOrNull { it.id == id }?.name ?: id }, entryIndex = index,
                            coverSource = { manager.getSource(it) })
                    }
                    }
                    if (state.loading) items(if (rows.isEmpty()) 6 else 2) {
                        DiscoverSkeleton(if (dimension == DiscoverDimension.RANKING) DiscoverLayout.LIST else layout)
                    }
                    if (!state.loading && state.more.isEmpty() && rows.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(tr("已看完当前书源的可用内容 · 换个维度继续发现"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(16.dp))
                    }
                }
                }
                }
            }
        }
        choice?.let { work ->
            PullOutBottomSheet(onDismissRequest = { choice = null }) {
                val close = LocalOverlayClose.current
                Column(Modifier.padding(16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(work.book.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("${work.variants.size} 个源可读 · 选择阅读书源", fontSize = 13.sp)
                    work.variants.forEach { entry ->
                        DiscoverGlass(Modifier.fillMaxWidth().pullOutClickable { close { onOpen(entry.book) } }) {
                            Text(sources.firstOrNull { it.id == entry.book.sourceId }?.name ?: entry.book.sourceId, fontWeight = FontWeight.SemiBold)
                            Text(metric(DiscoverWork(work.key, listOf(entry), 0.0), dimension), fontSize = 12.sp)
                            entry.book.discoverStatus?.let { Text(serialLabel(it), fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
        menuWork?.let { work ->
            com.example.ui.components.PullOutDropdownMenu(
                expanded = true,
                onDismissRequest = { menuWork = null },
                anchorInWindow = menuBounds.takeUnless { it == Rect.Zero }
            ) {
                val followed = work.variants.any { favoriteKey(it.book.sourceId, it.book.id) in favoriteKeys }
                val actions = listOf(Triple(Icons.Outlined.FavoriteBorder, if (followed) com.example.i18n.AppLocale.t("取消喜欢") else com.example.i18n.AppLocale.t("加入我喜欢的"), { follow(work, !followed) }),
                    Triple(Icons.Outlined.VisibilityOff, com.example.i18n.AppLocale.t("不感兴趣"), { block(work) }), Triple(Icons.Outlined.Share, com.example.i18n.AppLocale.t("分享"), { share(work) }))
                actions.forEachIndexed { index, action ->
                    com.example.ui.components.PullOutDropdownMenuItem(
                        text = { Text(action.second, color = if (index == 1) DiscoverRise else discoverInk()) },
                        leadingIcon = { Icon(action.first, null, tint = if (index == 1) DiscoverRise else discoverInk()) },
                        onClick = com.example.ui.components.paperDismiss(action.third))
                }
            }
        }
        // 常驻组合、只翻 visible：WenkuSheetHost 需要看到 visible 从 true 变 false 才有退场。
        DiscoverSourceStatusSheet(visible = noticesExpanded, activeSources, rows, state, effectiveStatus,
            onRetry = model::retry, onDismiss = { noticesExpanded = false })
        if (showSettings) DiscoverSettings(partition) { showSettings = false }
        if (showSources) DiscoverSourcePicker(sources, enabled, sourceId, { id ->
            sourceId = id; nativeFeed = store.string("feed_$id", "native").ifBlank { null }
            nativeCategory = store.string("category_$id", "").ifBlank { null }; showSources = false
        }, { showSources = false }, { showSources = false; onSourceManagement() })
        if (expanded) DiscoverCategorySheet(state.entries.filter { e -> activeSources.any { it.id == e.book.sourceId } }, selected,
            activeSources.mapNotNull { s -> state.catalogs[s.id]?.let { s to it } },
            { store.put("categories", it); expanded = false }, { id, category ->
                sourceId = id; nativeFeed = null; nativeCategory = category; store.put("categories", emptySet()); expanded = false
            }, { expanded = false }, model::loadCategories, partition = partition)

    }
}

@Composable
internal fun DiscoverChip(label: String, selected: Boolean, onClick: () -> Unit, solid: Boolean = true,
    visualHeight: Int = 32, dimmed: Boolean = false, icon: ImageVector? = null, outlined: Boolean = false, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).alpha(if (dimmed) .5f else 1f)
        .discoverPressSelect(selected, role = Role.Checkbox, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = visualHeight.dp).clip(CircleShape)
            .background(if (selected) { if (solid) primary else discoverTint() } else if (solid) MaterialTheme.colorScheme.surface.copy(alpha = .40f) else Color.Transparent)
            .then(if (outlined) Modifier.border(1.dp, if (selected) primary else discoverInk().copy(alpha = .12f), CircleShape) else Modifier)
            .padding(horizontal = if (visualHeight == 36) 14.dp else 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint = if (selected && solid) contrastInk(primary) else primary); Spacer(Modifier.width(4.dp)) }
            Text(label, fontSize = (if (visualHeight == 36) 14 else if (visualHeight == 32) 13 else if (visualHeight == 24) 11 else 12).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected && solid) contrastInk(primary) else if (selected) discoverAccentText() else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * 把源站返回的状态归一化成界面用的三种状态之一。
 *
 * 注意 `when` 的条件里**中文原文必须单独列出来**：书源返回的是中文（"连载"/"已完结"…），
 * 而 `AppLocale.t("连载")` 在英文模式下是 "Ongoing" —— 只用 `t(...)` 当 key 的话，
 * 英文模式下所有中文状态都会掉进 `else` 变成"状态未知"。
 */
internal fun serialLabel(status: String?): String = when (status?.trim()?.lowercase(java.util.Locale.ROOT)) {
    "ongoing", "publishing", "serial", "连载", "連載", "连载中", "連載中", "1" -> com.example.i18n.AppLocale.t("连载")
    "completed", "complete", "finished", "ended", "完结", "完結", "已完结", "已完結", "2" -> com.example.i18n.AppLocale.t("完结")
    "hiatus", "cancelled", "停更", "休刊", "暂停更新", "已暂停", "3", "4" -> com.example.i18n.AppLocale.t("停更")
    else -> com.example.i18n.AppLocale.t("状态未知")
}
internal fun filterWorkStatus(works: List<DiscoverWork>, status: String): List<DiscoverWork> =
    if (status == com.example.i18n.AppLocale.t("全部")) works else works.mapNotNull { work ->
        work.variants.filter { serialLabel(it.book.discoverStatus) == status }.takeIf { it.isNotEmpty() }?.let { work.copy(variants = it) }
    }
internal fun workStatus(work: DiscoverWork): String {
    val statuses = work.variants.map { serialLabel(it.book.discoverStatus) }.toSet()
    return listOf(com.example.i18n.AppLocale.t("完结"), com.example.i18n.AppLocale.t("连载"), com.example.i18n.AppLocale.t("停更")).firstOrNull { it in statuses } ?: com.example.i18n.AppLocale.t("状态未知")
}
internal fun dateLabel(time: Long?): String = time?.let {
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(it)) } ?: com.example.i18n.AppLocale.t("未提供")
