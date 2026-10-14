package com.example.discover

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.example.source.SearchBook
import com.example.ui.feedback.*
import kotlinx.coroutines.delay

@Composable
internal fun DiscoverCategories(entries: List<DiscoverEntry>, selected: Set<String>, adult: Boolean,
    rootId: String, branchId: String, onRoot: (String) -> Unit, onBranch: (String) -> Unit, onSelection: (Set<String>) -> Unit) {
    val works = remember(entries) { DiscoverEngine.merge(entries, DiscoverDimension.UPDATES) }
    val roots = DiscoverTaxonomy.roots.filter { adult || !it.adult }
    val root = roots.firstOrNull { it.id == rootId } ?: roots.first()
    val branch = root.children.firstOrNull { it.id == branchId }
    val today = DiscoverEngine.rangeStart("今日", System.currentTimeMillis()) ?: 0L
    val classified = remember(works, adult) { works.filter { adult || !DiscoverTaxonomy.isAdult(it.tags) }
        .map { it to DiscoverTaxonomy.classify(it.tags) } }
    val counts = remember(classified) { classified.flatMap { it.second }.groupingBy { it }.eachCount() }
    val updates = remember(classified, today) { classified.filter { (it.first.updatedAt ?: 0) >= today }.flatMap { it.second }.toSet() }
    fun count(c: DiscoverCategory) = counts[c.id] ?: 0
    fun updated(c: DiscoverCategory) = c.id in updates
    fun toggle(c: DiscoverCategory) = onSelection(if (c.id in selected) selected - c.id else selected + c.id)
    DiscoverGlass(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), PaddingValues(8.dp)) {
        Text("选你喜欢的故事", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = discoverInk())
        Text("数量为已加载作品 · ${classified.count { it.second.isEmpty() }} 本标签未映射；0 不代表书源不支持", fontSize = 11.sp, color = discoverInk().copy(alpha = .64f))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { DiscoverChip("全部", selected.isEmpty(), onClick = { onSelection(emptySet()) }) }
            items(roots) { c -> DiscoverChip("${c.label} ${count(c)}${if (updated(c)) " · 今" else ""}", c.id in selected,
                onClick = { onRoot(c.id); toggle(c) }) }
        }
        HorizontalDivider(color = discoverInk().copy(alpha = .12f))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { DiscoverChip("全部${root.label}", root.id in selected, solid = false, visualHeight = 28,
                onClick = { onSelection(selected - root.leaves + root.id) }) }
            items(root.children) { c -> DiscoverChip("${c.label} ${count(c)}${if (updated(c)) " · 今" else ""}", c.id in selected,
                solid = false, visualHeight = 28, outlined = c.id in selected, onClick = { onBranch(c.id); toggleSelection(selected - root.id, c.id, onSelection) }) }
        }
        branch?.let { b ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { DiscoverChip("全部${b.label}", b.id in selected, solid = false, visualHeight = 24,
                    onClick = { onSelection(selected - b.leaves - root.id + b.id) }) }
                items(b.children) { c ->
                        DiscoverChip("${c.label} ${count(c)}${if (updated(c)) " · 今" else ""}", c.id in selected, solid = false, visualHeight = 24,
                            outlined = true, onClick = { toggleSelection(selected - root.id - b.id, c.id, onSelection) })
                }
            }
        }
        if (selected.isNotEmpty()) TextButton(onClick = { onSelection(emptySet()) }, Modifier.heightIn(min = 48.dp)) {
            Text("已选 ${selected.size} 项 · 清除", fontSize = 12.sp)
        }
    }
}
private fun toggleSelection(selected: Set<String>, id: String, change: (Set<String>) -> Unit) = change(if (id in selected) selected - id else selected + id)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DiscoverCard(work: DiscoverWork, dimension: DiscoverDimension, layout: DiscoverLayout, rank: Int?,
    followed: Boolean, unread: Boolean, reverseColors: Boolean, onClick: () -> Unit,
    onFollow: (Boolean) -> Unit, onBlock: () -> Unit, onMenu: (Rect) -> Unit, menuActive: Boolean,
    sourceLabel: (String) -> String, entryIndex: Int,
    coverSource: (String) -> com.example.source.ComicSource? = { null }) {
    val reduce = LocalReduceMotion.current
    val haptics = rememberAppHaptics()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var entered by remember { mutableStateOf(reduce || entryIndex >= 6) }
    LaunchedEffect(Unit) { if (!entered) { delay(entryIndex * 30L); entered = true } }
    val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(if (reduce) 0 else 160), label = "discoverEnter")
    val scale by animateFloatAsState(if (menuActive) .97f else 1f, tween(if (reduce) 0 else 120), label = "discoverPress")
    var drag by remember { mutableFloatStateOf(0f) }
    val primary = MaterialTheme.colorScheme.primary
    val source = if (work.variants.size > 1) "${work.variants.size} 个源可读" else sourceLabel(work.book.sourceId)
    val medal = rank?.takeIf { it in 1..3 }?.let { DiscoverMedals[it - 1] }
    val shape = RoundedCornerShape(if (layout == DiscoverLayout.LARGE) 24.dp else if (layout == DiscoverLayout.GRID) 16.dp else 20.dp)
    Box(Modifier.fillMaxWidth().alpha(alpha).graphicsLayer { scaleX = scale; scaleY = scale; translationX = drag.coerceIn(-48f, 48f) }
        .onGloballyPositioned { bounds = it.boundsInRoot() }) {
        val cardModifier = Modifier.fillMaxWidth()
            .then(if (rank == 1 && medal != null) Modifier.shadow(8.dp, shape, ambientColor = medal.copy(alpha = .16f), spotColor = medal.copy(alpha = .24f)) else Modifier)
            .then(if (medal != null) Modifier.border(if (rank == 1) 1.5.dp else 1.dp, Brush.linearGradient(listOf(medal.copy(alpha = .40f), medal, medal.copy(alpha = .56f))), shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = { onMenu(bounds); haptics.perform(HapticKind.MEDIUM) })
            .pointerInput(work.key, followed) {
                detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { change, amount -> change.consume(); drag += amount },
                    onDragCancel = { drag = 0f }, onDragEnd = {
                        if (drag > 64.dp.toPx()) { onFollow(true); haptics.perform(HapticKind.SUCCESS) }
                        else if (drag < -64.dp.toPx()) onBlock()
                        drag = 0f
                    })
            }
        DiscoverGlass(cardModifier, shape, PaddingValues(if (layout == DiscoverLayout.LIST) 10.dp else 0.dp)) {
            when (layout) {
                DiscoverLayout.LIST -> Row(Modifier.fillMaxWidth().heightIn(min = if (rank == 1) 116.dp else 92.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rank != null && rank > 3) Text(rank.toString(), Modifier.width(28.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = discoverInk().copy(alpha = .64f))
                    Cover(work.book, Modifier.size(if (rank == 1) 88.dp else 72.dp, if (rank == 1) 118.dp else 96.dp).clip(RoundedCornerShape(12.dp)),
                        work.updatedAt, unread, rank, medal, coverSource)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(work.book.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = discoverInk().copy(alpha = .92f))
                        Text(work.book.author.ifBlank { "作者未提供" }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = discoverInk().copy(alpha = .64f))
                        Text(metric(work, dimension), fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = discoverInk(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TagRow(work.tags)
                        if (dimension == DiscoverDimension.DISCUSSION && work.variants.first().hotComment != null)
                            Text("热评：${work.variants.first().hotComment}", fontSize = 11.sp, color = discoverInk(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(source, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = discoverInk().copy(alpha = .64f))
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Trend(work, dimension, reverseColors)
                        Text(serialLabel(work.book.comicInfo?.status), fontSize = 10.sp, color = discoverInk().copy(alpha = .64f))
                        FollowButton(followed, false) { onFollow(!followed); haptics.perform(HapticKind.SUCCESS) }
                    }
                }
                DiscoverLayout.GRID -> {
                    Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                        Cover(work.book, Modifier.fillMaxSize(), work.updatedAt, unread, null, null, coverSource)
                        SourceBadge(source, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = if ((work.updatedAt ?: 0) >= (DiscoverEngine.rangeStart("今日", System.currentTimeMillis()) ?: Long.MAX_VALUE)) 32.dp else 8.dp))
                        FollowButton(followed, true, Modifier.align(Alignment.TopEnd)) { onFollow(!followed); haptics.perform(HapticKind.SUCCESS) }
                        SourceBadge(serialLabel(work.book.comicInfo?.status), Modifier.align(Alignment.BottomStart).padding(8.dp))
                    }
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(work.book.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, color = discoverInk())
                        Text(work.book.author.ifBlank { "作者未提供" }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = discoverInk().copy(alpha = .64f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(metric(work, dimension), Modifier.weight(1f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = discoverInk(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (dimension != DiscoverDimension.UPDATES) Trend(work, dimension, reverseColors)
                        }
                        TagRow(work.tags.take(2))
                    }
                }
                DiscoverLayout.LARGE -> {
                    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
                        Cover(work.book, Modifier.fillMaxSize(), work.updatedAt, unread, null, null, coverSource)
                        Box(Modifier.fillMaxWidth().fillMaxHeight(.50f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .90f)))))
                        SourceBadge(source, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 32.dp))
                        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                            Text(work.book.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(metric(work, dimension), fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                                if (dimension != DiscoverDimension.UPDATES) Trend(work, dimension, reverseColors, Color.White)
                            }
                        }
                    }
                    Column(Modifier.padding(10.dp)) {
                        TagRow(work.tags)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${work.book.author.ifBlank { "作者未提供" }} · ${serialLabel(work.book.comicInfo?.status)}", Modifier.weight(1f), fontSize = 13.sp, color = discoverInk())
                            FollowButton(followed, false) { onFollow(!followed); haptics.perform(HapticKind.SUCCESS) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cover(book: SearchBook, modifier: Modifier, updatedAt: Long?, unread: Boolean, rank: Int?, medal: Color?, source: (String) -> com.example.source.ComicSource?) {
    val context = LocalContext.current
    var headers by remember(book.sourceId, book.cover) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(book.sourceId, book.cover) {
        val url = book.cover ?: return@LaunchedEffect
        try { headers = source(book.sourceId)?.getCoverHeaders(url).orEmpty() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { }
    }
    val request = remember(book.cover, headers) { coil.request.ImageRequest.Builder(context).data(book.cover)
        .apply { headers.forEach { (key, value) -> addHeader(key, value) } }.build() }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(request, book.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (book.cover.isNullOrBlank()) Icon(Icons.Outlined.MenuBook, null, Modifier.align(Alignment.Center).size(32.dp), tint = MaterialTheme.colorScheme.primary)
        if ((updatedAt ?: 0) >= (DiscoverEngine.rangeStart("今日", System.currentTimeMillis()) ?: Long.MAX_VALUE))
            Box(Modifier.align(Alignment.TopStart).padding(4.dp).size(16.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text("今", fontSize = 10.sp, color = contrastInk(MaterialTheme.colorScheme.primary), fontWeight = FontWeight.Medium)
            }
        if (unread) SourceBadge("● 未读", Modifier.align(Alignment.BottomEnd).padding(4.dp))
        if (rank != null && medal != null) Box(Modifier.align(Alignment.TopStart).size(28.dp).background(Brush.linearGradient(listOf(medal, medal.copy(alpha = .64f))), CircleShape), contentAlignment = Alignment.Center) {
            Text(rank.toString(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = contrastInk(medal))
        }
    }
}
@Composable
private fun SourceBadge(label: String, modifier: Modifier) {
    Text(label, modifier.widthIn(max = 112.dp).background(Color.Black.copy(alpha = .72f), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
@Composable
private fun FollowButton(followed: Boolean, compact: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val reduce = LocalReduceMotion.current
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(followed) { if (followed && !reduce) { pulse = true; delay(90); pulse = false } }
    val scale by animateFloatAsState(if (pulse) 1.15f else 1f, tween(if (reduce) 0 else 90), label = "follow")
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Row(Modifier.scale(scale).heightIn(min = 32.dp).widthIn(min = 32.dp).background(if (followed) discoverTint() else MaterialTheme.colorScheme.surface.copy(alpha = .88f), CircleShape)
            .padding(horizontal = if (compact) 6.dp else 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(if (followed) Icons.Outlined.Check else Icons.Outlined.Add, if (followed) "已追，取消追漫" else "加入书架", Modifier.size(20.dp),
                tint = if (followed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (!compact && followed) Text("已追", fontSize = 11.sp, color = discoverAccentText())
        }
    }
}
@Composable
private fun TagRow(tags: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.filter(DiscoverTaxonomy::isStoryTag).take(3).forEach { tag -> Text(tag.substringAfter('：').substringAfter(':'), Modifier.weight(1f, fill = false).background(discoverTint(), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 11.sp, color = discoverInk().copy(alpha = .64f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
internal fun metric(work: DiscoverWork, dimension: DiscoverDimension): String = if (work.variants.first().native)
    listOfNotNull(work.variants.first().origin, work.variants.first().subtitle).joinToString(" · ").ifBlank { "原站推荐" }
else when (dimension) {
    DiscoverDimension.UPDATES -> work.variants.maxByOrNull { it.updatedAt ?: 0 }?.let { "${it.latestChapter ?: "最新话未提供"} · ${dateLabel(it.updatedAt)}" } ?: "更新未提供"
    DiscoverDimension.DISCUSSION -> "讨论 ${DiscoverEngine.compact(work.variants.mapNotNull { it.discussions }.takeIf { it.isNotEmpty() }?.sum())}"
    DiscoverDimension.CLICKS -> "点击 ${DiscoverEngine.compact(work.variants.mapNotNull { it.clicks }.takeIf { it.isNotEmpty() }?.sum())}"
    DiscoverDimension.RANKING -> "源内 #${work.variants.mapNotNull { it.rank }.minOrNull() ?: "—"}"
}
@Composable
private fun Trend(work: DiscoverWork, dimension: DiscoverDimension, reverse: Boolean, idleColor: Color? = null) {
    val entry = work.variants.first()
    val value = if (dimension == DiscoverDimension.RANKING) entry.rankChange?.toDouble() else entry.growth
    val label = when { entry.newEntry && dimension == DiscoverDimension.RANKING -> "NEW"
        value == null || value == 0.0 -> "—"
        dimension == DiscoverDimension.RANKING -> "${if (value > 0) "↑" else "↓"}${kotlin.math.abs(value).toInt()}"
        else -> "${if (value > 0) "▲" else "▼"}${String.format(java.util.Locale.CHINA, "%.1f", kotlin.math.abs(value))}%" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (value != null && value >= 100 && dimension != DiscoverDimension.RANKING)
            Icon(Icons.Outlined.LocalFireDepartment, "飙升", Modifier.size(12.dp), tint = if (reverse) DiscoverFall else DiscoverRise)
        Text(label, if (label == "NEW") Modifier.background(discoverTint(), CircleShape).padding(horizontal = 4.dp, vertical = 2.dp) else Modifier,
            fontSize = if (label == "NEW") 10.sp else 11.sp, fontFamily = FontFamily.Monospace,
            color = if (label == "NEW") discoverAccentText() else if (value == null || value == 0.0) idleColor ?: discoverInk().copy(alpha = .64f)
            else if ((value > 0) != reverse) DiscoverRise else DiscoverFall)
    }
}
@Composable
internal fun DiscoverSkeleton(layout: DiscoverLayout) {
    val reduce = LocalReduceMotion.current
    val alpha = if (reduce) .6f else {
        val animation = rememberInfiniteTransition(label = "discoverSkeleton")
        animation.animateFloat(.4f, .8f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "discoverSkeletonAlpha").value
    }
    DiscoverGlass(Modifier.fillMaxWidth(), padding = PaddingValues(10.dp)) {
        if (layout == DiscoverLayout.LIST) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(72.dp, 96.dp).clip(RoundedCornerShape(12.dp)).background(discoverInk().copy(alpha = alpha * .12f)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { Box(Modifier.fillMaxWidth().height(16.dp).background(discoverInk().copy(alpha = alpha * .12f), CircleShape)) } }
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(if (layout == DiscoverLayout.GRID) 3f / 4f else 4f / 3f).background(discoverInk().copy(alpha = alpha * .12f), RoundedCornerShape(16.dp)))
            Spacer(Modifier.height(8.dp)); Box(Modifier.fillMaxWidth().height(16.dp).background(discoverInk().copy(alpha = alpha * .12f), CircleShape))
        }
    }
}
