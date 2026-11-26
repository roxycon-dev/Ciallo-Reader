package com.example.ui.source

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.i18n.tr
import com.example.source.BookSource
import com.example.source.SourceViewModel
import com.example.source.js.JsComicSource
import com.example.ui.wenku.WenkuActionSheet
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton

@Composable
internal fun ComicSourceImportSheet(
    preview: SourceViewModel.ComicImport,
    existing: Map<String, BookSource>,
    onDismiss: () -> Unit,
    onInstall: (Set<String>) -> Unit,
) {
    val candidates = preview.candidates
    var selected by remember(candidates) {
        mutableStateOf(candidates.filter { it.installedVersion == null }
            .mapNotNull { it.definition?.id }.filterNot(existing::containsKey).toSet())
    }
    WenkuActionSheet(tr("导入漫画源"), onDismiss = onDismiss, dismissEnabled = !preview.busy) {
        AnimatedContent(preview.busy, label = "comicSourceImport") { busy ->
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(tr(if (candidates.isEmpty()) "正在识别 JS 文件…" else "正在导入漫画源…"))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("仅安装你信任的 Venera 兼容脚本。"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    candidates.forEachIndexed { index, candidate ->
                        val definition = candidate.definition
                        if (index != 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                                Text(definition?.name ?: candidate.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall)
                                val installedVersion = candidate.installedVersion
                                    ?: (definition?.let { existing[it.id] } as? JsComicSource)?.version
                                val detail = when {
                                    definition == null -> candidate.error.orEmpty()
                                    installedVersion != null -> "${tr("替换现有版本")} $installedVersion → ${definition.version}"
                                    else -> "${definition.key} · ${definition.version}"
                                }
                                Text(detail, style = MaterialTheme.typography.bodySmall,
                                    color = if (definition == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (definition != null) {
                                Checkbox(
                                    checked = definition.id in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + definition.id else selected - definition.id
                                    },
                                )
                            }
                        }
                    }
                    if (preview.overflow > 0) {
                        Text(tr("每次最多导入 20 个，另有 %d 个未检查").format(preview.overflow),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (!preview.busy) {
            WenkuTextButton(
                tr("导入所选（%d）").format(selected.size), onClick = { onInstall(selected) },
                modifier = Modifier.fillMaxWidth(), enabled = selected.isNotEmpty(), selected = true,
                wenkuSymbol = WenkuSymbol.Check,
            )
        }
    }
}
