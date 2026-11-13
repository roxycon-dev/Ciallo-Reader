package com.example.ui

import com.example.i18n.tr


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.AppIconButton
import com.example.ui.components.paperDismiss
import com.example.ui.wenku.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.example.data.AppDatabase
import com.example.data.Book
import com.example.download.DownloadManager
import com.example.download.DownloadTaskEntity

/**
 * 通用存储明细对话框（第十八轮）：手机内存管理式——任意缓存/数据类别的
 * 逐文件/逐目录列表（可读名+大小），单删、批删、全选、全清。
 * 漫画译文缓存条目附加智能标签（原文→译文，复用 TranslationCacheIndex）。
 *
 * 文件扫描与删除使用相同的索引和写入保护。
 */
data class StorageFileEntry(
    val file: File,
    val size: Long,
    val label: String,
    val sublabel: String = "",
    val deletable: Boolean = true,
)

object StorageDetailIndex {

    private fun pathOf(value: String?): String? = value?.takeUnless { it.startsWith("content:") }
        ?.removePrefix("file://")?.let { File(it).absolutePath }

    private fun describe(
        key: String, file: File, books: List<Book>, tasks: List<DownloadTaskEntity>
    ): Pair<String, String> {
        val path = file.absolutePath
        val book = when (key) {
            "covers" -> books.firstOrNull { pathOf(it.coverUri) == path }
            "book_images" -> file.name.toIntOrNull()?.let { id -> books.firstOrNull { it.id == id } }
            "downloads", "imports", "comics" -> books.firstOrNull { pathOf(it.filePath) == path }
            else -> null
        }
        if (book != null) return "《${book.title}》" to when (key) {
            "covers" -> tr("书架封面")
            "book_images" -> tr("书内插图")
            "comics" -> tr("离线漫画页面")
            else -> "${file.extension.uppercase()} 原文件"
        }
        if (key == "downloads") {
            val task = tasks.firstOrNull {
                pathOf(it.filePath) == path ||
                    file.nameWithoutExtension == DownloadManager.sanitizeFileName(it.id)
            }
            if (task != null) return "《${task.title}》" to "下载记录 · ${task.format.uppercase()}"
        }
        if (key in setOf("downloads", "imports", "comics", "covers", "book_images")) {
            return "未关联的${when (key) {
                "covers" -> tr("封面")
                "book_images" -> tr("书内插图")
                "comics" -> tr("漫画")
                else -> tr("书籍文件")
            }}" to file.name
        }
        if (key == "manga_tr_models") return "离线翻译模型" to file.name
        if (key == "webview") return when (file.name) {
            "Cookies" -> tr("网页登录信息") to "Cookie"
            "Local Storage" -> tr("网页本地数据") to "登录与浏览记录"
            "Default" -> tr("网页浏览资料") to "浏览器数据"
            else -> tr("网页浏览数据") to file.name
        }
        if (key == "personalization") return when {
            file.name == "custom_font.ttf" -> tr("自定义字体") to ""
            file.name.startsWith("custom_poster_") -> tr("启动海报") to ""
            else -> tr("软件背景") to ""
        }
        if (key == "sources") return if (file.name == "novel_reader_backup.json")
            "设置备份" to "JSON" else tr("已安装书源") to file.name
        if (key == "appdata") return when {
            file.parentFile?.name == "databases" || file.name == "databases" -> tr("书架与阅读数据") to file.name
            file.parentFile?.name == "shared_prefs" || file.name == "shared_prefs" -> tr("应用设置") to file.name
            else -> tr("应用状态数据") to file.name
        }
        val category = when (key) {
            "ehimg" -> tr("网页图片缓存")
            "image_cache" -> tr("封面与图片缓存")
            "comic_image_cache" -> tr("漫画图片缓存")
            "comic_processed" -> tr("漫画增强缓存")
            "external_cache" -> tr("外部缓存")
            "temp" -> tr("临时文件")
            "other" -> tr("其他应用数据")
            "external_files" -> tr("外部应用文件")
            else -> tr("缓存文件")
        }
        return category to if (file.name.length > 18 && file.name.matches(Regex("[a-fA-F0-9_.-]+")))
            "内部编号 ${file.name.takeLast(8)}" else file.name
    }

    /** 把类别 key 展开为逐文件/逐目录条目（含可读标签）。 */
    suspend fun scan(
        key: String, targets: List<File>, books: List<Book> = emptyList(),
        tasks: List<DownloadTaskEntity> = emptyList()
    ): List<StorageFileEntry> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<StorageFileEntry>()
            val now = System.currentTimeMillis()
            for (dir in targets) {
                if (!dir.exists()) continue
                if (key == "comics" && dir.isDirectory) {
                    val size = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    val (label, sublabel) = describe(key, dir, books, tasks)
                    val active = dir.walkTopDown().any { it.isFile && it.lastModified() > now - 2 * 60_000L }
                    out.add(StorageFileEntry(dir, size, label, sublabel, !active))
                    continue
                }
                val activeRoot = when (key) {
                    "temp" -> dir.name != "share_temp" &&
                        dir.walkTopDown().any { it.isFile && it.lastModified() > now - 2 * 60_000L }
                    "comics" -> dir.walkTopDown().any { it.isFile && it.lastModified() > now - 2 * 60_000L }
                    "downloads" -> dir.listFiles()?.any {
                        it.isFile && it.name.endsWith(".tmp") && it.lastModified() > now - 30 * 60_000L
                    } == true
                    else -> false
                }
                if (dir.isFile) {
                    val activeFile = (key == "downloads" && dir.name.endsWith(".tmp") &&
                        dir.lastModified() > now - 30 * 60_000L) ||
                        (key == "imports" && dir.lastModified() > now - 2 * 60_000L)
                    val (label, sublabel) = describe(key, dir, books, tasks)
                    out.add(StorageFileEntry(dir, dir.length(), label, sublabel, !activeFile && !activeRoot))
                    continue
                }
                val children = dir.listFiles() ?: continue
                for (f in children.sortedByDescending { it.length() }) {
                    val size = if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    else f.length()
                    if (size <= 0L && f.isFile) continue
                    val protected = activeRoot ||
                        (key == "downloads" && f.name.endsWith(".tmp") && f.lastModified() > now - 30 * 60_000L) ||
                        (key == "imports" && f.lastModified() > now - 2 * 60_000L) ||
                        (key == "temp" && dir.name == "share_temp" && f.lastModified() > now - 15 * 60_000L) ||
                        (key == "temp" && dir.name != "share_temp" && f.lastModified() > now - 2 * 60_000L)
                    when (key) {
                        "manga_tr_cache" -> {
                            // 译文缓存：复用智能标签（原文→译文）
                            val info = TranslationCacheIndex.describe(f)
                            out.add(StorageFileEntry(f, size, info.first, info.second, !protected))
                        }
                        else -> {
                            val (label, sublabel) = describe(key, f, books, tasks)
                            out.add(StorageFileEntry(f, size, label, sublabel, !protected))
                        }
                    }
                }
            }
            out.sortedByDescending { it.size }
        }

    suspend fun delete(files: List<File>, key: String): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        files.forEach { f ->
            runCatching {
                val now = System.currentTimeMillis()
                val root = when (key) {
                    "comics" -> if (f.name.startsWith("comics_")) f else f.parentFile
                    "temp" -> if (f.parentFile?.name == "share_temp") f.parentFile else f.parentFile
                    else -> f.parentFile
                }
                val active = when (key) {
                    "downloads" -> root?.listFiles()?.any {
                        it.isFile && it.name.endsWith(".tmp") && it.lastModified() > now - 30 * 60_000L
                    } == true
                    "comics" -> root?.walkTopDown()?.any {
                        it.isFile && it.lastModified() > now - 2 * 60_000L
                    } == true
                    "temp" -> if (root?.name == "share_temp") {
                        f.lastModified() > now - 15 * 60_000L
                    } else {
                        root?.walkTopDown()?.any { it.isFile && it.lastModified() > now - 2 * 60_000L } == true
                    }
                    "imports" -> f.lastModified() > now - 2 * 60_000L
                    else -> false
                }
                if (active) return@runCatching
                val before = if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    else if (f.isFile) f.length() else 0L
                if (f.isDirectory) f.deleteRecursively() else f.delete()
                val after = if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    else if (f.isFile) f.length() else 0L
                freed += (before - after).coerceAtLeast(0L)
            }
        }
        freed
    }
}

