package com.example.source.js

import com.example.data.favorite.ChapterReadState
import com.example.data.favorite.ComicProgressEntity
import com.example.data.favorite.ComicReadingLogic
import com.example.source.ComicChapter
import org.junit.Assert.*
import org.junit.Test

class JsChapterOrderTest {
    private fun ch(title: String, volume: String? = null, order: Float = 0f) =
        ComicChapter("book\u0001$volume/$title", title, volume, order)

    private fun titles(chapters: List<ComicChapter>) = chapters.map { it.title }

    @Test fun realManhuarenLilySweetheartCatalogueKeepsItsSpecialGroupAfterTheSerial() {
        val raw = javaClass.getResourceAsStream("/comic-order/manhuaren-lily-sweetheart.tsv")!!
            .bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                    val (group, path, title) = line.split('\t')
                    ComicChapter("book\u0001https://www.manhuaren.com$path", title, group)
                }.toList()
            }
        assertEquals("第26话", raw.first().title)
        val chapters = JsChapterOrder.normalize(raw, "manhuaren")
        assertEquals((1..26).map { "第${it}话" } + "(C88)续篇1夏日甜心", titles(chapters))
        assertEquals("book\u0001https://www.manhuaren.com/m158406/", chapters.first().id)
        assertEquals("book\u0001https://www.manhuaren.com/m482045/", chapters.last().id)
        val navigation = ComicReadingLogic.navigation(chapters, "book\u0001https://www.manhuaren.com/m159156/")
        assertEquals("第1话", navigation.previous?.title)
        assertEquals("第3话", navigation.next?.title)
        assertEquals(raw.map { it.id }.toSet(), chapters.map { it.id }.toSet())
    }

    @Test fun lilySweetheartBothSourcesReadFromFirstEpisodeToLatest() {
        val firstToLatest = (1..6).map { ch("第${it}话") }
        for ((source, raw) in listOf("copy_manga" to firstToLatest, "manhuaren" to firstToLatest.reversed())) {
            val chapters = JsChapterOrder.normalize(raw, source)
            assertEquals(titles(firstToLatest), titles(chapters))
            chapters.forEachIndexed { index, chapter ->
                val navigation = ComicReadingLogic.navigation(chapters, chapter.id)
                assertEquals(chapters.getOrNull(index - 1), navigation.previous)
                assertEquals(chapters.getOrNull(index + 1), navigation.next)
            }
            assertEquals(firstToLatest.first().id, ComicReadingLogic.ordered(chapters).first().chapter.id)
            assertEquals(firstToLatest.last().id, ComicReadingLogic.ordered(chapters).last().chapter.id)
        }
    }

    @Test fun clearAscendingTitlesOverrideManhuarenDefault() {
        val raw = listOf(ch("第1话"), ch("第2话"), ch("第3话"))
        assertEquals(titles(raw), titles(JsChapterOrder.normalize(raw, "manhuaren")))
    }

    @Test fun descendingTitlesOverrideCopyMangaDefaultAndWorkForUnknownSources() {
        val raw = listOf(ch("Chapter 3"), ch("Chapter 2"), ch("Chapter 1"))
        for (source in listOf("copy_manga", "unknown")) {
            assertEquals(titles(raw.reversed()), titles(JsChapterOrder.normalize(raw, source)))
        }
    }

    @Test fun namedEpisodesUseSourceFallback() {
        val raw = listOf(ch("终幕"), ch("相遇"), ch("序章"))
        assertEquals(titles(raw.reversed()), titles(JsChapterOrder.normalize(raw, "manhuaren")))
        assertEquals(titles(raw), titles(JsChapterOrder.normalize(raw, "copy_manga")))
        assertEquals(titles(raw), titles(JsChapterOrder.normalize(raw, "unknown")))
    }

    @Test fun reliableFractionalMetadataTakesPriorityOverTitles() {
        val raw = listOf(ch("第1话", order = 2f), ch("番外", order = 1.5f), ch("第3话", order = 0f))
        val sorted = JsChapterOrder.normalize(raw, "manhuaren")
        assertEquals(listOf(raw[2], raw[1], raw[0]), sorted)
        assertEquals(listOf(0f, 1.5f, 2f), sorted.map { it.order })
    }

    @Test fun missingOrInvalidOrderCannotOverrideChapterDirection() {
        val raw = listOf(ch("第3话", order = 1f), ch("第2话"), ch("第1话"))
        assertEquals(listOf("第1话", "第2话", "第3话"),
            titles(JsChapterOrder.normalize(raw, "copy_manga", hasExplicitOrder = false)))
        assertEquals(listOf("第1话", "第2话", "第3话"),
            titles(JsChapterOrder.normalize(raw.map { it.copy(order = Float.NaN) }, "copy_manga")))
    }

    @Test fun decimalsAndMissingEpisodesAreNotRoundedOrFilled() {
        val raw = listOf(ch("12.5"), ch("12"), ch("7"), ch("0"))
        assertEquals(listOf("0", "7", "12", "12.5"), titles(JsChapterOrder.normalize(raw, "unknown")))
    }

    @Test fun fullWidthAndChineseNumeralsIdentifyDirection() {
        for (raw in listOf(
            listOf(ch("第十二话"), ch("第十一话"), ch("第十话")),
            listOf(ch("第３話"), ch("第２話"), ch("第１話")),
            listOf(ch("ep. 3"), ch("ep. 2"), ch("ep. 1")),
        )) assertEquals(titles(raw.reversed()), titles(JsChapterOrder.normalize(raw, "unknown")))
    }

    @Test fun unrelatedNumbersAndSpecialsDoNotDetermineDirection() {
        val raw = listOf(ch("番外第9话"), ch("特别篇第8话"), ch("2026-10-05 公告"), ch("角色2的故事"))
        assertEquals(titles(raw), titles(JsChapterOrder.normalize(raw, "unknown")))
    }

    @Test fun inconsistentTitlesAreNotNumericallyResorted() {
        val raw = listOf(ch("第4话"), ch("第1话"), ch("第3话"), ch("第2话"))
        assertEquals(titles(raw), titles(JsChapterOrder.normalize(raw, "unknown")))
    }

    @Test fun adjacentPartsAndSpecialPlacementSurviveReversal() {
        val raw = listOf(ch("第3话"), ch("番外"), ch("第2话（上）"), ch("第2话（下）"), ch("第1话"), ch("序章"))
        assertEquals(listOf("序章", "第1话", "第2话（上）", "第2话（下）", "番外", "第3话"),
            titles(JsChapterOrder.normalize(raw, "unknown")))
    }

    @Test fun editionsNormalizeSeparatelyWithoutMovingNamedGroups() {
        val raw = listOf(ch("第3话", "连载"), ch("第2话", "连载"), ch("第1话", "连载"),
            ch("第1卷", "单行本"), ch("第2卷", "单行本"), ch("番外2", "番外"), ch("番外1", "番外"))
        val sorted = JsChapterOrder.normalize(raw, "manhuaren")
        assertEquals(listOf("第1话", "第2话", "第3话", "第1卷", "第2卷", "番外1", "番外2"), titles(sorted))
        assertEquals(raw.map { it.volume }, sorted.map { it.volume })
    }

    @Test fun explicitlyNumberedVolumesNormalizeBeforeTheirEpisodes() {
        val raw = listOf(ch("第2话", "第二卷"), ch("第1话", "第二卷"),
            ch("第2话", "Volume 1"), ch("第1话", "Volume 1"))
        val sorted = JsChapterOrder.normalize(raw, "unknown")
        assertEquals(listOf("Volume 1", "Volume 1", "第二卷", "第二卷"), sorted.map { it.volume })
        assertEquals(listOf("第1话", "第2话", "第1话", "第2话"), titles(sorted))
    }

    @Test fun interleavedGroupsKeepTheirSlots() {
        val raw = listOf(ch("第3话", "A"), ch("第1话", "B"), ch("第2话", "A"),
            ch("第2话", "B"), ch("第1话", "A"))
        val sorted = JsChapterOrder.normalize(raw, "unknown")
        assertEquals(raw.map { it.volume }, sorted.map { it.volume })
        assertEquals(listOf("第1话", "第1话", "第2话", "第2话", "第3话"), titles(sorted))
    }

    @Test fun equalNumbersAndSmallCataloguesRemainValid() {
        assertTrue(JsChapterOrder.normalize(emptyList(), "manhuaren").isEmpty())
        val single = listOf(ch("单话"))
        assertEquals(single, JsChapterOrder.normalize(single, "manhuaren"))
        val duplicate = listOf(ch("第2话（上）"), ch("第2话（下）"))
        assertEquals(titles(duplicate), titles(JsChapterOrder.normalize(duplicate, "manhuaren")))
        assertEquals(listOf("第1话", "第2话"), titles(JsChapterOrder.normalize(listOf(ch("第2话"), ch("第1话")), "unknown")))
    }

    @Test fun normalizationIsIdempotentAndPreservesIdentityAndExternalLinks() {
        val raw = listOf(ch("第3话").copy(external = true, externalUrl = "https://example.test/3"), ch("第2话"), ch("第1话"))
        val sorted = JsChapterOrder.normalize(raw, "manhuaren")
        assertEquals(raw.map { it.copy(order = 0f) }.toSet(), sorted.map { it.copy(order = 0f) }.toSet())
        assertEquals(sorted, JsChapterOrder.normalize(sorted, "manhuaren"))
        assertEquals(listOf(0f, 1f, 2f), sorted.map { it.order })
    }

    @Test fun continueAndNewChapterDetectionUseNormalizedOrderAndStableIds() {
        val raw = (1..4).map { ch("第${it}话") }.reversed()
        val chapters = JsChapterOrder.normalize(raw, "manhuaren")
        val second = raw.first { it.title == "第2话" }
        // This index is from the old, descending catalogue. ID is the progress anchor.
        val progress = ComicProgressEntity("js_manhuaren", "book", second.id, lastChapterIndex = 2,
            lastPageIndex = 3, lastPageCount = 10, seenTopChapterId = second.id, seenChapterCount = 2)
        assertEquals(ComicReadingLogic.ContinueTarget.Resume(1, 3), ComicReadingLogic.resolveContinue(chapters, emptyMap(), progress))
        assertEquals(ComicReadingLogic.ContinueTarget.Next(2), ComicReadingLogic.resolveContinue(chapters,
            mapOf(second.id to ChapterReadState.READ), progress))
        assertEquals(chapters.takeLast(2).map { it.id }.toSet(), ComicReadingLogic.newChapterIds(chapters, progress))
        assertEquals(ComicReadingLogic.ContinueTarget.UpToDate, ComicReadingLogic.resolveContinue(chapters,
            mapOf(chapters.last().id to ChapterReadState.READ), progress.copy(lastChapterId = chapters.last().id)))
    }
}
