package com.example.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.example.source.keyword.KeywordKeys
import com.example.source.keyword.KeywordProviderState
import com.example.ui.design.DesignTokens
import com.example.ui.components.AppSwitch
import com.example.ui.glasskit.GlassKitCard

/** A regular result item: metadata never occupies the pinned search bar. */
internal fun LazyListScope.keywordSearchPreviewItem(
    words: List<String>, status: String, origins: Map<String, String>, submitted: Map<String, Set<String>>,
    providers: List<KeywordProviderState> = emptyList(), onRetry: (() -> Unit)? = null,
) {
    if (words.isNotEmpty()) item(key = "keyword_search_preview", contentType = "keyword_preview") {
        KeywordSearchPreview(words, status, origins, submitted, providers = providers, onRetry = onRetry)
    }
}

internal fun LazyStaggeredGridScope.keywordSearchPreviewItem(
    words: List<String>, status: String, origins: Map<String, String>, submitted: Map<String, Set<String>>,
    providers: List<KeywordProviderState> = emptyList(), onRetry: (() -> Unit)? = null,
) {
    if (words.isNotEmpty()) item(key = "keyword_search_preview", contentType = "keyword_preview",
        span = StaggeredGridItemSpan.FullLine) {
        KeywordSearchPreview(words, status, origins, submitted, providers = providers, onRetry = onRetry)
    }
}

@Composable
internal fun KeywordSearchPreview(
    words: List<String>,
    status: String,
    origins: Map<String, String>,
    submitted: Map<String, Set<String>>,
    modifier: Modifier = Modifier,
    providers: List<KeywordProviderState> = emptyList(),
    onRetry: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable(words.firstOrNull()) { mutableStateOf(false) }
    GlassKitCard(modifier = modifier.fillMaxWidth().testTag("keyword_search_preview")) {
        Column(Modifier.padding(DesignTokens.SpaceMd), verticalArrangement = Arrangement.spacedBy(DesignTokens.SpaceSm)) {
            Row(Modifier.fillMaxWidth().heightIn(min = DesignTokens.SpaceXxl * 2)
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .clickable(role = Role.Button, onClickLabel = if (expanded) "收起搜索用词" else "查看搜索用词") { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpaceSm)) {
                Icon(Icons.Default.Translate, "多语言关键词", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(DesignTokens.SpaceXxl))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpaceSm)) {
                        Text("搜索用词", style = MaterialTheme.typography.labelLarge)
                        Text("${words.size} 个", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Text(status, style = MaterialTheme.typography.labelSmall,
                        color = if (status.startsWith("正在")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "收起搜索用词" else "查看搜索用词", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!expanded) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpaceSm)) {
                    words.forEach { word ->
                        Text(word, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = DesignTokens.SpaceXxl * 9)
                                .background(MaterialTheme.colorScheme.secondaryContainer, DesignTokens.shape(DesignTokens.RadiusXs))
                                .clickable(onClickLabel = "查看完整搜索用词") { expanded = true }
                                .padding(horizontal = DesignTokens.SpaceSm, vertical = DesignTokens.SpaceXs))
                    }
                }
            } else SelectionContainer {
                // The result list owns vertical scrolling, including long names.
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.SpaceSm)) {
                    words.forEach { word ->
                        val key = KeywordKeys.query(word)
                        val count = submitted[key].orEmpty().size
                        Column(Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, DesignTokens.shape(DesignTokens.RadiusSm))
                            .padding(DesignTokens.SpaceSm)) {
                            Text(word, style = MaterialTheme.typography.bodyMedium)
                            Text("${origins[key] ?: "扩展名称"} · " + if (count == 0) "等待提交" else "已提交 $count 个书源",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("已提交表示交给书源处理，结果由书源返回。长按名称可复制。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (expanded && providers.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.SpaceXs)) {
                providers.forEach { state ->
                    val outcome = when (state.outcome) {
                        "found" -> "已找到名称"
                        "empty" -> "未收录匹配名称"
                        "ambiguous" -> "存在同名，未自动采用"
                        else -> state.detail.ifBlank { "查询失败，请稍后重试" }
                    }
                    Text("${state.displayName} · $outcome", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onRetry != null && !status.startsWith("正在") &&
                (status.contains("失败") || status.contains("超时") || status.startsWith("未找到更多"))) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = DesignTokens.SpaceXs)) {
                    Text("重新查词", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Intrinsic row heights keep wrapped supporting text clear of the following option. */
@Composable
internal fun KeywordSearchOptions(
    enabled: Boolean, onEnabledChange: (Boolean) -> Unit,
    online: Boolean, onOnlineChange: (Boolean) -> Unit,
) {
    Column {
        ListItem(modifier = Modifier.testTag("multilingual_option"),
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = { Text("多语言搜索", style = MaterialTheme.typography.bodyMedium) },
            supportingContent = { Text(if (enabled) "开启：扩展常用词、作品、角色和作者别名" else "关闭：仅使用输入的原始关键词") },
            leadingContent = { Icon(Icons.Default.Translate, "多语言搜索",
                tint = if (enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingContent = { AppSwitch(checked = enabled, onCheckedChange = onEnabledChange) })
        if (enabled) ListItem(modifier = Modifier.testTag("online_keyword_option"),
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = { Text("缺词时在线补充", style = MaterialTheme.typography.bodyMedium) },
            supportingContent = { Text("优先查本地资料；在线查询名称后缓存，无痕时暂停") },
            leadingContent = { Spacer(Modifier.size(DesignTokens.SpaceXxl)) },
            trailingContent = { AppSwitch(checked = online, onCheckedChange = onOnlineChange) })
    }
}
