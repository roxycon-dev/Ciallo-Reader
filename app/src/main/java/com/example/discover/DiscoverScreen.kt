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
    onLatest: (SearchBook) -> Unit, onSourceManagement: () -> Unit, readingTitles: Set<String> = emptySet()) {
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
        it.name == store.string("last_dimension", "UPDATES") } ?: DiscoverDimension.UPDATES) }
    var range by rememberSaveable { mutableStateOf(store.string("range_${dimension.name}", dimension.ranges.first()).takeIf { it in dimension.ranges } ?: dimension.ranges.first()) }
    val layout = DiscoverLayout.entries.firstOrNull { it.name == store.string("layout", "GRID") } ?: DiscoverLayout.GRID
    val selected = store.selection("categories") - DiscoverTaxonomy.roots.map { it.id }.toSet()
    val blocked = store.selection("blocked")
    val showAdult = true
    var expanded by rememberSaveable { mutableStateOf(false) }
    var statusFilter by rememberSaveable { mutableStateOf("全部") }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var nativeFeed by rememberSaveable(sourceId) { mutableStateOf<String?>(store.string("feed_$sourceId", "native").ifBlank { null }) }
    var nativeCategory by rememberSaveable(sourceId) { mutableStateOf<String?>(store.string("category_$sourceId", "").ifBlank { null }) }
    var noticesExpanded by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<DiscoverWork?>(null) }
    var latestChoice by remember { mutableStateOf(false) }
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
    val scrolling by remember { derivedStateOf { grid.isScrollInProgress } }
    var movingDown by remember { mutableStateOf(false) }
    val scrollConnection = remember { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < -4) movingDown = true
            if (available.y > 4) movingDown = false
            return Offset.Zero
        }
    } }
    val today = DiscoverEngine.rangeStart("今日", System.currentTimeMillis()) ?: 0L
    val updatedFavorites = favorites.filter { it.favorite.latestChapterUpdateAt >= today && it.favorite.latestChapterId != null }
    val unreadFavorites = updatedFavorites.filter { it.favorite.latestChapterId != it.progress?.lastChapterId || it.progress?.isChapterFinished() != true }
    val rows = remember(state.entries, sourceId, dimension, selected, blocked, showAdult, statusFilter, unreadOnly, favorites, readingTitles, revision, enabled, sources, nativeFeed, nativeCategory) {
        val feed = if (unreadOnly) unreadFavorites.filter { (sourceId == "aggregate" || it.favorite.sourceId == sourceId) && enabled[it.favorite.sourceId] != false && sources.any { s -> s.id == it.favorite.sourceId } }.map {
            val f = it.favorite
            DiscoverEntry(SearchBook(f.comicId, f.sourceId, f.title, f.author, f.coverUrl, format = "漫画", comicId = f.comicId,
                comicInfo = ComicInfo(status = f.serialStatus)), updatedAt = f.latestChapterUpdateAt, latestChapter = f.latestChapterTitle)
        } else state.entries
        val merged = DiscoverEngine.merge(feed, dimension, sourceId == "aggregate").filter { work ->
            work.key !in blocked && work.variants.none { DiscoverEngine.identity(it) in blocked } &&
                (showAdult || !DiscoverTaxonomy.isAdult(work.tags)) && DiscoverTaxonomy.matches(work.tags, selected) &&
                (statusFilter == "全部" || work.variants.any { serialLabel(it.book.comicInfo?.status) == statusFilter }) &&
                (!unreadOnly || work.variants.any { e -> unreadFavorites.any { it.favorite.sourceId == e.book.sourceId && it.favorite.comicId == e.book.id } })
        }
        val favoriteAuthors = favorites.map { it.favorite.author }.filter { it.isKnownComicAuthor() }.toSet()
        if (store.flag("personalization") && nativeFeed == null && nativeCategory == null && !unreadOnly)
            DiscoverEngine.personalize(merged, dimension) { w ->
                val unread = w.variants.any { e -> unreadFavorites.any { it.favorite.sourceId == e.book.sourceId && it.favorite.comicId == e.book.id } }
                val knownAuthor = w.variants.any { it.book.author in favoriteAuthors }
                val read = w.variants.any { it.book.title in readingTitles }
                (if (unread) 3 else 0) + (if (knownAuthor) 1 else 0) - (if (read && !unread) 1 else 0)
            } else merged
    }
    LaunchedEffect(sources, enabled, sourceId, dimension, range, nativeFeed, nativeCategory, selected) {
        if (sourceId != "aggregate" && sources.none { it.id == sourceId }) { sourceId = "aggregate"; return@LaunchedEffect }
        model.configure(sourceId, dimension, range, nativeFeed, nativeCategory, selected)
        store.put("last_source", sourceId); store.put("last_dimension", dimension.name)
        store.put("range_${dimension.name}", range)
        store.put("feed_$sourceId", nativeFeed.orEmpty()); store.put("category_$sourceId", nativeCategory.orEmpty())
    }
    val nearEnd by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 && (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, state.loading, state.more) { if (nearEnd && !state.loading && state.more.isNotEmpty()) model.more() }
    val viewportKey = "$sourceId:${dimension.name}:$range:$nativeFeed:$nativeCategory:${selected.sorted()}"
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
    fun open(work: DiscoverWork, latest: Boolean = false) {
        if (work.variants.size > 1) { choice = work; latestChoice = latest }
        else if (latest) onLatest(work.book) else onOpen(work.book)
    }
    fun switch(next: DiscoverDimension) { light(); nativeFeed = null; nativeCategory = null; unreadOnly = false
        dimension = next; range = store.string("range_${next.name}", next.ranges.first()).takeIf { it in next.ranges } ?: next.ranges.first() }
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
    val availableDimensions = DiscoverDimension.entries.filter { d -> currentCatalogs.isEmpty() || currentCatalogs.any { it.ranges(d).isNotEmpty() } }
    val availableRanges = dimension.ranges.filter { r -> currentCatalogs.isEmpty() || currentCatalogs.any { r in it.ranges(dimension) } }
    LaunchedEffect(sourceId, availableDimensions, availableRanges, nativeFeed, nativeCategory) {
        if (nativeFeed == null && nativeCategory == null && (dimension !in availableDimensions || range !in availableRanges)) {
            if (availableRanges.isNotEmpty()) range = availableRanges.first() else nativeFeed = "native"
        }
    }
    val headerDistance = with(LocalDensity.current) { 150.dp.toPx() }
    val headerFraction = remember(grid, headerDistance) { { if (grid.firstVisibleItemIndex > 0) 1f else (grid.firstVisibleItemScrollOffset / headerDistance).coerceIn(0f, 1f) } }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("discover_screen").onGloballyPositioned { screenOffset = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize().nestedScroll(scrollConnection)) {
            CollapsingTabScreenHeader(fractionSource = headerFraction, modifier = Modifier.statusBarsPadding(), title = "发现", subtitle = "DISCOVER",
                onTitleClick = { scope.launch { if (reduce) grid.scrollToItem(0) else grid.animateScrollToItem(0) } },
                titleColor = glassTitleColor(), trailing = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Tune, "发现设置") }
                })
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { DiscoverChip("推荐", nativeFeed != null || nativeCategory != null, solid = false, onClick = {
                    light(); nativeFeed = "native"; nativeCategory = null; unreadOnly = false
                }) }
                items(availableDimensions) { d -> DiscoverChip(d.label, nativeFeed == null && nativeCategory == null && dimension == d,
                    solid = false, onClick = { switch(d) }) }
            }
            PullToRefreshBox(isRefreshing = state.loading && state.entries.isNotEmpty(), onRefresh = { light(); model.refresh() }, modifier = Modifier.weight(1f),
                indicator = { if (state.loading && state.entries.isNotEmpty()) Row(Modifier.align(Alignment.TopCenter).background(MaterialTheme.colorScheme.surface, CircleShape).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(androidx.compose.ui.res.painterResource(MascotSpriteSheet.runDrawable), "刷新指示器", Modifier.size(24.dp))
                    Text("正在刷新", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                } }) {
                var swipe by remember { mutableFloatStateOf(0f) }
                LazyVerticalGrid(columns = GridCells.Fixed(if (layout == DiscoverLayout.GRID && (dimension != DiscoverDimension.RANKING || nativeFeed != null || nativeCategory != null || selected.isNotEmpty())) 2 else 1), state = grid,
                    modifier = Modifier.fillMaxSize().testTag("discover_content").pointerInput(dimension, availableDimensions) {
                        detectHorizontalDragGestures(onDragStart = { swipe = 0f }, onHorizontalDrag = { _, amount -> swipe += amount }, onDragEnd = {
                            if (abs(swipe) > 80.dp.toPx()) { val index = (availableDimensions.indexOf(dimension) + if (swipe < 0) 1 else -1).coerceIn(0, (availableDimensions.size - 1).coerceAtLeast(0))
                                availableDimensions.getOrNull(index)?.let(::switch) }
                        })
                    }, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = LocalAppBottomInset.current + 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DiscoverChip(if (sourceId == "aggregate") "聚合书源" else sources.firstOrNull { it.id == sourceId }?.name ?: "选择书源",
                                false, solid = false, icon = Icons.Outlined.ExpandMore, modifier = Modifier.widthIn(max = 180.dp), onClick = { showSources = true })
                            Spacer(Modifier.weight(1f))
                            DiscoverChip("分类${if (selected.isNotEmpty()) " ${selected.size}" else ""}", selected.isNotEmpty(), solid = false,
                                icon = Icons.Outlined.FilterList, onClick = { expanded = true })
                            IconButton(onClick = { light(); model.refresh() }, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, "刷新发现", Modifier.size(20.dp)) }
                        }
                        if (nativeFeed == null && nativeCategory == null && availableRanges.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(availableRanges) { r -> DiscoverChip(r, range == r, solid = false, visualHeight = 28, onClick = { light(); range = r }) }
                        }
                        if (selected.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(DiscoverTaxonomy.all.filter { it.id in selected }.joinToString(" · ") { it.label }, Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { store.put("categories", emptySet()) }) { Text("清除") }
                        }
                        }
                    }
                    if (updatedFavorites.isNotEmpty() || unreadOnly) item(span = { GridItemSpan(maxLineSpan) }) {
                        DiscoverGlass(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { unreadOnly = !unreadOnly; if (unreadOnly) { nativeFeed = null; nativeCategory = null; dimension = DiscoverDimension.UPDATES; range = "今日" }
                                scope.launch { grid.scrollToItem(0) } }, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(if (unreadOnly) "正在看未读更新 · 查看全部" else "你追的作品今日更新 ${updatedFavorites.size} 本", Modifier.weight(1f), fontSize = 13.sp, color = discoverInk())
                                Icon(Icons.Outlined.ChevronRight, "直达未读", Modifier.size(16.dp), tint = discoverInk())
                            }
                        }
                    }
                    if (dimension == DiscoverDimension.UPDATES && nativeFeed == null && nativeCategory == null) item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(listOf("全部", "连载", "完结")) { status ->
                            DiscoverChip(status, statusFilter == status, solid = false, visualHeight = 28, onClick = { light(); statusFilter = status }) } }
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
                            DiscoverEmptyState(if (unreadOnly) MascotSpriteSheet.readingDrawable else if (selected.isNotEmpty()) MascotSpriteSheet.sadDrawable else MascotSpriteSheet.idleDrawable,
                                if (unreadOnly) "暂时没有未读更新" else if (selected.isNotEmpty()) "换个分类，遇见新故事" else "这个书源暂时没有数据",
                                if (selected.isNotEmpty()) "试试其他分类，或清除筛选继续浏览" else "下拉刷新，或换个书源继续发现", actionLabel = if (selected.isNotEmpty() || unreadOnly) "清除筛选" else "原站推荐",
                                onActionClick = { if (selected.isNotEmpty() || unreadOnly) { store.put("categories", emptySet()); unreadOnly = false; statusFilter = "全部" }
                                    else { nativeFeed = "native"; nativeCategory = null } })
                        }
                    }
                    itemsIndexed(rows, key = { _, w -> w.key }) { index, work ->
                        DiscoverCard(work, dimension, if (dimension == DiscoverDimension.RANKING && nativeFeed == null && nativeCategory == null && selected.isEmpty()) DiscoverLayout.LIST else layout,
                            rank = if (dimension == DiscoverDimension.RANKING && nativeFeed == null && nativeCategory == null && selected.isEmpty()) { if (sourceId == "aggregate") index + 1 else work.variants.first().rank ?: index + 1 } else null,
                            followed = work.variants.any { favoriteKey(it.book.sourceId, it.book.id) in favoriteKeys },
                            unread = work.variants.any { e -> unreadFavorites.any { it.favorite.sourceId == e.book.sourceId && it.favorite.comicId == e.book.id } },
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
        AnimatedVisibility(!movingDown || !scrolling, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = LocalAppBottomInset.current + 16.dp),
            enter = fadeIn(tween(if (reduce) 0 else 120)), exit = fadeOut(tween(if (reduce) 0 else 120))) {
            val primary = MaterialTheme.colorScheme.primary
            Surface(onClick = { rows.randomOrNull()?.let { light(); open(it, true) } }, enabled = rows.isNotEmpty(),
                modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 52.dp)
                    .shadow(16.dp, CircleShape, ambientColor = primary.copy(alpha = .30f), spotColor = primary.copy(alpha = .30f)),
                shape = CircleShape, color = if (rows.isEmpty()) primary.copy(alpha = .5f) else primary,
                contentColor = contrastInk(primary)) {
            Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Casino, "随机来一本", Modifier.size(20.dp), tint = contrastInk(primary))
                if (!scrolling) { Spacer(Modifier.width(8.dp)); Text("随机来一本", fontSize = 13.sp, color = contrastInk(primary)) }
            }
            }
        }
        choice?.let { work ->
            ModalBottomSheet(onDismissRequest = { choice = null }) {
                Column(Modifier.padding(16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(work.book.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("${work.variants.size} 个源可读 · 选择阅读书源", fontSize = 13.sp)
                    work.variants.forEach { entry ->
                        DiscoverGlass(Modifier.fillMaxWidth().clickable { choice = null; if (latestChoice) onLatest(entry.book) else onOpen(entry.book) }) {
                            Text(sources.firstOrNull { it.id == entry.book.sourceId }?.name ?: entry.book.sourceId, fontWeight = FontWeight.SemiBold)
                            Text("更新 ${dateLabel(entry.updatedAt)} · 讨论 ${DiscoverEngine.compact(entry.discussions)} · 点击 ${DiscoverEngine.compact(entry.clicks)} · 名次 ${entry.rank ?: "未提供"}", fontSize = 12.sp)
                            Text("${entry.latestChapter ?: "最新话未提供"} · ${serialLabel(entry.book.comicInfo?.status)}", fontSize = 11.sp)
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
            val menuHeight = 196.dp
            val maxMenuY = (maxHeight - LocalAppBottomInset.current - menuHeight - 16.dp).coerceAtLeast(16.dp)
            val x = left.coerceIn(16.dp, (maxWidth - 216.dp).coerceAtLeast(16.dp))
            val y = (if (top + menuHeight > maxHeight - LocalAppBottomInset.current - 16.dp) top - menuHeight - 8.dp else top + 16.dp).coerceIn(16.dp, maxMenuY)
            DiscoverGlass(Modifier.offset(x = x, y = y).width(200.dp).semantics { paneTitle = "作品快捷菜单" }, RoundedCornerShape(20.dp), PaddingValues(0.dp)) {
                val followed = work.variants.any { favoriteKey(it.book.sourceId, it.book.id) in favoriteKeys }
                val actions = listOf(Triple(Icons.Outlined.BookmarkAdd, if (followed) "已追 · 取消追漫" else "加入书架", { follow(work, !followed) }),
                    Triple(Icons.Outlined.MenuBook, "读最新话", { open(work, true) }), Triple(Icons.Outlined.VisibilityOff, "不感兴趣", { block(work) }),
                    Triple(Icons.Outlined.Share, "分享", { share(work) }))
                actions.forEachIndexed { index, action ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { menuWork = null; action.third() }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(action.first, null, Modifier.size(20.dp), tint = if (index == 2) DiscoverRise else discoverInk())
                        Text(action.second, fontSize = 15.sp, color = if (index == 2) DiscoverRise else discoverInk())
                    }
                    if (index < 3) HorizontalDivider(thickness = .5.dp, color = discoverInk().copy(alpha = .12f))
                }
            }
        }
        if (showSettings) DiscoverSettings { showSettings = false }
        if (showSources) DiscoverSourcePicker(sources, enabled, sourceId, { id ->
            sourceId = id; nativeFeed = store.string("feed_$id", "native").ifBlank { null }
            nativeCategory = store.string("category_$id", "").ifBlank { null }; unreadOnly = false; showSources = false
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
        .selectable(selected, role = Role.Checkbox, onClick = onClick), contentAlignment = Alignment.Center) {
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

internal fun serialLabel(status: String?): String = when (status?.lowercase()) {
    "ongoing", "连载", "连载中", "連載中" -> "连载"
    "completed", "finished", "完结", "已完结", "已完結" -> "完结"
    "hiatus", "cancelled", "停更" -> "停更"
    else -> "状态未提供"
}
internal fun dateLabel(time: Long?): String = time?.let {
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(it)) } ?: "未提供"
