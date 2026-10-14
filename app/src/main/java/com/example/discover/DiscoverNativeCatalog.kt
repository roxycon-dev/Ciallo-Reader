package com.example.discover

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Large native tag catalogues remain reachable without lossy forced taxonomy mapping. */
@Composable
internal fun DiscoverNativeCatalog(catalog: DiscoverCatalog, feed: String?, category: String?, adult: Boolean,
    onFeed: (String) -> Unit, onCategory: (String) -> Unit) {
    var search by remember(catalog) { mutableStateOf("") }
    var group by remember(catalog) { mutableStateOf("") }
    val categories = remember(catalog, adult) { catalog.categories.filter { adult || !DiscoverTaxonomy.isAdult(listOf(it.label)) } }
    val groups = remember(categories) { categories.map { it.group }.filter { it.isNotBlank() }.distinct() }
    val filtered = remember(categories, group, search) { categories.filter { (group.isEmpty() || it.group == group) &&
        (search.isBlank() || DiscoverEngine.normalize(it.label).contains(DiscoverEngine.normalize(search))) } }
    DiscoverGlass(Modifier.fillMaxWidth(), padding = PaddingValues(8.dp)) {
        Text("原站入口", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = discoverInk())
        Text("分类来自书源，标准分类按已提供标签筛选", fontSize = 11.sp, color = discoverInk().copy(alpha = .64f))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(catalog.feeds, key = { it.id }) { f -> DiscoverChip(f.label, feed == f.id, solid = false, onClick = { onFeed(f.id) }) }
        }
        if (categories.size > 12) OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(),
            label = { Text("搜索原站分类 · ${categories.size} 项") }, singleLine = true)
        if (groups.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { DiscoverChip("全部分组", group.isEmpty(), solid = false, onClick = { group = "" }) }
            items(groups) { g -> DiscoverChip(g, group == g, solid = false, onClick = { group = g }) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.id }) { c -> DiscoverChip(c.label, category == c.id, solid = false,
                onClick = { onCategory(c.id) }) }
        }
        if (search.isNotBlank() && filtered.isEmpty()) Text("没有匹配的原站分类", fontSize = 13.sp, color = discoverInk())
        if (catalog.dynamicCategories && categories.isEmpty()) TextButton(onClick = { onFeed("native") }) { Text("加载原站分类目录") }
    }
}
