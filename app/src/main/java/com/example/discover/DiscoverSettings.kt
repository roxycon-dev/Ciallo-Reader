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

@Composable
fun DiscoverSettings(sources: List<Pair<String, String>> = emptyList()) {
    val (store, revision) = rememberDiscoverStore()
    var blockedOpen by remember { mutableStateOf(false) }
    DiscoverGlass(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
        Text("发现与推荐", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = discoverInk())
        Spacer(Modifier.height(8.dp))
        Text("默认书源", fontSize = 13.sp, color = discoverInk())
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("aggregate" to "聚合") + sources) { (id, name) -> DiscoverChip(name, store.string("default_source", "aggregate") == id,
                onClick = { store.put("default_source", id); store.put("last_source", id) }) }
        }
        Text("默认维度", fontSize = 13.sp, color = discoverInk())
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DiscoverDimension.entries) { d -> DiscoverChip(d.label, store.string("default_dimension", "UPDATES") == d.name,
                onClick = { store.put("default_dimension", d.name); store.put("last_dimension", d.name) }) }
        }
        Text("浏览布局", fontSize = 13.sp, color = discoverInk())
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DiscoverLayout.entries) { l -> DiscoverChip(l.label, store.string("layout", "GRID") == l.name, onClick = { store.put("layout", l.name) }) }
        }
        SettingsToggle("轻度个性化", "根据追漫和阅读记录调整同分推荐", store.flag("personalization")) { store.put("personalization", it) }
        SettingsToggle("显示成人向分类", "默认隐藏成人内容与成人分类", store.flag("adult", false)) {
            store.put("adult", it)
            if (!it) store.put("categories", store.selection("categories") - DiscoverTaxonomy.roots.last().leaves)
        }
        SettingsToggle("连续浏览休息提醒", "温和提醒，不打断阅读", store.flag("rest")) { store.put("rest", it) }
        if (store.flag("rest")) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("15", "30", "45", "60")) { minutes -> DiscoverChip("${minutes}分钟", store.string("rest_minutes", "30") == minutes,
                solid = false, onClick = { store.put("rest_minutes", minutes) }) }
        }
        SettingsToggle("绿涨红跌", "关闭时为红涨绿跌，箭头始终显示", store.flag("green_rise", false)) { store.put("green_rise", it) }
        SettingsToggle("缓存发现内容", "网络失败时显示上次缓存", store.flag("cache")) { store.put("cache", it); if (!it) store.cache.edit().clear().apply() }
        if (store.flag("cache")) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("6", "24", "72")) { hours -> DiscoverChip("${hours}小时", store.string("cache_hours", "24") == hours,
                solid = false, onClick = { store.put("cache_hours", hours) }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { blockedOpen = true }) { Text("屏蔽管理（${store.selection("blocked").size}）") }
            TextButton(onClick = { store.cache.edit().clear().apply() }) { Text("清除缓存") }
        }
        TextButton(onClick = { store.put("categories", emptySet()) }) { Text("清除记忆的分类筛选") }
    }
    if (blockedOpen) AlertDialog(onDismissRequest = { blockedOpen = false }, title = { Text("屏蔽管理") },
        text = {
            Column {
                val blocked = store.selection("blocked")
                if (blocked.isEmpty()) Text("还没有屏蔽作品")
                else androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(blocked.toList(), key = { it }) { key -> TextButton(onClick = { store.put("blocked", blocked - key) }) { Text("解除屏蔽 · ${key.removePrefix("title:").substringBefore('|')}") } }
                }
            }
        }, confirmButton = { TextButton(onClick = { store.put("blocked", emptySet()); blockedOpen = false }) { Text("全部解除") } },
        dismissButton = { TextButton(onClick = { blockedOpen = false }) { Text("完成") } })
}

@Composable
private fun SettingsToggle(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, fontSize = 13.sp, color = discoverInk())
            Text(description, fontSize = 11.sp, color = discoverInk().copy(alpha = .64f))
        }
        com.example.ui.components.AppSwitch(checked, change, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
    }
}
