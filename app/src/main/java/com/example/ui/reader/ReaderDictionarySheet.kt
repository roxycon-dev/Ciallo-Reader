package com.example.ui.reader

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.data.DictionaryResult
import com.example.data.OfflineDictionary
import com.example.data.ReaderDictionaryStore
import com.example.i18n.tr
import com.example.ui.components.AppIconButton
import com.example.ui.components.PullOutTextButton
import com.example.ui.wenku.LocalWenkuDecorativeMotion
import com.example.ui.wenku.rememberWenkuAccessibility
import com.example.ui.wenku.WenkuPillButton
import com.example.ui.wenku.WenkuSheetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DictionaryPane { Lookup, Select, Manage }

@Composable
internal fun ReaderDictionarySheet(passage: String, initialQuery: String = "", onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ReaderDictionaryStore(context) }
    val scope = rememberCoroutineScope()
    val motion = rememberWenkuAccessibility().decorativeMotion && LocalWenkuDecorativeMotion.current
    val colors = MaterialTheme.colorScheme
    var close by remember { mutableStateOf(false) }
    var pane by remember { mutableStateOf(DictionaryPane.Lookup) }
    var query by remember(initialQuery) { mutableStateOf(initialQuery.trim().take(120)) }
    var selectedText by remember(passage) { mutableStateOf(TextFieldValue(passage.take(1200))) }
    var dictionaries by remember { mutableStateOf<List<OfflineDictionary>>(emptyList()) }
    var results by remember { mutableStateOf<List<DictionaryResult>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun mutate(message: String, action: suspend () -> List<OfflineDictionary>) {
        busy = true
        error = null
        scope.launch {
            try {
                dictionaries = action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = message
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
        if (files.isNotEmpty()) mutate("无法导入词典，请检查文件是否齐全或格式是否受支持") { store.import(files) }
    }
    LaunchedEffect(store) {
        mutate("无法读取词典库", store::list)
    }
    LaunchedEffect(query, dictionaries, busy) {
        results = null
        loading = false
        if (query.isBlank() || dictionaries.none { it.enabled } || busy) return@LaunchedEffect
        loading = true
        delay(180)
        try {
            results = store.lookup(query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "无法读取词典释义"
        } finally {
            loading = false
        }
    }

    WenkuSheetDialog(onDismissRequest = onDismiss, requestDismiss = close, fitKeyboard = true) {
        BackHandler(enabled = pane != DictionaryPane.Lookup) { pane = DictionaryPane.Lookup }
        val keyboard = LocalSoftwareKeyboardController.current
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(start = 20.dp, end = 20.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pane != DictionaryPane.Lookup) {
                    AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, tr("返回"), { pane = DictionaryPane.Lookup })
                }
                Text(tr(when (pane) {
                    DictionaryPane.Lookup -> "词典查词"
                    DictionaryPane.Manage -> "离线词典"
                    DictionaryPane.Select -> "从正文选词"
                }), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                AppIconButton(Icons.Filled.Close, tr("关闭"), { close = true })
            }
            if (error != null) {
                Text(tr(error.orEmpty()), color = colors.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            AnimatedContent(
                targetState = pane,
                transitionSpec = {
                    if (motion) {
                        fadeIn() togetherWith fadeOut()
                    } else {
                        (EnterTransition.None togetherWith ExitTransition.None) using null
                    }
                },
                modifier = Modifier.weight(1f, fill = false),
                label = "readerDictionaryPane"
            ) { current ->
                when (current) {
                    DictionaryPane.Lookup -> Column(Modifier.fillMaxWidth()) {
                        OutlinedTextField(query, { query = it.take(120); error = null }, Modifier.fillMaxWidth(),
                            singleLine = true, label = { Text(tr("输入单词或短语")) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            PullOutTextButton(onClick = {
                                keyboard?.hide()
                                pane = DictionaryPane.Select
                            }, enabled = passage.isNotBlank()) { Text(tr("从正文选词")) }
                            PullOutTextButton(onClick = {
                                keyboard?.hide()
                                pane = DictionaryPane.Manage
                            }) { Text(tr("管理词典")) }
                        }
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            when {
                                busy -> Unit
                                dictionaries.none { it.enabled } -> Text(tr("导入 StarDict、MDX 或 MOBI 词典后即可离线查看释义。"),
                                    color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                                query.isBlank() -> Unit
                                loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                                results?.isEmpty() == true -> Text(tr("离线词典未找到这个词"),
                                    color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                                else -> results.orEmpty().forEach { result ->
                                    HorizontalDivider(color = colors.outlineVariant)
                                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                        Text(result.dictionary, color = colors.primary, style = MaterialTheme.typography.labelMedium)
                                        Text(result.word, style = MaterialTheme.typography.titleMedium)
                                        SelectionContainer { Text(result.definition, style = MaterialTheme.typography.bodyMedium) }
                                    }
                                }
                            }
                        }
                        if (query.isNotBlank()) {
                            WenkuPillButton(tr("用有道在线查词"), {
                                val url = Uri.Builder().scheme("https").authority("dict.youdao.com")
                                    .path("/search").appendQueryParameter("q", query.trim()).build()
                                if (runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }.isFailure) {
                                    error = "无法打开浏览器"
                                }
                            }, primary = false, icon = Icons.Filled.Search)
                        }
                    }
                    DictionaryPane.Select -> Column(Modifier.fillMaxWidth()) {
                        Text(tr("长按文字，拖动选区手柄选择单词或短语。"),
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(selectedText, { selectedText = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            readOnly = true, maxLines = 10)
                        WenkuPillButton(tr("查询选中文字"), {
                            val start = selectedText.selection.min
                            val end = selectedText.selection.max
                            query = selectedText.text.substring(start, end).trim().take(120)
                            error = null
                            pane = DictionaryPane.Lookup
                        }, enabled = !selectedText.selection.collapsed, icon = Icons.Filled.Search)
                    }
                    DictionaryPane.Manage -> Column(Modifier.fillMaxWidth()) {
                        Text(tr("可选择 MDX、MOBI、ZIP，或同时选择同名的 StarDict .ifo、.idx 和 .dict 文件。"),
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp))
                        PullOutTextButton(onClick = {
                            if (runCatching { picker.launch(arrayOf("*/*")) }.isFailure) error = "无法打开文件选择器"
                        }, enabled = !busy) { Text(tr("导入词典")) }
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            dictionaries.forEach { dictionary ->
                                HorizontalDivider(color = colors.outlineVariant)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(dictionary.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    PullOutTextButton(onClick = {
                                        mutate("无法更新词典") { store.enable(dictionary.id, !dictionary.enabled) }
                                    }, enabled = !busy) { Text(tr(if (dictionary.enabled) "已启用" else "已停用")) }
                                    var confirm by remember(dictionary.id) { mutableStateOf(false) }
                                    AppIconButton(Icons.Filled.DeleteOutline, tr("删除"), { confirm = !confirm }, enabled = !busy)
                                    if (confirm) PullOutTextButton(onClick = {
                                        confirm = false
                                        mutate("无法删除词典") { store.remove(dictionary.id) }
                                    }, enabled = !busy) { Text(tr("确认删除")) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
