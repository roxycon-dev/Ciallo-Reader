package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BookImportBatchTest {
    @Test fun continuesAfterFailureAndDeduplicatesSelections() = runBlocking {
        val processed = mutableListOf<Int>()
        val progress = mutableListOf<BookImportProgress>()
        val report = importBookBatch(listOf(1, 2, 1, 3), progress::add) { item, name ->
            name("book$item.txt")
            processed += item
            if (item == 2) error("文件损坏")
        }
        assertEquals(listOf(1, 2, 3), processed)
        assertEquals(2, report.succeeded)
        assertEquals(1, report.failed)
        assertTrue(report.message().contains("book2.txt：文件损坏"))
        assertEquals(BookImportProgress(3, 3, "book3.txt"), progress.last())
    }
    @Test fun cancellationStopsRemainingImports() = runBlocking {
        val processed = mutableListOf<Int>()
        try {
            importBookBatch(listOf(1, 2, 3), {}) { item, _ ->
                processed += item
                if (item == 2) throw CancellationException("取消")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf(1, 2), processed)
    }
    @Test fun emptySelectionDoesNothing() = runBlocking {
        val result = importBookBatch(emptyList<Int>(), { error("No progress expected") }) { _, _ -> error("No import expected") }
        assertEquals(BookImportReport(0, 0, emptyList()), result)
    }
    @Test fun allFailuresAreCountedButDetailsAreBounded() = runBlocking {
        val result = importBookBatch((1..20).toList(), {}) { _, _ -> error("拒绝读取") }
        assertEquals(20, result.failed)
        assertEquals(5, result.failures.size)
        assertTrue(result.message().contains("另有 15 本失败"))
    }
}
