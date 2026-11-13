package com.example.ui.source

import com.example.i18n.tr

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.library.ZLibraryNodeManager
import com.example.source.SearchBook
import com.example.source.zlibrary.ZLibraryCredentialStorage
import com.example.source.zlibrary.ZLibraryWebViewHelper
import com.example.source.zlibrary.network.ZLibraryHttpClient
import com.example.source.zlibrary.parser.ZLibraryParserManager
import com.example.ui.components.AppIconButton
import com.example.ui.components.paperDismiss
import com.example.ui.glasskit.GlassKitCard
import com.example.ui.glasskit.GlassKitHost
import com.example.ui.glasskit.GlassManagementTopBar
import com.example.ui.glasskit.GlassTopBarStrip
import com.example.ui.wenku.WenkuActionSheet
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton
import com.example.ui.wenku.wenkuPagePart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

import com.example.ui.components.AppToast

/**
 * Z-Library 节点管理窗口：
 * - 默认节点（1lib.sk）+ 官网/备用入口三个节点 + 用户自定义节点；
 * - 每个节点可“检测”（游离 WebView 真实搜索“三体”并解析）；
 * - “扒取节点”按钮抓取 https://z.wwwnav.com/rkfby.html 的三个入口，确认后替换；
 * - 切换节点立即生效并持久化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZLibraryNodeManagementScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scrapedNodes by remember { mutableStateOf(ZLibraryNodeManager.getScrapedNodes(context)) }
    var customNodes by remember { mutableStateOf(ZLibraryNodeManager.getCustomNodes(context)) }
    var selectedNode by remember { mutableStateOf(ZLibraryNodeManager.getSelectedNode(context)) }
    var customInput by remember { mutableStateOf("") }
    var scraping by remember { mutableStateOf(false) }
    var showReplaceDialog by remember { mutableStateOf(false) }
    var foundNodes by remember { mutableStateOf<List<String>>(emptyList()) }

    val statuses = remember { mutableStateMapOf<String, String>() }
    var testingNode by remember { mutableStateOf<String?>(null) }

    fun testNode(node: String) {
        if (testingNode != null) return
        testingNode = node
        statuses[node] = "测试中…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val httpClient = ZLibraryHttpClient(
                        credentialStorage = ZLibraryCredentialStorage(context),
                        context = context
                    )
                    val encodedKw = URLEncoder.encode("三体", "UTF-8").replace("+", "%20")
                    val url = "https://$node/s/$encodedKw"
                    val response = httpClient.get(url, referer = "https://$node/")
                    val code = response.code
                    val html = response.body?.string() ?: ""
                    // 修复"搜索出40本"假通过：入口跳转域会把 /s/{kw} 302 丢路径跳到
                    // 主页，主页书卡片被当搜索结果充数。真实搜索 ① 最终 URL 仍在 /s/
                    // ② 结果与关键词相关（命中书名含"三体"）。
                    val finalPath = response.request.url.encodedPath
                    val redirectedOffSearch = !finalPath.startsWith("/s/")
                    response.close()

                    // 官网搜索服务故障（2026-08 全站性）：节点本身可达但搜索不可用。
                    // 此时不算"不可用"，也不再走 WebView 兜底（只会浪费 ~36s 后同样失败）。
                    if (html.contains("Search service temporary unavailable", ignoreCase = true)) {
                        return@withContext "站点可达，搜索服务暂不可用（官网故障）"
                    }

                    // 重定向丢路径：搜索请求根本没到搜索服务，主页书卡不算数
                    if (redirectedOffSearch) {
                        return@withContext "不可用（搜索被重定向到主页，非真实镜像）"
                    }

                    val httpResult = if (code !in 200..299) {
                        "不可用（HTTP $code）"
                    } else if (
                        html.contains("Verifying your browser", ignoreCase = true) ||
                        html.contains("Checking your browser", ignoreCase = true) ||
                        html.contains("Just a moment", ignoreCase = true) ||
                        html.contains("solve this captcha", ignoreCase = true) ||
                        html.contains("cpt.lib")
                    ) {
                        "需验证（无法自动搜索）"
                    } else {
                        val books: List<SearchBook> = try {
                            ZLibraryParserManager.parseSearchPage(html, "https://$node", "zlibrary")
                        } catch (e: Exception) {
                            emptyList()
                        }
                        // 关键词相关性校验：真实搜索结果必然含命中书名
                        val relevant = books.isNotEmpty() &&
                            (html.contains("三体") || books.any { it.title.contains("三体") })
                        when {
                            books.isNotEmpty() && relevant -> "可用（搜索到 ${books.size} 本）"
                            books.isNotEmpty() -> tr("不可用（结果与关键词无关，疑似主页充数）")
                            html.contains("/book/") ||
                            html.contains("z-bookcard") ||
                            html.contains("resItemBox") ||
                            html.contains("book-item") -> tr("可用（页面可打开）")
                            html.contains("Z-Library", ignoreCase = true) -> tr("可用（站点可达）")
                            else -> tr("不可用（无搜索结果）")
                        }
                    }
                    // 仅"不可用 / 需验证"才尝试 WebView（挑战/JS 渲染场景），
                    // "搜索服务暂不可用"直接返回，不再浪费 WebView 超时。
                    if (httpResult.startsWith("不可用") || httpResult.startsWith("需验证")) {
                        val web = ZLibraryWebViewHelper.searchViaWebView(
                            context,
                            node,
                            "三体",
                            cookies = ZLibraryCredentialStorage(context).getCookies()
                        )
                        when {
                            web.books.isNotEmpty() -> "可用（搜索到 ${web.books.size} 本）"
                            web.pageHasZlibMarkers -> tr("可用（页面可打开）")
                            web.stillChallenged -> tr("需验证（无法自动搜索）")
                            else -> httpResult
                        }
                    } else {
                        httpResult
                    }
                } catch (e: Exception) {
                    val web = ZLibraryWebViewHelper.searchViaWebView(
                        context,
                        node,
                        "三体",
                        cookies = ZLibraryCredentialStorage(context).getCookies()
                    )
                    if (web.books.isNotEmpty()) {
                        "可用（搜索到 ${web.books.size} 本）"
                    } else if (web.pageHasZlibMarkers) {
                        "可用（页面可打开）"
                    } else {
                        "不可用（${e.message?.take(40) ?: "连接失败"}）"
                    }
                }
            }
            if (testingNode == node) {
                statuses[node] = result
                testingNode = null
            }
        }
    }

    fun selectNode(node: String) {
        ZLibraryNodeManager.selectNode(context, node)
        selectedNode = node
        AppToast.makeText(context, com.example.i18n.switchedTo(node), Toast.LENGTH_SHORT).show()
    }

    val defaultNode = ZLibraryNodeManager.DEFAULT_NODE
    val builtinNodes = (listOf(defaultNode) + scrapedNodes).distinct()
    val customNodeList = customNodes.filterNot { it in builtinNodes }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val headerHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 48.dp
    val headerTravel = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }

    GlassKitHost(topGradientHeight = headerHeight + 24.dp) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            topBar = {
                Box {
                    GlassTopBarStrip(
                        collapsedFraction = (-scrollBehavior.state.contentOffset / headerTravel).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().height(headerHeight)
                    )
                    GlassManagementTopBar("节点管理", "LIBRARY / NODES", scrollBehavior, onBack)
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item {
                    GlassKitCard(modifier = Modifier.fillMaxWidth().wenkuPagePart(1)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "当前节点：$selectedNode",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                WenkuTextButton(
                                    text = if (scraping) tr("扒取中…") else tr("扒取节点"),
                                    onClick = {
                                        scope.launch {
                                            scraping = true
                                            val nodes = ZLibraryNodeManager.scrapeNodes(context)
                                            scraping = false
                                            if (nodes.isNotEmpty()) {
                                                foundNodes = nodes
                                                showReplaceDialog = true
                                            } else {
                                                AppToast.makeText(context, tr("扒取失败，请检查网络后重试"), Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    selected = true,
                                    compact = true,
                                    wenkuSymbol = WenkuSymbol.Refresh,
                                    enabled = !scraping
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "从 zlib.wwkejishe.top 抓取全部官方入口（优先尝试/备用/中文），检测后点“使用”切换，立即生效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item {
                    Text(
                        "节点列表",
                        modifier = Modifier.wenkuPagePart(2),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                itemsIndexed(builtinNodes, key = { _, node -> node }) { index, node ->
                    val isDefault = node == defaultNode
                    val verifiedLive = node in ZLibraryNodeManager.VERIFIED_LIVE_NODES
                    val label = when {
                        isDefault -> tr("默认节点")
                        verifiedLive -> tr("已验证可用")
                        node == "z-lib.sk" -> tr("硬挑战·留池")
                        else -> tr("导航站节点")
                    }
                    NodeCard(
                        node = node,
                        label = label,
                        isSelected = selectedNode == node,
                        status = statuses[node] ?: "未测试",
                        isTesting = testingNode == node,
                        canDelete = false,
                        modifier = Modifier.wenkuPagePart(index + 3),
                        onTest = { testNode(node) },
                        onSelect = { selectNode(node) },
                        onDelete = {
                            ZLibraryNodeManager.removeCustomNode(context, node)
                            customNodes = ZLibraryNodeManager.getCustomNodes(context)
                            selectedNode = ZLibraryNodeManager.getSelectedNode(context)
                            statuses.remove(node)
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "自定义节点",
                        modifier = Modifier.wenkuPagePart(builtinNodes.size + 3),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                itemsIndexed(customNodeList, key = { _, node -> node }) { index, node ->
                    NodeCard(
                        node = node,
                        label = tr("自定义节点"),
                        isSelected = selectedNode == node,
                        status = statuses[node] ?: "未测试",
                        isTesting = testingNode == node,
                        canDelete = true,
                        modifier = Modifier.wenkuPagePart(builtinNodes.size + index + 4),
                        onTest = { testNode(node) },
                        onSelect = { selectNode(node) },
                        onDelete = {
                            ZLibraryNodeManager.removeCustomNode(context, node)
                            customNodes = ZLibraryNodeManager.getCustomNodes(context)
                            selectedNode = ZLibraryNodeManager.getSelectedNode(context)
                            statuses.remove(node)
                        }
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().wenkuPagePart(builtinNodes.size + customNodeList.size + 4),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = customInput,
                            onValueChange = { customInput = it },
                            placeholder = { Text(tr("自定义节点域名")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        WenkuTextButton(
                            text = tr("添加"),
                            onClick = {
                                if (ZLibraryNodeManager.addCustomNode(context, customInput)) {
                                    customNodes = ZLibraryNodeManager.getCustomNodes(context)
                                    customInput = ""
                                    AppToast.makeText(context, tr("已添加自定义节点"), Toast.LENGTH_SHORT).show()
                                } else {
                                    AppToast.makeText(context, tr("请输入有效的域名"), Toast.LENGTH_SHORT).show()
                                }
                            },
                            compact = true,
                            wenkuSymbol = WenkuSymbol.Add,
                            enabled = customInput.isNotBlank()
                        )
                    }
                }
            }
        }
    }

    if (showReplaceDialog) {
        WenkuActionSheet("找到 ${foundNodes.size} 个节点", onDismiss = { showReplaceDialog = false }) {
            Text(tr("是否合并以下节点？内置已验证节点会保留。"))
            foundNodes.forEach { node ->
                Text(node, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
            WenkuTextButton(
                "合并",
                onClick = paperDismiss {
                    ZLibraryNodeManager.saveScrapedNodes(context, foundNodes)
                    scrapedNodes = ZLibraryNodeManager.getScrapedNodes(context)
                    AppToast.makeText(context, tr("已合并节点"), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                selected = true,
                wenkuSymbol = WenkuSymbol.Check
            )
            WenkuTextButton(tr("取消"), onClick = paperDismiss {}, modifier = Modifier.fillMaxWidth(), wenkuSymbol = WenkuSymbol.Close)
        }
    }
}

@Composable
private fun NodeCard(
    node: String,
    label: String,
    isSelected: Boolean,
    status: String,
    isTesting: Boolean,
    canDelete: Boolean,
    onTest: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isOk = status.startsWith("可用") || status.startsWith("站点可达")
    val isFail = status == "不可用" || status.startsWith("需验证") || status == "初始化失败"
    val colors = MaterialTheme.colorScheme
    GlassKitCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(node, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = colors.primary.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "当前使用",
                                    color = colors.primary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isTesting) {
                            com.example.ui.components.AppProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = colors.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        } else if (isOk) {
                            Icon(Icons.Default.CheckCircle, null, tint = colors.primary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                        } else if (isFail) {
                            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("$label · $status", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                WenkuTextButton(
                    text = tr("检测"),
                    onClick = onTest,
                    enabled = !isTesting,
                    compact = true,
                    wenkuSymbol = WenkuSymbol.Search
                )
                WenkuTextButton(
                    text = if (isSelected) tr("使用中") else tr("使用"),
                    onClick = onSelect,
                    selected = isSelected,
                    compact = true,
                    wenkuSymbol = if (isSelected) WenkuSymbol.Selected else WenkuSymbol.Circle
                )
                if (canDelete) {
                    Spacer(modifier = Modifier.weight(1f))
                    AppIconButton(
                        icon = Icons.Default.Delete,
                        contentDescription = tr("删除节点"),
                        onClick = onDelete,
                        tint = MaterialTheme.colorScheme.error,
                        glyphSize = 22.dp
                    )
                }
            }
        }
    }
}
