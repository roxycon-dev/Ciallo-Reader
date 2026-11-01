package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class BookImportProgress(val completed: Int, val total: Int, val fileName: String)

data class BookImportReport(val succeeded: Int, val failed: Int, val failures: List<String>) {
    fun message(): String = buildString {
        append("导入完成：成功 $succeeded 本，失败 $failed 本")
        if (failures.isNotEmpty()) append("\n" + failures.joinToString("\n"))
        if (failed > failures.size) append("\n另有 ${failed - failures.size} 本失败，请检查文件后重试")
    }
}

/** Serial processing bounds memory; each file owns its transaction and failures do not abort the batch. */
internal suspend fun <T> importBookBatch(
    items: List<T>,
    onProgress: (BookImportProgress) -> Unit,
    importOne: suspend (T, (String) -> Unit) -> Unit
): BookImportReport {
    var succeeded = 0
    var failed = 0
    val failures = mutableListOf<String>()
    items.distinct().let { files ->
        files.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            var name = "文件 ${index + 1}"
            onProgress(BookImportProgress(index, files.size, name))
            try {
                importOne(item) { resolved ->
                    name = resolved
                    onProgress(BookImportProgress(index, files.size, name))
                }
                succeeded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                if (failures.size < 5) failures += "$name：${e.localizedMessage ?: "未知错误"}"
            }
            onProgress(BookImportProgress(index + 1, files.size, name))
        }
    }
    return BookImportReport(succeeded, failed, failures)
}
