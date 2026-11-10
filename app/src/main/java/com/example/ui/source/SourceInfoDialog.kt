package com.example.ui.source

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.example.source.BookSource
import com.example.source.js.JsComicSource
import com.example.ui.components.paperDismiss
import com.example.ui.wenku.WenkuActionSheet
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton

/** Describe declared capabilities separately from current enablement or reachability. */
@Composable
internal fun SourceInfoDialog(source: BookSource, enabled: Boolean, onDismiss: () -> Unit) {
    val caps = source.capabilities
    WenkuActionSheet(source.name, onDismiss) {
        Text(source.id, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        Text("启用状态：${if (enabled) "已启用" else "已停用"}")
        if (source is JsComicSource) {
            Text("适配方式：Venera / JS 漫画源")
            Text("脚本版本：${source.version}")
        } else Text("适配方式：应用内漫画源")
        Text("站内搜索：${if (caps.supportSearch) "支持" else "不支持"}")
        Text("章节阅读：${if (caps.supportComic) "支持图片漫画" else "未声明"}")
        Text("章节下载：${if (caps.supportDownload) "支持" else "不支持"}")
        Text("搜索登录：${if (caps.searchRequiresLogin) "需要登录" else "适配器未要求登录"}")
        Text("阅读 / 下载登录：${if (caps.downloadRequiresLogin) "需要登录" else "适配器未要求登录"}")
        Text(
            "作品资料以源站实际返回为准。启用状态表示是否参与搜索。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WenkuTextButton("知道啦", paperDismiss {}, Modifier.fillMaxWidth(), selected = true, wenkuSymbol = WenkuSymbol.Check)
    }
}
