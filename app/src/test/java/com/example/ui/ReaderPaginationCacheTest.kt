package com.example.ui

import androidx.compose.ui.text.font.FontFamily
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReaderPaginationCacheTest {
    @Before fun reset() = ReaderPaginationCache.clear()
    @After fun cleanup() = ReaderPaginationCache.clear()
    private fun key(text: String, width: Int = 600) = PaginationKey(text, width, 800, 24f, 32f, FontFamily.Default, false, 0)

    @Test fun byteBudgetEvictsOldestButPreservesExactCachedPages() {
        val text = "文".repeat(800_000)
        val a = key(text, 600); val b = key(text, 601); val c = key(text, 602)
        val pages = listOf(text.substring(0, 400_000), text.substring(400_000))
        ReaderPaginationCache.put(a, pages)
        ReaderPaginationCache.put(b, pages)
        assertSame(pages, ReaderPaginationCache.get(a)) // a becomes most recently used
        ReaderPaginationCache.put(c, pages)
        assertNull(ReaderPaginationCache.get(b))
        assertSame(pages, ReaderPaginationCache.get(a))
        assertSame(pages, ReaderPaginationCache.get(c))
        assertTrue(ReaderPaginationCache.retainedBytes() <= ReaderPaginationCache.MAX_BYTES)
    }

    @Test fun oversizedEntryCannotFlushTheUsefulCache() {
        val small = key("正文")
        val pages = listOf("正文")
        ReaderPaginationCache.put(small, pages)
        val huge = "x".repeat(2_200_000)
        ReaderPaginationCache.put(key(huge), listOf(huge))
        assertNull(ReaderPaginationCache.get(key(huge)))
        assertSame(pages, ReaderPaginationCache.get(small))
        ReaderPaginationCache.clear()
        assertEquals(0L, ReaderPaginationCache.retainedBytes())
    }

    @Test fun replacingSameKeyDoesNotDoubleCountAndEntryLimitStillApplies() {
        val first = key("first")
        ReaderPaginationCache.put(first, listOf("first"))
        val size = ReaderPaginationCache.retainedBytes()
        repeat(10) { ReaderPaginationCache.put(first, listOf("first")) }
        assertEquals(size, ReaderPaginationCache.retainedBytes())
        repeat(8) { ReaderPaginationCache.put(key("page$it"), listOf("page$it")) }
        assertNull(ReaderPaginationCache.get(first))
    }
}
