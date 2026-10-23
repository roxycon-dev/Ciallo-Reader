package com.example.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReaderChapterWindowTest {
    private val physical = (0..5).map { Chapter(bookId = 1, chapterOrder = it, title = "章$it", content = "正文$it") }
    private val mapping = ChapterMerger.buildLogicalChapters(physical.map { it.copy(content = "") })

    @Test fun sequentialTurnReadsOnlyNewChapterAndKeepsExistingTextInstances() = runBlocking {
        val initial = loadReaderChapterWindow(mapping, 1, emptyList(), emptySet()) { orders -> physical.filter { it.chapterOrder in orders } }
        var requested = emptyList<Int>()
        val next = loadReaderChapterWindow(mapping, 2, initial.chapters, initial.loaded) { orders ->
            requested = orders
            physical.filter { it.chapterOrder in orders }
        }
        assertEquals(listOf(3), requested)
        assertEquals(setOf(1, 2, 3), next.loaded)
        assertSame(initial.chapters[1], next.chapters[1])
        assertSame(initial.chapters[2], next.chapters[2])
        assertEquals("", next.chapters[0].content)
        assertEquals("正文3", next.chapters[3].content)
    }

    @Test fun jumpDropsDistantTextAndLoadsOnlyTheNewWindow() = runBlocking {
        val initial = loadReaderChapterWindow(mapping, 1, emptyList(), emptySet()) { orders -> physical.filter { it.chapterOrder in orders } }
        val next = loadReaderChapterWindow(mapping, 5, initial.chapters, initial.loaded) { orders ->
            assertEquals(listOf(4, 5), orders)
            physical.filter { it.chapterOrder in orders }
        }
        assertEquals(setOf(4, 5), next.loaded)
        assertTrue(next.chapters.take(4).all { it.content.isEmpty() })
    }

    @Test fun splitChapterPartsPreserveExactTextAndMissingNeighborIsRetried() = runBlocking {
        val parts = listOf(physical[0], physical[1].copy(title = "章0 (续2)"), physical[2])
        val logical = ChapterMerger.buildLogicalChapters(parts.map { it.copy(content = "") })
        val first = loadReaderChapterWindow(logical, 0, emptyList(), emptySet()) { parts.take(2) }
        assertEquals("正文0正文1", first.chapters[0].content)
        assertEquals(setOf(0), first.loaded)
        val next = loadReaderChapterWindow(logical, 1, first.chapters, first.loaded) { orders ->
            assertEquals(listOf(2), orders)
            parts.takeLast(1)
        }
        assertSame(first.chapters[0], next.chapters[0])
        assertEquals(setOf(0, 1), next.loaded)
    }

    @Test fun genuinelyEmptyChapterIsStillReused() = runBlocking {
        val only = ChapterMerger.buildLogicalChapters(listOf(physical[0].copy(content = "")))
        val first = loadReaderChapterWindow(only, 0, emptyList(), emptySet()) { listOf(physical[0].copy(content = "")) }
        val next = loadReaderChapterWindow(only, 0, first.chapters, first.loaded) { error("Already loaded empty chapter must not be read again") }
        assertEquals(setOf(0), next.loaded)
    }

    @Test fun incompleteCurrentChapterFailsInsteadOfPublishingTruncatedText() = runBlocking {
        val parts = listOf(physical[0], physical[1].copy(title = "章0 (续2)"))
        val logical = ChapterMerger.buildLogicalChapters(parts.map { it.copy(content = "") })
        try {
            loadReaderChapterWindow(logical, 0, emptyList(), emptySet()) { parts.take(1) }
            fail("Missing current chapter part must fail")
        } catch (_: IllegalStateException) { }
    }
}
