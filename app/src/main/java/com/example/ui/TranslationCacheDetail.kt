package com.example.ui

import com.example.ui.components.pullOutClickable
import com.example.ui.components.PullOutTextButton
import com.example.ui.components.PullOutDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mangatranslate.TranslationCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 漫画译文缓存明细（第十七轮）：手机内存管理式——逐条（每页一条）展示
 * 可读名与大小，支持单删、多选批删、全选、全清。数据源即缓存 JSON 文件，
 * 文件名是 SHA-256 哈希，条目名从 JSON 内容还原（首条原文×译文 + 区域数）。
 */
data class TranslationCacheEntry(
    val file: File,
    val size: Long,
    val label: String,
    val regionCount: Int,
)

object TranslationCacheIndex {

    /** 单个译文缓存文件的可读描述（通用明细对话框复用）。 */
    suspend fun describe(file: File): Pair<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val o = org.json.JSONObject(file.readText(Charsets.UTF_8))
            val arr = o.optJSONArray("r")
            val regions = arr?.length() ?: 0
            val first = arr?.optJSONObject(0)
            val src = first?.optString("o")?.take(18) ?: ""
            val dst = first?.optString("c")?.take(18) ?: ""
            val label = when {
                src.isNotBlank() && dst.isNotBlank() -> "$src → $dst"
                src.isNotBlank() -> src
                else -> "空译文"
            }.replace("\n", " ")
            label to "$regions 个气泡区域"
        }.getOrDefault(("未知页面" to ""))
    }

    /** 扫描缓存目录，解析每个 JSON 的首条原文/译文做可读标签。 */
    suspend fun scan(context: android.content.Context): List<TranslationCacheEntry> =
        withContext(Dispatchers.IO) {
            val dir = TranslationCache.dir(context)
            val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return@withContext emptyList()
            files.map { f ->
                var label = "未知页面"
                var regions = 0
                runCatching {
                    val o = JSONObject(f.readText(Charsets.UTF_8))
                    val arr = o.optJSONArray("r")
                    regions = arr?.length() ?: 0
                    val first = arr?.optJSONObject(0)
                    if (first != null) {
                        val src = first.optString("o").take(18)
                        val dst = first.optString("c").take(18)
                        label = when {
                            src.isNotBlank() && dst.isNotBlank() -> "$src → $dst"
                            src.isNotBlank() -> src
                            else -> "空译文"
                        }
                    }
                }
                TranslationCacheEntry(f, f.length(), label.replace('\n', ' '), regions)
            }.sortedByDescending { it.size }
        }

    suspend fun delete(files: List<File>): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        files.forEach { f -> runCatching { freed += f.length(); f.delete() } }
        freed
    }
}

/** 明细对话框（缓存管理页点击"漫画译文缓存"行打开）。 */
@Composable
fun TranslationCacheDetailDialog(
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<TranslationCacheEntry>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    val checked = remember { mutableStateMapOf<File, Boolean>() }

    fun refresh() {
        scope.launch {
            scanning = true
            entries = TranslationCacheIndex.scan(context)
            checked.keys.retainAll(entries.map { it.file }.toSet())
            scanning = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val selectedFiles = entries.filter { checked[it.file] == true }.map { it.file }
    val totalSize = entries.sumOf { it.size }

    PullOutDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Description, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("译文缓存明细", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (scanning) "扫描中…" else "${entries.size} 页 · 共 ${formatCacheSize(totalSize)}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(6.dp))

                if (!scanning && entries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        Text("暂无已翻译页面", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().height(340.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // 全选行
                        item(key = "select_all") {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .pullOutClickable {
                                        val allOn = entries.isNotEmpty() && entries.all { checked[it.file] == true }
                                        entries.forEach { checked[it.file] = !allOn }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (entries.isNotEmpty() && entries.all { checked[it.file] == true }) "取消全选"
                                    else "全选（${entries.size}）",
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    if (selectedFiles.isEmpty()) ""
                                    else "已选 ${selectedFiles.size} 项 / ${formatCacheSize(selectedFiles.sumOf { it.length() })}",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(entries, key = { it.file.absolutePath }) { entry ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .pullOutClickable { checked[entry.file] = !(checked[entry.file] ?: false) }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (checked[entry.file] == true) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (checked[entry.file] == true) {
                                        Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        "${entry.regionCount} 个气泡区域",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    formatCacheSize(entry.size), fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Filled.Delete, contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .pullOutClickable {
                                            scope.launch {
                                                TranslationCacheIndex.delete(listOf(entry.file))
                                                refresh()
                                            }
                                        },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(8.dp))
                Row {
                    PullOutTextButton(
                        onClick = {
                            scope.launch {
                                TranslationCache.clear(context)
                                refresh()
                            }
                        },
                        enabled = entries.isNotEmpty() && !scanning,
                    ) { Text("全部清理", color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.weight(1f))
                    PullOutTextButton(
                        onClick = com.example.ui.components.paperDismiss(onDismiss),
                    ) { Text("关闭") }
                    PullOutTextButton(
                        onClick = {
                            scope.launch {
                                TranslationCacheIndex.delete(selectedFiles)
                                refresh()
                            }
                        },
                        enabled = selectedFiles.isNotEmpty(),
                    ) { Text("删除所选（${selectedFiles.size}）") }
                }
            }
        }
    }
}

internal fun formatCacheSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1fMB".format(bytes / 1048576f)
    bytes >= 1 shl 10 -> "%dKB".format(bytes / 1024)
    else -> "${bytes}B"
}
