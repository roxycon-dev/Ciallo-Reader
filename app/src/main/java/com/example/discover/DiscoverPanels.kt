package com.example.discover

import com.example.i18n.tr

import com.example.ui.components.PullOutTextButton
import com.example.ui.components.PullOutIconButton
import com.example.ui.components.pullOutClickable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.source.BookSource
import com.example.ui.components.*

/** Source diagnostics have their own bounded viewport, independent of the result grid. */
@Composable
internal fun DiscoverSourceStatusSheet(visible: Boolean, sources: List<BookSource>, rows: List<DiscoverWork>, state: DiscoverState,
    status: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    // 6.2：第一块从 AcrylicBottomOverlay（Dialog + 实时模糊）换到 WenkuSheetHost 的面板。
    // 宿主自己负责遮罩/滑入/拖拽关闭，调用方只需保持它常驻并翻转 visible 才能看到退场。
    com.example.ui.wenku.WenkuSheetHost(
        visible = visible,
        anchorBounds = null,
        onDismiss = onDismiss
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("书源加载详情"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                PullOutIconButton(icon = Icons.Outlined.Close, contentDescription = tr("关闭来源详情"), onClick = com.example.ui.components.paperDismiss {})
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("discover_source_status_list"), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(sources, key = { it.id }) { source ->
                    val count = rows.count { work -> work.variants.any { it.book.sourceId == source.id } }
                    val message = state.errors[source.id] ?: if (count > 0) com.example.i18n.filterCount(count)
                        else state.notices[source.id] ?: if (state.loading) tr("加载中")
                        else if (status != "全部" && state.entries.any { it.book.sourceId == source.id && serialLabel(it.book.comicInfo?.status) == "状态未知" })
                            com.example.i18n.tr("作品状态尚未提供或待补全") else tr("当前筛选无结果")
                    Column {
                        Text(source.name, style = MaterialTheme.typography.titleSmall)
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.errors.isNotEmpty()) PullOutTextButton(onClick = onRetry,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text(tr("重试失败书源")) }
        }
    }
}

@Composable
internal fun DiscoverSourcePicker(sources: List<BookSource>, enabled: Map<String, Boolean>, selected: String,
    onSelect: (String) -> Unit, onDismiss: () -> Unit, onManage: () -> Unit) {
    AcrylicBottomOverlay(onDismissRequest = onDismiss) {
        val close = LocalOverlayClose.current
        val closeThenSelect: (String) -> Unit = { id -> close { onSelect(id) } }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("选择书源"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            PullOutIconButton(icon = Icons.Outlined.Close, contentDescription = tr("关闭书源窗口"), onClick = com.example.ui.components.paperDismiss(onDismiss))
        }
        LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .6f).dp), contentPadding = PaddingValues(12.dp)) {
            item { SourceOption("aggregate", tr("聚合 · 全部已启用书源"), true, selected, closeThenSelect) }
            items(sources, key = { it.id }) { s -> SourceOption(s.id, s.name, enabled[s.id] != false, selected, closeThenSelect) }
        }
        PullOutTextButton(onClick = paperOpen(onManage), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text(tr("管理书源")) }
    }
}

@Composable
private fun SourceOption(id: String, title: String, enabled: Boolean, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).pullOutClickable(enabled = enabled) { onSelect(id) }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (id == "aggregate") Icons.Outlined.Hub else Icons.Outlined.MenuBook, null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (enabled) title else com.example.i18n.titleDisabled(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (id == selected) Icon(Icons.Outlined.Check, tr("当前书源"), tint = MaterialTheme.colorScheme.primary)
    }
}

