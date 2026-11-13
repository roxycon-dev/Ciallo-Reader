package com.example.ui.help

import com.example.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MintPrimary
import com.example.ui.theme.AppFonts

@Composable
fun JsonSourceGuideCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = tr("什么是 JSON 书源？"),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MintPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = tr("JSON 书源是一种不需要修改 APP 代码即可扩展站点检索的配置文件，兼容「开源阅读 Legado」社区规则。您只需用选择器描述搜索、目录和图片规则："),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // JSON Code block
            Text(
                text = tr("漫画/图集站 JSON 模板（可直接复制修改）："),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(12.dp)
            ) {
                Text(
                    text = """{
  "id": "example_comic",
  "name": "示例漫画源",
  "baseUrl": "https://example.com",
  "htmlSearch": {
    "url": "/search?q={keyword}",
    "listSelector": "@css:ul.list li.item",
    "title": "@css:a.book-name@text",
    "author": "@css:span.author@text",
    "cover": "@css:img.cover@data-src",
    "detailUrl": "@css:a.book-name@href"
  },
  "htmlChapters": {
    "url": "{id}",
    "listSelector": "@css:ul.chapters li",
    "name": "@css:a@text",
    "href": "@css:a@href"
  },
  "htmlContent": {
    "url": "{chapterUrl}",
    "imageSelector": "@css:div.reader img@data-src"
  }
}""",
                    fontFamily = AppFonts.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFD4D4D4),
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Table Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MintPrimary.copy(alpha = 0.1f))
                    .padding(8.dp)
            ) {
                Text(tr("字段名称"), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1.2f), color = MintPrimary)
                Text(tr("对应描述与作用说明"), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(2.8f), color = MintPrimary)
            }
            HorizontalDivider(color = MintPrimary.copy(alpha = 0.3f))

            val fields = listOf(
                "id" to com.example.i18n.tr("唯一英文字符标识，保证书源独立性"),
                "name" to com.example.i18n.tr("在书源列表中显示的友好名称"),
                "baseUrl" to com.example.i18n.tr("站点主站域名，用于拼合相对路径"),
                "htmlSearch.url" to com.example.i18n.tr("搜索地址，{keyword} 替换关键词，{page} 替换页码"),
                "htmlSearch.listSelector" to com.example.i18n.tr("结果列表项规则（支持 Legado 语法与 @css:）"),
                "htmlChapters.url" to com.example.i18n.tr("目录页地址，{id} 为搜索结果详情链接"),
                "htmlContent.imageSelector" to com.example.i18n.tr("章节图片规则，支持 @src / @data-src / JSONPath")
            )

            fields.forEach { (field, desc) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = field,
                        fontFamily = AppFonts.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1.2f)
                    )
                    Text(
                        text = desc,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(2.8f)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
        }
    }
}
