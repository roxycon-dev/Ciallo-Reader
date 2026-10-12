package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class ReleaseSummary(val version: String, val date: String, val summary: String)
internal val releaseHistory = listOf(
    ReleaseSummary("1.3.4", "10-12", "漫画增强提速"),
    ReleaseSummary("1.3.3", "10-12", "增强加速 · 设置卡片统一"),
    ReleaseSummary("1.3.2", "10-12", "漫画增强 · 精确续读 · 极简渲染"),
    ReleaseSummary("1.3.1", "10-11", "动效与玻璃 · 导航修复"),
    ReleaseSummary("1.3.0", "10-09", "增强翻页无空白"),
    ReleaseSummary("1.2.9", "10-09", "增强细线与小字"),
    ReleaseSummary("1.2.8", "10-09", "画质增强重构"),
    ReleaseSummary("1.2.7", "10-07", "翻页与导航修复"),
    ReleaseSummary("1.2.6", "10-07", "分享与原归档"),
    ReleaseSummary("1.2.5", "10-07", "阅读体验与翻译"),
    ReleaseSummary("1.2.4", "10-06", "章节顺序修复"),
    ReleaseSummary("1.2.3", "10-05", "神回封面 · 应用更新"),
    ReleaseSummary("1.2.2", "10-05", "多语言搜索"),
    ReleaseSummary("1.2.1", "10-05", "书源与界面修复"),
    ReleaseSummary("1.2.0", "10-03", "小说与漫画阅读升级"),
    ReleaseSummary("1.1.5", "10-02", "聚合搜索 · 连续搜索"),
    ReleaseSummary("1.1.0", "09-24", "书源与键盘交互"),
    ReleaseSummary("1.0.7", "09-21", "界面适配 · 安装包瘦身"),
    ReleaseSummary("1.0.6", "09-08", "串珠翻页 · 漫画加载"),
    ReleaseSummary("1.0.5", "09-05", "漫画阅读 · 整页翻译"),
    ReleaseSummary("1.0.1", "08-29", "在线小说 · 聚合搜索"),
    ReleaseSummary("1.0.0", "08-28", "首次发布"),
)

/** Uses the common animated entrance/exit and follows reduced motion/minimal rendering. */
@Composable
internal fun ReleaseHistoryDialog(onDismiss: () -> Unit) {
    AcrylicDialog(onDismissRequest = onDismiss,
        modifier = Modifier.testTag("release-history"),
        title = { Text("更新目录", style = MaterialTheme.typography.titleLarge) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(releaseHistory, key = { it.version }) { release ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(release.version, fontWeight = FontWeight.SemiBold)
                            Text(release.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(release.summary, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }, confirmButton = {
            val close = LocalOverlayClose.current
            TextButton(onClick = { close {} }) { Text("关闭") }
        })
}