/** 明细对话框：任意类别通用。rowKey 用于译文缓存智能标签分支。 */
@Composable
fun StorageDetailDialog(
    rowKey: String,
    title: String,
    targets: List<File>,
    confirmTip: String? = null,
    allowDelete: Boolean = true,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<StorageFileEntry>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var deleting by remember { mutableStateOf(false) }
    val checked = remember { mutableStateMapOf<File, Boolean>() }
    var pendingDeletion by remember { mutableStateOf<List<File>?>(null) }

    fun refresh() {
        scope.launch {
            scanning = true
            try {
                val (books, tasks) = if (rowKey in setOf("downloads", "imports", "comics", "covers", "book_images")) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val db = AppDatabase.getDatabase(context)
                            db.bookDao().getAllBooksSync() to db.downloadTaskDao().getAllTasksSync()
                        }
                    }.getOrDefault(emptyList<Book>() to emptyList<DownloadTaskEntity>())
                } else emptyList<Book>() to emptyList<DownloadTaskEntity>()
                entries = StorageDetailIndex.scan(rowKey, targets, books, tasks).map {
                    if (allowDelete) it else it.copy(deletable = false)
                }
                checked.keys.retainAll(entries.filter { it.deletable }.map { it.file }.toSet())
            } finally {
                scanning = false
            }
        }
    }
    fun deleteFiles(files: List<File>) {
        if (deleting || scanning) return
        deleting = true
        scope.launch {
            try {
                StorageDetailIndex.delete(files, rowKey)
                onChanged()
                refresh()
            } finally {
                deleting = false
            }
        }
    }

    fun requestDeletion(files: List<File>) {
        if (files.isEmpty() || scanning || deleting) return
        if (confirmTip != null) pendingDeletion = files else deleteFiles(files)
    }

    LaunchedEffect(Unit) { refresh() }

    val eligibleFiles = entries.filter { it.deletable }
    val selectedFiles = eligibleFiles.filter { checked[it.file] == true }
    val allSelected = eligibleFiles.isNotEmpty() && selectedFiles.size == eligibleFiles.size
    val canEdit = allowDelete && !scanning && !deleting
    val colors = MaterialTheme.colorScheme

    WenkuSheetDialog(onDismissRequest = onDismiss, dismissGesturesEnabled = !deleting) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val maxListHeight = maxHeight * .60f
            WenkuLoadedContentSequence(contentKey = scanning) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 24.dp)) {
                    Row(Modifier.fillMaxWidth().wenkuPagePart(0), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            val status = when {
                                deleting -> tr("正在清理…")
                                scanning -> tr("扫描中…")
                                else -> com.example.i18n.itemsOfTotal(entries.size, formatCacheSize(entries.sumOf { it.size }))
                            }
                            Text(status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        AppIconButton(Icons.Filled.Close, tr("关闭面板"), onClick = paperDismiss {}, enabled = !deleting)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (entries.isEmpty()) {
                        Text(
                            if (scanning) tr("正在读取存储内容…") else tr("暂无可管理的内容"),
                            Modifier.padding(vertical = 18.dp).wenkuPagePart(1),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            Modifier.fillMaxWidth().heightIn(max = maxListHeight),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(vertical = 6.dp),
                        ) {
                            if (eligibleFiles.isNotEmpty()) {
                                item(key = "select_all") {
                                    WenkuTextButton(
                                        if (allSelected) tr("取消全选") else tr("全选"),
                                        onClick = { eligibleFiles.forEach { checked[it.file] = !allSelected } },
                                        modifier = Modifier.fillMaxWidth().wenkuPagePart(1),
                                        enabled = canEdit, selected = allSelected, compact = true,
                                        wenkuSymbol = if (allSelected) WenkuSymbol.Selected else WenkuSymbol.Circle,
                                    )
                                }
                            }
                            itemsIndexed(entries, key = { _, entry -> entry.file.absolutePath }) { index, entry ->
                                val isSelected = entry.deletable && checked[entry.file] == true
                                WenkuContentPanel(
                                    Modifier.fillMaxWidth().wenkuPagePart(index + 2).semantics {
                                        if (entry.deletable) selected = isSelected
                                    },
                                    onClick = if (canEdit && entry.deletable) ({
                                        checked[entry.file] = !isSelected
                                    }) else null,
                                    contentPadding = PaddingValues(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                                ) {
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        if (entry.deletable) {
                                            WenkuIcon(
                                                if (isSelected) WenkuSymbol.Selected else WenkuSymbol.Circle,
                                                size = 16.dp,
                                                tint = if (isSelected) colors.primary else colors.onSurfaceVariant,
                                                entrance = false,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelLarge)
                                            if (entry.sublabel.isNotEmpty()) {
                                                Text(entry.sublabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                            }
                                            Text(formatCacheSize(entry.size), style = MaterialTheme.typography.bodySmall,
                                                color = colors.primary)
                                        }
                                        if (entry.deletable) {
                                            AppIconButton(Icons.Filled.Delete, "清理 ${entry.label}",
                                                onClick = { requestDeletion(listOf(entry.file)) }, enabled = canEdit,
                                                tint = colors.error, glyphSize = 17.dp, wenkuSymbol = WenkuSymbol.Delete)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (eligibleFiles.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "已选 ${selectedFiles.size} 项 · ${formatCacheSize(selectedFiles.sumOf { it.size })}",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WenkuTextButton(
                                "全部清理", { requestDeletion(eligibleFiles.map { it.file }) }, Modifier.weight(1f),
                                enabled = canEdit, compact = true, color = colors.error, wenkuSymbol = WenkuSymbol.Delete,
                            )
                            WenkuTextButton(
                                "删除所选", { requestDeletion(selectedFiles.map { it.file }) }, Modifier.weight(1f),
                                enabled = canEdit && selectedFiles.isNotEmpty(), selected = true, compact = true,
                                wenkuSymbol = WenkuSymbol.Check,
                            )
                        }
                    }
                }
            }
        }
    }
    pendingDeletion?.let { files ->
        val selectedBytes = entries.filter { it.file in files }.sumOf { it.size }
        WenkuActionSheet("确认清理 ${files.size} 项", onDismiss = { pendingDeletion = null }) {
            Text("预计清理 ${formatCacheSize(selectedBytes)}。")
            Text(confirmTip.orEmpty(), color = colors.onSurfaceVariant)
            WenkuTextButton(tr("清理"), onClick = paperDismiss { deleteFiles(files) }, modifier = Modifier.fillMaxWidth(),
                color = colors.error, wenkuSymbol = WenkuSymbol.Delete)
            WenkuTextButton(tr("取消"), onClick = paperDismiss {}, modifier = Modifier.fillMaxWidth(),
                wenkuSymbol = WenkuSymbol.Close)
        }
    }
}
