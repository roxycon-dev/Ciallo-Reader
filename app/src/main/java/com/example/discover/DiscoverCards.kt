package com.example.discover

import com.example.ui.components.pullOutCombinedClickable
import com.example.ui.components.pullOutClickable
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
import com.example.source.isKnownComicAuthor
import com.example.ui.feedback.*
import kotlinx.coroutines.delay

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
    val primary = MaterialTheme.colorScheme.primary
    val sourceCount = work.variants.map { it.book.sourceId }.distinct().size
    val source = if (sourceCount > 1) "$sourceCount 个源可读" else sourceLabel(work.book.sourceId)
    val medal = rank?.takeIf { it in 1..3 }?.let { DiscoverMedals[it - 1] }
    val shape = RoundedCornerShape(if (layout == DiscoverLayout.LARGE) 24.dp else if (layout == DiscoverLayout.GRID) 16.dp else 20.dp)
    Box(Modifier.fillMaxWidth().alpha(alpha).graphicsLayer { scaleX = scale; scaleY = scale }
        .onGloballyPositioned { bounds = it.boundsInRoot() }) {
        val cardModifier = Modifier.fillMaxWidth()
            .then(if (rank == 1 && medal != null) Modifier.shadow(8.dp, shape, ambientColor = medal.copy(alpha = .16f), spotColor = medal.copy(alpha = .24f)) else Modifier)
            .then(if (medal != null) Modifier.border(if (rank == 1) 1.5.dp else 1.dp, Brush.linearGradient(listOf(medal.copy(alpha = .40f), medal, medal.copy(alpha = .56f))), shape) else Modifier)
            .clip(shape).background(MaterialTheme.colorScheme.surface.copy(alpha = .88f))
            .pullOutCombinedClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = { onMenu(bounds); haptics.perform(HapticKind.MEDIUM) })
            // Consume a horizontal drag after touch slop so releasing it cannot open a work.
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume() } }
        Column(cardModifier) {
            when (layout) {
                DiscoverLayout.LIST -> {
                    val largeText = LocalDensity.current.fontScale > 1.15f
                    val displayRank = rank ?: work.variants.firstNotNullOfOrNull { it.rank?.takeIf { _ -> it.native } }
                    Row(Modifier.fillMaxWidth().height(160.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.width(120.dp).fillMaxHeight()) {
                            Cover(work.book, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)), work.updatedAt, false, null, null, coverSource)
                            if (displayRank != null) SourceBadge("No.$displayRank", Modifier.align(Alignment.BottomCenter).padding(4.dp))
                        }
                        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(work.book.title, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                                FollowButton(followed, false) { onFollow(!followed); haptics.perform(HapticKind.SUCCESS) }
                            }
                            if (!largeText && work.book.author.isKnownComicAuthor()) Text(work.book.author, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            work.variants.firstNotNullOfOrNull { it.latestChapter?.takeIf(String::isNotBlank) }?.let { chapter ->
                                Text(chapter, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, color = discoverAccentText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            CardDataLine(work, dimension)
                            TagRow(work.tags, workStatus(work).takeUnless { it == "状态未知" })
                            if (!largeText) Text(source, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f))
                        }
                    }
                }
                DiscoverLayout.GRID -> {
                    Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                        Cover(work.book, Modifier.fillMaxSize(), work.updatedAt, unread, null, null, coverSource)
                        SourceBadge(source, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = if ((work.updatedAt ?: 0) >= (DiscoverEngine.rangeStart("今日", System.currentTimeMillis()) ?: Long.MAX_VALUE)) 32.dp else 8.dp))
                        FollowButton(followed, true, Modifier.align(Alignment.TopEnd)) { onFollow(!followed); haptics.perform(HapticKind.SUCCESS) }
                        Box(Modifier.fillMaxWidth().fillMaxHeight(.45f).align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))))
                        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                            Text(work.book.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (work.book.author.isNotBlank()) Text(work.book.author, fontSize = 11.sp, color = Color.White.copy(alpha = .7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            Text(listOfNotNull(work.book.author.takeIf { it.isNotBlank() }, work.book.comicInfo?.status?.let(::serialLabel)).joinToString(" · "), Modifier.weight(1f), fontSize = 13.sp, color = discoverInk())
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
    var headersReady by remember(book.sourceId, book.cover) { mutableStateOf(book.cover.isNullOrBlank()) }
    LaunchedEffect(book.sourceId, book.cover) {
        val url = book.cover ?: return@LaunchedEffect
        try { headers = source(book.sourceId)?.getCoverHeaders(url).orEmpty() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { }
        finally { headersReady = true }
    }
    val request = remember(book.cover, headers) { coil.request.ImageRequest.Builder(context).data(book.cover)
        .apply { headers.forEach { (key, value) -> addHeader(key, value) } }.build() }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (headersReady) AsyncImage(request, book.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
        color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
@Composable
private fun FollowButton(followed: Boolean, compact: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Foundation expands the small visual icon's touch target without stretching the title row.
    Box(modifier.size(if (compact) 48.dp else 28.dp).pullOutClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(if (followed) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            if (followed) "取消喜欢" else "加入我喜欢的", Modifier.size(20.dp),
            tint = if (followed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun TagRow(tags: List<String>, status: String? = null) {
    val labels = (listOfNotNull(status) + DiscoverTaxonomy.labels(tags)).distinct()
    if (labels.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (labels.take(3) + if (labels.size > 3) listOf("+${labels.size - 3}") else emptyList()).forEach { tag ->
            Text(tag, Modifier.weight(1f, fill = false).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
internal fun cardCompact(value: Long): String = when {
    value >= 100_000_000 -> String.format(java.util.Locale.ROOT, "%.1fy", value / 100_000_000.0)
    value >= 10_000 -> String.format(java.util.Locale.ROOT, "%.1fw", value / 10_000.0)
    value >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fk", value / 1_000.0)
    else -> value.toString()
}
@Composable
private fun CardDataLine(work: DiscoverWork, dimension: DiscoverDimension) {
    val entries = work.variants
    val count = when (dimension) {
        DiscoverDimension.DISCUSSION -> entries.mapNotNull { it.discussions }.takeIf { it.isNotEmpty() }?.sum()
        DiscoverDimension.CLICKS -> entries.mapNotNull { it.clicks }.takeIf { it.isNotEmpty() }?.sum()
        DiscoverDimension.RANKING -> entries.mapNotNull { it.clicks }.takeIf { it.isNotEmpty() }?.sum()
        else -> entries.mapNotNull { it.clicks }.takeIf { it.isNotEmpty() }?.sum()
    }?.takeIf { it > 0 }
    val statistic = entries.firstNotNullOfOrNull { it.statistic?.takeIf(String::isNotBlank) }
    if (work.updatedAt == null && count == null && statistic == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        work.updatedAt?.let { Text(dateLabel(it).substringBefore(' '), fontSize = 12.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        if (count != null || statistic != null) {
            Icon(when (dimension) { DiscoverDimension.DISCUSSION -> Icons.Outlined.ChatBubbleOutline
                DiscoverDimension.RANKING -> if (count != null) Icons.Outlined.Visibility else Icons.Outlined.FavoriteBorder
                else -> Icons.Outlined.Visibility }, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(count?.let(::cardCompact) ?: statistic.orEmpty().substringAfter(' ').replace("万", "w"),
                Modifier.weight(1f, fill = false), fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun metric(work: DiscoverWork, dimension: DiscoverDimension): String {
    val entries = work.variants
    val actual = when (dimension) {
        DiscoverDimension.UPDATES -> entries.maxByOrNull { it.updatedAt ?: 0 }?.let {
            listOfNotNull(it.latestChapter, it.updatedAt?.let(::dateLabel)).joinToString(" · ")
        }.orEmpty()
        DiscoverDimension.DISCUSSION -> entries.mapNotNull { it.discussions }.takeIf { it.isNotEmpty() }?.let { "讨论 ${DiscoverEngine.compact(it.sum())}" }.orEmpty()
        DiscoverDimension.CLICKS -> entries.mapNotNull { it.clicks }.takeIf { it.isNotEmpty() }?.let { "阅读 ${DiscoverEngine.compact(it.sum())}" }.orEmpty()
        DiscoverDimension.RANKING -> entries.mapNotNull { it.rank }.minOrNull()?.let { "源内 #$it" }.orEmpty()
    }
    val origins = (entries.mapNotNull { it.origin } + entries.mapNotNull { it.statistic }).distinct().joinToString(" · ")
    if (actual.isNotBlank()) return listOf(actual, origins).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    val entry = entries.first()
    val nativeMetric = listOfNotNull(entry.latestChapter, entry.clicks?.let { "阅读 ${DiscoverEngine.compact(it)}" }, entry.discussions?.let { "讨论 ${DiscoverEngine.compact(it)}" }).joinToString(" · ")
    return listOf(origins, nativeMetric.ifBlank { entry.nativeOrder?.let { "原站顺序 #$it" }.orEmpty() }).filter { it.isNotBlank() }.joinToString(" · ")
}
@Composable
private fun Trend(work: DiscoverWork, dimension: DiscoverDimension, reverse: Boolean, idleColor: Color? = null) {
    val entry = work.variants.first()
    val value = if (dimension == DiscoverDimension.RANKING) entry.rankChange?.toDouble() else entry.growth
    if (value == null && !entry.newEntry) return
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
    Column(Modifier.fillMaxWidth().padding(10.dp)) {
        if (layout == DiscoverLayout.LIST) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(72.dp, 96.dp).clip(RoundedCornerShape(12.dp)).background(discoverInk().copy(alpha = alpha * .12f)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { Box(Modifier.fillMaxWidth().height(16.dp).background(discoverInk().copy(alpha = alpha * .12f), CircleShape)) } }
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(if (layout == DiscoverLayout.GRID) 3f / 4f else 4f / 3f).background(discoverInk().copy(alpha = alpha * .12f), RoundedCornerShape(16.dp)))
            Spacer(Modifier.height(8.dp)); Box(Modifier.fillMaxWidth().height(16.dp).background(discoverInk().copy(alpha = alpha * .12f), CircleShape))
        }
    }
}
