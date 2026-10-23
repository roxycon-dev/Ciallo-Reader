package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ReaderChapterWindow(val chapters: List<Chapter>, val loaded: Set<Int>)

/** Keep the same three-chapter window while reading only its newly entered chapters. */
internal suspend fun loadReaderChapterWindow(
    mapping: LogicalChapterBook,
    currentIndex: Int,
    previous: List<Chapter>,
    loaded: Set<Int>,
    readParts: suspend (List<Int>) -> List<Chapter>,
): ReaderChapterWindow = withContext(Dispatchers.Default) {
    val metadata = mapping.chapters
    check(currentIndex in metadata.indices) { "没有可读取的章节" }
    val targets = ((currentIndex - 1)..(currentIndex + 1)).filter { it in metadata.indices }
    val reusable = targets.filter { it in loaded && it in previous.indices }.toSet()
    val missing = targets.filterNot { it in reusable }
    val orders = missing.flatMap { mapping.logicalToPhysicalOrders[it].asIterable() }.distinct()
    val parts = if (orders.isEmpty()) emptyMap() else readParts(orders).associateBy { it.chapterOrder }
    val complete = reusable + missing.filter { index -> mapping.logicalToPhysicalOrders[index].all { it in parts } }
    check(currentIndex in complete) { "当前章节数据缺失，请重试加载" }
    val chapters = metadata.toMutableList()
    for (index in targets) {
        ensureActive()
        if (index in reusable) {
            chapters[index] = previous[index]
        } else {
            val content = mapping.logicalToPhysicalOrders[index].asIterable().mapNotNull { parts[it] }
            if (content.isNotEmpty()) chapters[index] = metadata[index].copy(
                content = content.joinToString(separator = "") { it.content },
            )
        }
    }
    ReaderChapterWindow(chapters, complete)
}
