package com.example.source

import com.example.data.favorite.ComicChapterMatching
import com.example.data.favorite.ComicReadingLogic
import org.junit.Assert.*
import org.junit.Test

class ComicChapterTitleTest {
    @Test fun explicitEpisodeNumbersKeepDecimalsAndNormalizeNumerals() {
        for ((title, expected) in mapOf("第 12 话" to "12", "180.5话 鹰身女妖" to "180.5",
            "Chapter 7" to "7", "ep. 2.5" to "2.5", "第１２．５話" to "12.5",
            "第十二话" to "12", "第一百零二回" to "102", "007" to "7")) {
            assertEquals(title, expected, ComicChapterTitle.numberText(title))
        }
        assertNull(ComicReadingLogic.chapterNumber("180.5话"))
        assertEquals("第 180.5 话", ComicChapterTitle.label("180.5话 鹰身女妖"))
    }

    @Test fun extrasNoticesAndUnrelatedNumbersNeverBecomeEpisodes() {
        for (title in listOf("番外1", "第12话 番外2", "2026-10-18 停更公告", "180话休刊通知",
            "10月18日请假", "2026 停更公告", "Extra 5", "Special Chapter 12", "Bonus 8",
            "特典12", "第13话 延期告知", "Chapter 15 hiatus", "角色2的故事", "新年2026", "序章")) {
            assertNull(title, ComicChapterTitle.number(title))
            assertEquals(title, ComicChapterTitle.label(title))
            val chapter = ComicChapter("extra", title)
            assertNull(ComicReadingLogic.ordered(listOf(chapter)).single().displayNumber)
            assertEquals("继续阅读 · $title", ComicReadingLogic.continueLabel(
                ComicReadingLogic.ContinueTarget.Next(0), listOf(chapter)))
        }
    }

    @Test fun episodeMatchingDoesNotMoveReadStateFromNoticesOrExtras() {
        for (title in listOf("12 停更公告", "第12话休刊通知", "番外12", "12 特典")) {
            assertTrue(title, ComicChapterMatching.mapping(listOf(ComicChapter("a", title)),
                listOf(ComicChapter("b", "Chapter 12"))).isEmpty())
        }
        assertEquals("b", ComicChapterMatching.mapping(listOf(ComicChapter("a", "番外12")),
            listOf(ComicChapter("b", "番外12")))["a"]?.id)
    }

    @Test fun equalExtraNumbersDoNotSuppressNewEpisodes() {
        val top = ComicReadingLogic.ordered(listOf(ComicChapter("b", "第12话"))).single()
        assertTrue(ComicReadingLogic.isNewChapterObserved("a", "番外12", top))
        val decimal = ComicReadingLogic.ordered(listOf(ComicChapter("c", "第12.5话"))).single()
        assertTrue(ComicReadingLogic.isNewChapterObserved("b", "第12话", decimal))
        assertFalse(ComicReadingLogic.isNewChapterObserved("old", "第12.5话", decimal))
    }
}
