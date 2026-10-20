package com.example.ui.favorite

import com.example.ui.components.PullOutTextButton
import com.example.ui.components.PullOutBottomSheet
import com.example.ui.components.pullOutClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.source.ComicSource
import com.example.source.SearchBook
import com.example.source.SourceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 换源候选：在另一个书源里找到的「同一本书」。 */
data class SourceCandidate(
    val sourceId: String,
    val sourceName: String,
    val book: SearchBook,
) {
    val key: String get() = "${sourceId}::${book.id}"
}

/**
 * 换源：按书名在其它已启用书源里找同一本书。
 *
 * 只做「标题归一化后的相等 / 包含」匹配，不猜章节、不做模糊语义匹配 ——
 * 命中结果交给用户确认，避免误迁移到同名不同版本的书。
 */
object ComicSourceMigration {

    /** 归一化：去掉标点、空格、大小写差异（保留 CJK 与字母数字）。 */
    fun normalize(title: String): String =
        title.filter { it.isLetterOrDigit() }.lowercase()

    /**
     * @param title 当前书名
     * @param excludeSourceId 当前书源（排除掉）
     * @param sources 已启用的漫画书源
     * @param maxSources 最多并发查几个源（避免被封）
     */
    suspend fun findCandidates(
        title: String,
        excludeSourceId: String,
        sources: List<ComicSource>,
        maxSources: Int = 5,
    ): List<SourceCandidate> = withContext(Dispatchers.IO) {
        val want = normalize(title)
        if (want.isBlank()) return@withContext emptyList()
        val gate = Semaphore(3)
        coroutineScope {
            sources
                .filter { it.id != excludeSourceId }
                .take(maxSources)
                .map { source ->
                    async {
                        gate.withPermit {
                            runCatching {
                                when (val r = source.search(title)) {
                                    is SourceResult.Success -> {
                                        // 同名优先，其次包含
                                        val exact = r.data.firstOrNull { normalize(it.title) == want }
                                        val loose = r.data.firstOrNull {
                                            val n = normalize(it.title)
                                            n.isNotBlank() && (n.contains(want) || want.contains(n))
                                        }
                                        (exact ?: loose)?.let {
                                            SourceCandidate(source.id, source.name, it)
                                        }
                                    }
                                    is SourceResult.Error -> null
                                }
                            }.getOrNull()
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
                .sortedBy { it.sourceName }
        }
    }
}

/**
 * 换源确认 Sheet：列出候选来源，用户确认后才迁移收藏 / 进度 / 已读状态。
 * 「换源」是破坏性操作（旧键会被清理），因此必须由用户显式选择，不自动执行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceMigrateSheet(
    title: String,
    candidates: List<SourceCandidate>,
    loading: Boolean,
    onPick: (SourceCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    PullOutBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "换个书源继续看",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "《$title》\n选择来源后，收藏、阅读进度与已读状态会按章节顺序迁移过去。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(14.dp))

            when {
                loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                }
                candidates.isEmpty() -> {
                    Text(
                        text = "其它书源里没找到同名作品，可先在书库搜索确认。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(candidates, key = { it.key }) { c ->
                            SourceCandidateRow(candidate = c, onClick = { onPick(c) })
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            PullOutTextButton(onClick = com.example.ui.components.paperDismiss(onDismiss), modifier = Modifier.align(Alignment.End)) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun SourceCandidateRow(
    candidate: SourceCandidate,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pullOutClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.sourceName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = candidate.book.title,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "迁移到该来源",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
