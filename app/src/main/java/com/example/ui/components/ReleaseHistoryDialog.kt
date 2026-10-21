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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class ReleaseSummary(val version: String, val date: String, val summary: String)
internal val releaseHistory = listOf(
    ReleaseSummary("1.4.17", "10-21", "小说与漫画独立发现 · 完整页面统一抽纸 · 阅读设置退出优化"),
    ReleaseSummary("1.4.16", "10-21", "按钮直达完整界面 · 弹层交接统一 · 抽纸平稳落定"),
    ReleaseSummary("1.4.15", "10-21", "轻量柔性抽纸 · 修复底栏跳动 · 移除弹层角落白光"),
    ReleaseSummary("1.4.14", "10-21", "快速滚动与连续点击稳定性修复 · 抽纸简化为统一轻量过渡"),
    ReleaseSummary("1.4.13", "10-21", "毛玻璃抽纸稳定性与性能打磨 · 滑动条触控修复"),
    ReleaseSummary("1.4.12", "10-21", "抽纸加速 · 弹层生命周期修复"),
    ReleaseSummary("1.4.11", "10-20", "原尺寸触控分区与滑动校准 · 统一纸面动效与液体玻璃滑条 · 书源修复"),
    ReleaseSummary("1.4.10", "10-19", "新增书阁小说源 · 完整目录、在线文字与 EPUB 下载"),
    ReleaseSummary("1.4.9", "10-19", "小说插图引用与下载修复 · 失效节点恢复"),
    ReleaseSummary("1.4.7", "10-18", "新增免登录漫画源 · 源站动态分类与筛选"),
    ReleaseSummary("1.4.6", "10-18", "互传合并书籍去重 · 原文件缺失与漫画路径识别"),
    ReleaseSummary("1.4.5", "10-18", "附近连接建组与角色修复 · 独立直连握手"),
    ReleaseSummary("1.4.4", "10-18", "互传毛玻璃界面统一 · 连接与 TLS 握手修复"),
    ReleaseSummary("1.4.3", "10-18", "设备互传与便携备份 · 卡片流转动效 · 精简文案"),
    ReleaseSummary("1.4.2", "10-17", "顶部留白压缩 · 发现榜单与状态分类联动"),
    ReleaseSummary("1.4.1", "10-15", "禁漫连接与线路恢复 · 移除热议 · 更新目录补齐"),
    ReleaseSummary("1.4.0", "10-15", "发现聚合与状态筛选 · Hitomi 与爱漫画修复"),
    ReleaseSummary("1.3.11", "10-15", "发现筛选与列表重构 · 分类窗口 · 栏目缓存"),
    ReleaseSummary("1.3.10", "10-14", "发现收藏与刷新 · 原站排序 · 元数据补全"),
    ReleaseSummary("1.3.9", "10-14", "发现设置与分类 · 共用顶栏 · 渐进加载"),
    ReleaseSummary("1.3.8", "10-14", "发现板块 · 爱漫画与 MYCOMIC 阅读链路"),
    ReleaseSummary("1.3.7", "10-13", "发布签名 · 书源响应与线路容错"),
    ReleaseSummary("1.3.6", "10-12", "完整增强 · GPU 加速"),
    ReleaseSummary("1.3.5", "10-12", "极速增强 · 后续预生成"),
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
            PullOutTextButton(onClick = { close {} }) { Text("关闭") }
        })
}
