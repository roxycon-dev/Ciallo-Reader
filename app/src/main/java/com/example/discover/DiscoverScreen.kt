package com.example.discover

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DiscoverScreen(manager: SourceManager, favorites: List<FavoriteItem>, favoriteKeys: Set<String>,
    onFollow: (SearchBook, Boolean) -> Unit, onOpen: (SearchBook) -> Unit,
    onLatest: (SearchBook) -> Unit, onSourceManagement: () -> Unit, readingTitles: Set<String> = emptySet(), refreshRequest: Int = 0) {
    val (store, revision) = rememberDiscoverStore()
    val model: DiscoverViewModel = viewModel(key = "discover", factory = remember(manager, store) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = DiscoverViewModel(manager, store) as T
        }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val all by manager.allSources.collectAsStateWithLifecycle()
    val enabled by manager.enabledStates.collectAsStateWithLifecycle()
    val sources = all.filter { it.isComicSource }
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
    var statusFilter by rememberSaveable(sourceId) { mutableStateOf("全部") }
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
    val reduce = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalHapticsEnabled.current
    fun light() { if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val rows = remember(state.entries, sourceId, dimension, range, state.catalogs, selected, blocked, statusFilter, favorites, readingTitles, revision, nativeFeed, nativeCategory) {
        val completedFeed = sourceId != "aggregate" && nativeFeed != null && state.catalogs[sourceId]?.feeds?.let { feeds ->
            (if (nativeFeed == "native") feeds.firstOrNull() else feeds.firstOrNull { it.id == nativeFeed })?.label?.contains(Regex("完结|完結|completed|finished", RegexOption.IGNORE_CASE))
        } == true
        val completedOnly = completedFeed || (nativeFeed == null && nativeCategory == null && dimension == DiscoverDimension.RANKING && range == "完结")
        val completed = filterWorkStatus(DiscoverEngine.merge(state.entries, dimension, sourceId == "aggregate"),
            if (completedOnly) "完结" else "全部")
        val merged = filterWorkStatus(completed, statusFilter).filter { work ->
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
    LaunchedEffect(sources, enabled, sourceId, dimension, range, nativeFeed, nativeCategory, selected, statusFilter) {
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
            (selected.isNotEmpty() || statusFilter != "全部" || (nativeFeed == null && dimension == DiscoverDimension.UPDATES && range != "最新"))) {
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
        dimension = next; statusFilter = "全部"; range = store.string("range_${next.name}", next.ranges.first()).takeIf { it in next.ranges } ?: next.ranges.first() }
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
    val headerDistance = with(LocalDensity.current) { 150.dp.toPx() }
    val headerFraction = remember(grid, headerDistance) { { if (grid.firstVisibleItemIndex > 0) 1f else (grid.firstVisibleItemScrollOffset / headerDistance).coerceIn(0f, 1f) } }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("discover_screen").onGloballyPositioned { screenOffset = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            CollapsingTabScreenHeader(fractionSource = headerFraction, modifier = Modifier.statusBarsPadding(), title = "发现", subtitle = "DISCOVER",
                onTitleClick = { scope.launch { if (reduce) grid.scrollToItem(0) else grid.animateScrollToItem(0) } },
                titleColor = glassTitleColor(), trailing = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Tune, "发现设置") }
                })
            PullToRefreshBox(isRefreshing = state.loading && state.entries.isNotEmpty(), onRefresh = { light(); model.refresh() }, modifier = Modifier.weight(1f),
                ) {
                LazyVerticalGrid(columns = GridCells.Fixed(if (layout == DiscoverLayout.GRID && (dimension != DiscoverDimension.RANKING || nativeFeed != null || nativeCategory != null || selected.isNotEmpty())) 2 else 1), state = grid,
                    modifier = Modifier.fillMaxSize().testTag("discover_content"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = LocalAppBottomInset.current + 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val native = nativeFeed != null || nativeCategory != null
                        val feeds = if (nativeFeed != null && sourceId != "aggregate") currentCatalogs.flatMap { it.feeds }.distinctBy { it.id } else emptyList()
                        val ranges = if (!native && availableRanges.size > 1) availableRanges else emptyList()
                        val statusKnown = currentCatalogs.any { c -> c.statuses.isNotEmpty() || c.categories.any { serialLabel(it.label) in setOf("连载", "完结") } } ||
                            state.entries.any { e -> activeSources.any { it.id == e.book.sourceId } && serialLabel(e.book.comicInfo?.status) in setOf("连载", "完结") }
                        val unknownStatus = state.entries.any { serialLabel(it.book.comicInfo?.status) == "状态未知" }
                        DiscoverFilters(
                            tabs = listOf("推荐" to native) + availableDimensions.map { it.label to (!native && dimension == it) },
                            onTab = { index -> if (index == 0) { light(); nativeFeed = store.string("native_feed_$sourceId", "native"); nativeCategory = null } else switch(availableDimensions[index - 1]) },
                            source = if (sourceId == "aggregate") "聚合书源" else sources.firstOrNull { it.id == sourceId }?.name ?: "选择书源",
                            onSource = { showSources = true }, selected = selected, onCategory = { expanded = true },
                            onRemove = { store.put("categories", selected - it) }, onClear = { store.put("categories", emptySet()) },
                            refreshing = state.loading, onRefresh = { light(); model.refresh() },
                            sorts = if (feeds.isNotEmpty()) feeds.map { it.label to (nativeFeed == it.id || (nativeFeed == "native" && it == feeds.first())) } else ranges.map { it to (range == it) },
                            onSort = { index -> light(); if (feeds.isNotEmpty()) { nativeFeed = feeds[index].id; nativeCategory = null; statusFilter = "全部" } else { range = ranges[index]; statusFilter = "全部" } },
                            statuses = if (statusKnown) listOf("全部", "连载", "完结") + (if (state.entries.any { serialLabel(it.book.comicInfo?.status) == "停更" }) listOf("停更") else emptyList()) + (if (unknownStatus) listOf("状态未知") else emptyList()) else emptyList(),
                            status = statusFilter, onStatus = { light(); statusFilter = it })
                    }
                    if (sourceId == "aggregate") item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                            val participants = rows.flatMap { it.variants }.map { it.book.sourceId }.distinct()
                            TextButton(onClick = { noticesExpanded = !noticesExpanded }) {
                                Text("当前结果 ${participants.size} 个源 · 已请求 ${activeSources.size} 个源" + if (state.loading) " · 加载中" else " · 查看来源", style = MaterialTheme.typography.labelMedium)
                            }
                            if (noticesExpanded) activeSources.forEach { source ->
                                val count = rows.count { w -> w.variants.any { it.book.sourceId == source.id } }
                                Text(source.name + " · " + if (count > 0) "${count} 本" else state.errors[source.id] ?: state.notices[source.id] ?: if (state.loading) "加载中" else if (statusFilter != "全部" && state.entries.any { it.book.sourceId == source.id && serialLabel(it.book.comicInfo?.status) == "状态未知" }) "作品状态尚未提供或待补全" else "当前筛选无结果",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (state.errors.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        GlassCard(contentPadding = PaddingValues(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (rows.isEmpty()) "暂时连接不上，已保留上次内容" else "${state.errors.size} 个书源暂时连接不上", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = model::retry) { Text("重试") }
                            }
                            if (noticesExpanded) state.errors.forEach { (id, error) -> Text("${sources.firstOrNull { it.id == id }?.name ?: id}：$error", style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { noticesExpanded = !noticesExpanded }) { Text(if (noticesExpanded) "收起" else "查看详情") }
                        }
                    }
                    if (rows.isEmpty() && !state.loading) item(span = { GridItemSpan(maxLineSpan) }) {
                        DiscoverGlass(Modifier.fillMaxWidth()) {
                            DiscoverEmptyState(if (selected.isNotEmpty()) MascotSpriteSheet.sadDrawable else MascotSpriteSheet.idleDrawable,
                                if (state.more.isNotEmpty()) "已加载作品暂无匹配" else if (selected.isNotEmpty() || statusFilter != "全部") "换个分类，遇见新故事" else "这个书源暂时没有数据",
                                if (state.more.isNotEmpty()) "还有更多原站内容，可以继续查找" else if (selected.isNotEmpty()) "试试其他分类，或清除筛选继续浏览" else "下拉刷新，或换个书源继续发现", actionLabel = if (state.more.isNotEmpty()) "继续查找" else if (selected.isNotEmpty() || statusFilter != "全部") "清除筛选" else "原站推荐",
                                onActionClick = { if (state.more.isNotEmpty()) model.more() else if (selected.isNotEmpty() || statusFilter != "全部") { store.put("categories", emptySet()); statusFilter = "全部" }
                                    else { nativeFeed = "native"; nativeCategory = null } })
                        }
                    }
                    itemsIndexed(rows, key = { _, w -> w.key }) { index, work ->
                        DiscoverCard(work, dimension, if (dimension == DiscoverDimension.RANKING && nativeFeed == null && nativeCategory == null && selected.isEmpty()) DiscoverLayout.LIST else layout,
                            rank = if (dimension == DiscoverDimension.RANKING && nativeFeed == null && nativeCategory == null && selected.isEmpty()) { if (sourceId == "aggregate") index + 1 else work.variants.first().rank ?: index + 1 } else null,
                            followed = work.variants.any { favoriteKey(it.book.sourceId, it.book.id) in favoriteKeys },
                            unread = false,
                            reverseColors = store.flag("green_rise", false), onClick = { open(work) },
                            onFollow = { follow(work, it) }, onBlock = { block(work) },
                            onMenu = { menuWork = work; menuBounds = it }, menuActive = menuWork?.key == work.key,
                            sourceLabel = { id -> sources.firstOrNull { it.id == id }?.name ?: id }, entryIndex = index,
                            coverSource = { manager.getSource(it) as? ComicSource })
                    }
                    if (state.loading) items(if (rows.isEmpty()) 6 else 2) {
                        DiscoverSkeleton(if (dimension == DiscoverDimension.RANKING) DiscoverLayout.LIST else layout)
                    }
                    if (!state.loading && state.more.isEmpty() && rows.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("已看完当前书源的可用内容 · 换个维度继续发现", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
        choice?.let { work ->
            ModalBottomSheet(onDismissRequest = { choice = null }) {
                Column(Modifier.padding(16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(work.book.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("${work.variants.size} 个源可读 · 选择阅读书源", fontSize = 13.sp)
                    work.variants.forEach { entry ->
                        DiscoverGlass(Modifier.fillMaxWidth().clickable { choice = null; onOpen(entry.book) }) {
                            Text(sources.firstOrNull { it.id == entry.book.sourceId }?.name ?: entry.book.sourceId, fontWeight = FontWeight.SemiBold)
                            Text(metric(DiscoverWork(work.key, listOf(entry), 0.0), dimension), fontSize = 12.sp)
                            entry.book.comicInfo?.status?.let { Text(serialLabel(it), fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
        menuWork?.let { work ->
            androidx.activity.compose.BackHandler { menuWork = null }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .32f)).clickable { menuWork = null })
            val density = LocalDensity.current
            val left = with(density) { (menuBounds.left - screenOffset.x).toDp() }
            val top = with(density) { (menuBounds.top - screenOffset.y).toDp() }
            val menuHeight = 148.dp
            val maxMenuY = (maxHeight - LocalAppBottomInset.current - menuHeight - 16.dp).coerceAtLeast(16.dp)
            val x = left.coerceIn(16.dp, (maxWidth - 216.dp).coerceAtLeast(16.dp))
            val y = (if (top + menuHeight > maxHeight - LocalAppBottomInset.current - 16.dp) top - menuHeight - 8.dp else top + 16.dp).coerceIn(16.dp, maxMenuY)
            DiscoverGlass(Modifier.offset(x = x, y = y).width(200.dp).semantics { paneTitle = "作品快捷菜单" }, RoundedCornerShape(20.dp), PaddingValues(0.dp)) {
                val followed = work.variants.any { favoriteKey(it.book.sourceId, it.book.id) in favoriteKeys }
                val actions = listOf(Triple(Icons.Outlined.FavoriteBorder, if (followed) "取消喜欢" else "加入我喜欢的", { follow(work, !followed) }), Triple(Icons.Outlined.VisibilityOff, "不感兴趣", { block(work) }),
                    Triple(Icons.Outlined.Share, "分享", { share(work) }))
                actions.forEachIndexed { index, action ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { menuWork = null; action.third() }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(action.first, null, Modifier.size(20.dp), tint = if (index == 1) DiscoverRise else discoverInk())
                        Text(action.second, fontSize = 15.sp, color = if (index == 1) DiscoverRise else discoverInk())
                    }
                    if (index < actions.lastIndex) HorizontalDivider(thickness = .5.dp, color = discoverInk().copy(alpha = .12f))
                }
            }
        }
        if (showSettings) DiscoverSettings { showSettings = false }
        if (showSources) DiscoverSourcePicker(sources, enabled, sourceId, { id ->
            sourceId = id; nativeFeed = store.string("feed_$id", "native").ifBlank { null }
            nativeCategory = store.string("category_$id", "").ifBlank { null }; showSources = false
        }, { showSources = false }, { showSources = false; onSourceManagement() })
        if (expanded) DiscoverCategorySheet(state.entries.filter { e -> activeSources.any { it.id == e.book.sourceId } }, selected,
            activeSources.mapNotNull { s -> state.catalogs[s.id]?.let { s to it } },
            { store.put("categories", it); expanded = false }, { id, category ->
                sourceId = id; nativeFeed = null; nativeCategory = category; store.put("categories", emptySet()); expanded = false
            }, { expanded = false }, model::loadCategories)

    }
}

@Composable
internal fun DiscoverChip(label: String, selected: Boolean, onClick: () -> Unit, solid: Boolean = true,
    visualHeight: Int = 32, dimmed: Boolean = false, icon: ImageVector? = null, outlined: Boolean = false, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).alpha(if (dimmed) .5f else 1f)
        .selectable(selected, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, role = Role.Checkbox, onClick = onClick), contentAlignment = Alignment.Center) {
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

internal fun serialLabel(status: String?): String = when (status?.trim()?.lowercase(java.util.Locale.ROOT)) {
    "ongoing", "publishing", "serial", "连载", "連載", "连载中", "連載中", "1" -> "连载"
    "completed", "complete", "finished", "ended", "完结", "完結", "已完结", "已完結", "2" -> "完结"
    "hiatus", "cancelled", "停更", "休刊", "暂停更新", "3", "4" -> "停更"
    else -> "状态未知"
}
internal fun filterWorkStatus(works: List<DiscoverWork>, status: String): List<DiscoverWork> =
    if (status == "全部") works else works.mapNotNull { work ->
        work.variants.filter { serialLabel(it.book.comicInfo?.status) == status }.takeIf { it.isNotEmpty() }?.let { work.copy(variants = it) }
    }
internal fun workStatus(work: DiscoverWork): String {
    val statuses = work.variants.map { serialLabel(it.book.comicInfo?.status) }.toSet()
    return listOf("完结", "连载", "停更").firstOrNull { it in statuses } ?: "状态未知"
}
internal fun dateLabel(time: Long?): String = time?.let {
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(it)) } ?: "未提供"