/** Navigation changes no filter. Multiple standard choices commit once with Apply. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiscoverCategorySheet(entries: List<DiscoverEntry>, selected: Set<String>,
    catalogs: List<Pair<BookSource, DiscoverCatalog>>, onApply: (Set<String>) -> Unit,
    onNative: (String, String) -> Unit, onDismiss: () -> Unit, onLoadCategories: (String) -> Unit = {}, partition: DiscoverPartition = DiscoverPartition.COMIC) {
    var draft by remember { mutableStateOf(selected) }
    var facet by remember { mutableStateOf("genre") }
    var search by remember { mutableStateOf("") }
    var nativeSource by remember { mutableStateOf(catalogs.firstOrNull()?.first?.id.orEmpty()) }
    var nativeGroup by remember { mutableStateOf("") }
    val works = remember(entries) { DiscoverEngine.merge(entries, DiscoverDimension.UPDATES) }
    val counts = remember(works) { works.flatMap { DiscoverTaxonomy.classify(it.tags) }.groupingBy { it }.eachCount() }
    val today = DiscoverEngine.rangeStart("今日", System.currentTimeMillis()) ?: Long.MAX_VALUE
    val updated = remember(works) { works.filter { (it.updatedAt ?: 0) >= today }.flatMap { DiscoverTaxonomy.classify(it.tags) }.toSet() }
    val supportedIds = remember(catalogs, counts) { catalogs.flatMap { it.second.categories }.flatMap { it.standardIds }.toSet() + counts.keys }
    LaunchedEffect(catalogs.map { it.first.id to it.second.dynamicCategories }) {
        catalogs.filter { it.second.dynamicCategories }.forEach { onLoadCategories(it.first.id) }
    }
    val openTaxonomy = catalogs.isEmpty() || catalogs.any { !it.second.declared }
    fun toggle(id: String) { draft = if (id in draft) draft - id else draft + id }
    AcrylicBottomOverlay(onDismissRequest = onDismiss, dismissOnBackgroundClick = false, fitAvailableHeight = true) {
        val close = LocalOverlayClose.current
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(com.example.i18n.findWhatYouWant(partition.label), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                PullOutIconButton(icon = Icons.Outlined.Close, contentDescription = tr("关闭分类窗口"), onClick = com.example.ui.components.paperDismiss(onDismiss))
            }
            if (facet == "native") {
                val catalog = catalogs.firstOrNull { it.first.id == nativeSource }?.second
                val groups = catalog?.categories.orEmpty().map { it.group }.filter { it.isNotBlank() }.distinct()
                val categories = catalog?.categories.orEmpty().filter { (nativeGroup.isBlank() || it.group == nativeGroup) &&
                    (search.isBlank() || it.label.contains(search, true) || it.standardIds.any { id -> DiscoverTaxonomy.all.any { it.id == id && it.label.contains(search) } }) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    item { CategorySearchHeader(search, facet, { search = it }, { facet = it; search = "" }) }
                    item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(catalogs, key = { it.first.id }) { (source, _) -> DiscoverChip(source.name, nativeSource == source.id,
                            onClick = { nativeSource = source.id; nativeGroup = "" }) }
                    } }
                    if (groups.size > 1) item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { DiscoverChip(tr("全部"), nativeGroup.isBlank(), solid = false, onClick = { nativeGroup = "" }) }
                        items(groups) { g -> DiscoverChip(g, nativeGroup == g, solid = false, onClick = { nativeGroup = g }) }
                    } }
                    items(categories.chunked(3)) { row -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { c -> DiscoverChip(com.example.i18n.tr(c.label), false, solid = false, outlined = true, onClick = { val source = nativeSource; close { onNative(source, c.id) } }) }
                    } }
                    if (categories.isEmpty()) item { Text(tr("这个书源暂未提供独立分类，可使用上方标准分类。"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
                    if (catalog?.dynamicCategories == true) item { PullOutTextButton(onClick = { onLoadCategories(nativeSource) }) { Text(tr("加载全部分类")) } }
                }
            } else {
                val root = DiscoverTaxonomy.roots.first { it.id == facet }
                val branches = root.children.filter { b ->
                    (openTaxonomy || b.id in supportedIds || b.children.any { it.id in supportedIds || it.id in draft || it.id in setOf("native_other", "year", "franchise") }) &&
                    (search.isBlank() || b.label.contains(search, true) || b.children.any { c -> c.label.contains(search, true) || c.aliases.any { it.contains(search, true) } }) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    item { CategorySearchHeader(search, facet, { search = it }, { facet = it; search = "" }) }
                    items(branches) { branch ->
                        Text(com.example.i18n.tr(branch.label), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (branch.id in supportedIds) DiscoverChip(com.example.i18n.allOf(branch.label), branch.id in draft, solid = false, outlined = true, onClick = { toggle(branch.id) })
                            branch.children.filter { c -> (openTaxonomy || c.id in supportedIds || c.id in draft || c.id in setOf("native_other", "year", "franchise")) &&
                                (search.isBlank() || branch.label.contains(search, true) || c.label.contains(search, true) || c.aliases.any { it.contains(search, true) }) }.forEach { c ->
                                val count = counts[c.id]?.let { com.example.i18n.itemsCount(it) }.orEmpty()
                                DiscoverChip(com.example.i18n.tr(c.label) + count + if (c.id in updated) tr(" · 今") else "", c.id in draft,
                                    solid = false, outlined = true, onClick = {
                                        if (c.id in setOf("native_other", "year", "franchise")) { facet = "native"; search = "" }
                                        else toggle(c.id)
                                    })
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PullOutTextButton(onClick = { draft = emptySet() }) { Text(tr("清除")) }
                AppActionButton(if (draft.isEmpty()) com.example.i18n.tr("查看全部") else com.example.i18n.applyFilters(draft.size), { val selectedDraft = draft; close { onApply(selectedDraft) } },
                    modifier = Modifier.weight(1f), fullWidth = true)
            }
        }
    }
}

/** Search and facets scroll under the fixed title/footer when the keyboard reduces height. */
@Composable
private fun CategorySearchHeader(search: String, facet: String, onSearch: (String) -> Unit, onFacet: (String) -> Unit) {
    Column {
        OutlinedTextField(search, onSearch, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(tr("搜索分类")) }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DiscoverTaxonomy.roots) { root -> DiscoverChip(com.example.i18n.tr(root.label), facet == root.id, solid = false, onClick = { onFacet(root.id) }) }
            item { DiscoverChip(tr("书源分类"), facet == "native", solid = false, onClick = { onFacet("native") }) }
        }
        Text(tr("组内多选，跨组组合 · 本数仅统计已加载作品"), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
    }
}
