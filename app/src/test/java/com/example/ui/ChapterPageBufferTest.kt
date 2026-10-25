package com.example.ui

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.*
import org.junit.Test

class ChapterPageBufferTest {
    private fun key(width: Int) = PaginationKey("abcdef", width, 800, 24f, 30f, FontFamily.Default, false, 0)

    @Test fun resizeRetainsContentAndRejectsOutOfOrderResults() {
        val old = key(360); val middle = key(600); val latest = key(720)
        val original = listOf("ab", "cd", "ef")
        val buffer = ChapterPageBuffer(old, original)
        buffer.request(middle)
        assertSame(original, buffer.pages)
        buffer.request(latest)
        assertFalse(buffer.publish(middle, listOf("abc", "def")))
        assertSame(original, buffer.pages)
        val ready = listOf("abcdef")
        assertTrue(buffer.publish(latest, ready))
        assertSame(ready, buffer.pages)
        assertFalse(buffer.publish(old, original))
        assertSame(ready, buffer.pages)
    }

    @Test fun returnToAnEarlierSizeAcceptsOnlyItsRequestedLayout() {
        val narrow = key(360); val wide = key(720)
        val buffer = ChapterPageBuffer(narrow, listOf("ab", "cd", "ef"))
        buffer.request(wide)
        buffer.request(narrow)
        assertFalse(buffer.publish(wide, listOf("abcdef")))
        assertTrue(buffer.publish(narrow, listOf("abc", "def")))
    }

    @Test fun anchorUsesTextPositionAcrossPageCountChangesAndBoundaries() {
        assertEquals(0, pageIndexAtOffset(emptyList(), 900))
        assertEquals(0, pageIndexAtOffset(listOf("ab", "cde", "f"), 0))
        assertEquals(1, pageIndexAtOffset(listOf("ab", "cde", "f"), 2))
        assertEquals(2, pageIndexAtOffset(listOf("ab", "cde", "f"), Int.MAX_VALUE))
        assertEquals(0, pageIndexAtOffset(listOf("abcdef"), 5))
    }

    @Test fun indexedAnchorsMatchLinearReferenceForEmptyPagesBoundariesAndRandomOffsets() {
        val random = kotlin.random.Random(42)
        val pages = List(1000) { "文".repeat(random.nextInt(0, 200)) }
        val anchors = ReaderPageAnchors(pages)
        var start = 0
        for (page in pages.indices) {
            assertEquals(start, anchors.startOf(page))
            start += pages[page].length
            assertEquals(pageIndexAtOffset(pages, start), anchors.pageAt(start))
        }
        repeat(10000) {
            val offset = random.nextInt(-1, start + 100)
            assertEquals(pageIndexAtOffset(pages, offset), anchors.pageAt(offset))
        }
        assertEquals(0, ReaderPageAnchors(emptyList()).pageAt(100))
        assertEquals(0, ReaderPageAnchors(emptyList()).startOf(100))
        assertEquals(pages.lastIndex, anchors.pageAt(Int.MAX_VALUE))
    }
}
