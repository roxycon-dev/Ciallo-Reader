package com.example.discover

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.components.*

/** Discover owns its settings window; shared by the Discover and global Settings entries. */
@Composable
fun DiscoverSettings(onDismissRequest: () -> Unit) {
    val (store, revision) = rememberDiscoverStore()
    var blockedOpen by remember { mutableStateOf(false) }
    AcrylicDialog(onDismissRequest = onDismissRequest, title = { Text("发现设置", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GlassCard(contentPadding = PaddingValues(16.dp)) {
                    Text("浏览布局", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    SegmentedPillSelector(DiscoverLayout.entries.map { it.ordinal to it.label },
                        DiscoverLayout.entries.indexOfFirst { it.name == store.string("layout", "GRID") }.coerceAtLeast(0),
                        { store.put("layout", DiscoverLayout.entries[it].name) })
                }
                GlassCard(contentPadding = PaddingValues(16.dp)) {
                    DiscoverSettingToggle("个性化推荐", "结合喜欢的作品与阅读记录", store.flag("personalization")) { store.put("personalization", it) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    DiscoverSettingToggle("绿涨红跌", "关闭时为红涨绿跌", store.flag("green_rise", false)) { store.put("green_rise", it) }
                }
                GlassCard(contentPadding = PaddingValues(16.dp)) {
                    DiscoverSettingToggle("离线缓存", "下次打开立即显示上次内容", store.flag("cache")) { store.put("cache", it); if (!it) store.cache.edit().clear().apply() }
                    if (store.flag("cache")) {
                        Spacer(Modifier.height(12.dp))
                        SegmentedPillSelector(listOf(6 to "6小时", 24 to "24小时", 72 to "72小时"),
                            store.string("cache_hours", "24").toIntOrNull() ?: 24, { store.put("cache_hours", it.toString()) })
                    }
                    TextButton(onClick = { store.cache.edit().clear().apply() }) { Text("清除离线缓存") }
                }
                AppActionButton("屏蔽管理", { blockedOpen = true }, variant = AppButtonVariant.Secondary, fullWidth = true)
            }
        }, confirmButton = { val close = LocalOverlayClose.current
            AppActionButton("完成", { close {} }, variant = AppButtonVariant.Primary) })
    if (blockedOpen) AcrylicDialog(onDismissRequest = { blockedOpen = false }, title = { Text("屏蔽管理") },
        text = {
            val blocked = store.blockedWorks()
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (blocked.isEmpty()) Text("还没有屏蔽作品")
                blocked.forEach { (key, title) ->
                    TextButton(onClick = { store.unblock(key) }) {
                        Text("解除 · $title")
                    }
                }
            }
        }, confirmButton = { val close = LocalOverlayClose.current
            AppActionButton("全部解除", { store.clearBlocked(); close {} }) })
}

@Composable
private fun DiscoverSettingToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppSwitch(checked, onChange)
    }
}
