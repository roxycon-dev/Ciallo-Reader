package com.example.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.i18n.tr
import com.example.ui.components.AppIconButton
import com.example.ui.wenku.WenkuSymbol

/** 音色列表的一行：唯一 id、主标题（音色名/语言）、副标题（语言码/引擎音色名）。 */
internal data class VoiceRow(val id: String, val label: String, val detail: String)

/**
 * 全屏音色选择器：标题 + 搜索框 + 可滚动的列表（LazyColumn）。
 *
 * 之前列表内联在听书弹窗里，弹窗本身又是定高可滚动容器，列表被压到只剩一行、
 * 末行还常被裁成半截字。改成独立全屏窗口后，列表由 LazyColumn 自己滚，
 * 每行单行省略，不会再出现"看起来像乱码"的裁切文字。
 */
@Composable
internal fun VoicePickerDialog(
    title: String,
    rows: List<VoiceRow>,
    selectedId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    loading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    autoLabel: String? = null,
) {
    var filter by remember { mutableStateOf("") }
    val visible = remember(rows, filter) {
        val text = filter.trim()
        if (text.isEmpty()) rows else rows.filter {
            it.label.contains(text, true) || it.detail.contains(text, true) || it.id.contains(text, true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            tr("共 %s 项").replace("%s", rows.size.toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppIconButton(
                        Icons.Filled.Close,
                        tr("关闭"),
                        onClick = onDismiss,
                        wenkuSymbol = WenkuSymbol.Close,
                    )
                }
                OutlinedTextField(
                    filter,
                    { filter = it },
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    label = { Text(tr("搜索语言或音色")) },
                    singleLine = true,
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                if (error != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            error,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (onRetry != null) TextButton(onClick = onRetry) { Text(tr("重试在线音色")) }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if (autoLabel != null) {
                        item("auto") {
                            VoicePickerRow(
                                label = autoLabel,
                                detail = "",
                                selected = selectedId.isEmpty(),
                                onClick = { onPick("") },
                            )
                        }
                    }
                    items(visible, key = { it.id }) { row ->
                        VoicePickerRow(
                            label = row.label,
                            detail = row.detail,
                            selected = row.id == selectedId,
                            onClick = { onPick(row.id) },
                        )
                    }
                    if (visible.isEmpty() && !loading) {
                        item("empty") {
                            Text(
                                tr("没有匹配的音色"),
                                Modifier.fillMaxWidth().padding(24.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoicePickerRow(
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        Modifier.fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            androidx.compose.material3.Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
