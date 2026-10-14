package com.example.discover

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.source.BookSource
import com.example.ui.components.*

@Composable
internal fun DiscoverSourcePicker(sources: List<BookSource>, enabled: Map<String, Boolean>, selected: String,
    onSelect: (String) -> Unit, onDismiss: () -> Unit, onManage: () -> Unit) {
    AcrylicBottomOverlay(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("选择书源", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭书源窗口") }
        }
        LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .6f).dp), contentPadding = PaddingValues(12.dp)) {
            item { SourceOption("aggregate", "聚合 · 全部已启用书源", true, selected, onSelect) }
            items(sources, key = { it.id }) { s -> SourceOption(s.id, s.name, enabled[s.id] != false, selected, onSelect) }
        }
        TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text("管理书源") }
    }
}

@Composable
private fun SourceOption(id: String, title: String, enabled: Boolean, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled) { onSelect(id) }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (id == "aggregate") Icons.Outlined.Hub else Icons.Outlined.MenuBook, null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (enabled) title else "$title · 已停用", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (id == selected) Icon(Icons.Outlined.Check, "当前书源", tint = MaterialTheme.colorScheme.primary)
    }
}

/** Navigation changes no filter. Multiple standard choices commit once with Apply. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiscoverCategorySheet(entries: List<DiscoverEntry>, selected: Set<String>,
    catalogs: List<Pair<BookSource, DiscoverCatalog>>, onApply: (Set<String>) -> Unit,
    onNative: (String, String) -> Unit, onDismiss: () -> Unit, onLoadCategories: (String) -> Unit = {}) {
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
    AcrylicBottomOverlay(onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .84f).dp).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("找你想看的漫画", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭分类窗口") }
            }
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜索分类") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DiscoverTaxonomy.roots) { root -> DiscoverChip(root.label, facet == root.id, solid = false,
                    onClick = { facet = root.id; search = "" }) }
                item { DiscoverChip("书源分类", facet == "native", solid = false, onClick = { facet = "native"; search = "" }) }
            }
            Text("组内多选，跨组组合 · 数量与更新来自已加载作品",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp))
            if (facet == "native") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(catalogs, key = { it.first.id }) { (source, _) -> DiscoverChip(source.name, nativeSource == source.id,
                        onClick = { nativeSource = source.id; nativeGroup = "" }) }
                }
                val catalog = catalogs.firstOrNull { it.first.id == nativeSource }?.second
                val groups = catalog?.categories.orEmpty().map { it.group }.filter { it.isNotBlank() }.distinct()
                if (groups.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { DiscoverChip("全部", nativeGroup.isBlank(), solid = false, onClick = { nativeGroup = "" }) }
                    items(groups) { g -> DiscoverChip(g, nativeGroup == g, solid = false, onClick = { nativeGroup = g }) }
                }
                val categories = catalog?.categories.orEmpty().filter { (nativeGroup.isBlank() || it.group == nativeGroup) &&
                    (search.isBlank() || it.label.contains(search, true) || it.standardIds.any { id -> DiscoverTaxonomy.all.any { it.id == id && it.label.contains(search) } }) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(categories.chunked(3)) { row -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { c -> DiscoverChip(c.label, false, solid = false, outlined = true, onClick = { onNative(nativeSource, c.id) }) }
                    } }
                    if (categories.isEmpty()) item { Text("这个书源暂未提供独立分类，可使用上方标准分类。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
                    if (catalog?.dynamicCategories == true) item { TextButton(onClick = { onLoadCategories(nativeSource) }) { Text("加载全部分类") } }
                }
            } else {
                val root = DiscoverTaxonomy.roots.first { it.id == facet }
                val branches = root.children.filter { b ->
                    (openTaxonomy || b.id in supportedIds || b.children.any { it.id in supportedIds || it.id in draft || it.id in setOf("native_other", "year", "franchise") }) &&
                    (search.isBlank() || b.label.contains(search, true) || b.children.any { c -> c.label.contains(search, true) || c.aliases.any { it.contains(search, true) } }) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(branches) { branch ->
                        Text(branch.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (branch.id in supportedIds) DiscoverChip("全部${branch.label}", branch.id in draft, solid = false, outlined = true, onClick = { toggle(branch.id) })
                            branch.children.filter { c -> (openTaxonomy || c.id in supportedIds || c.id in draft || c.id in setOf("native_other", "year", "franchise")) &&
                                (search.isBlank() || branch.label.contains(search, true) || c.label.contains(search, true) || c.aliases.any { it.contains(search, true) }) }.forEach { c ->
                                val count = counts[c.id]?.let { " $it" }.orEmpty()
                                DiscoverChip(c.label + count + if (c.id in updated) " · 今" else "", c.id in draft,
                                    solid = false, outlined = true, onClick = {
                                        if (c.id in setOf("native_other", "year", "franchise")) { facet = "native"; search = "" }
                                        else toggle(c.id)
                                    })
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { draft = emptySet() }) { Text("清除") }
                AppActionButton(if (draft.isEmpty()) "查看全部" else "应用 ${draft.size} 项", { onApply(draft) },
                    modifier = Modifier.weight(1f), fullWidth = true)
            }
        }
    }
}
