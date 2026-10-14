package com.example.discover

import com.example.source.SearchBook
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class DiscoverEngineTest {
    private fun entry(source: String, title: String, clicks: Long, author: String = "作者") = DiscoverEntry(
        SearchBook(title, source, title, author), clicks = clicks)
    @Test fun aggregateDeduplicatesWithoutRepeatingSourceVotes() {
        val a = entry("a", "同一作品", 100)
        val b = entry("b", "同一作品", 5)
        val works = DiscoverEngine.merge(listOf(a, a, b), DiscoverDimension.CLICKS)
        assertEquals(1, works.size)
        assertEquals(2, works.first().variants.size)
        assertEquals(1.0, works.first().score, .0001)
    }
    @Test fun trafficScaleDoesNotSuppressSmallSites() {
        val works = DiscoverEngine.merge(listOf(entry("big", "大站首位", 9_000_000), entry("big", "大站次位", 8_000_000),
            entry("small", "小站首位", 10), entry("small", "小站次位", 5)), DiscoverDimension.CLICKS)
        assertEquals(works.first { it.book.title == "大站首位" }.score, works.first { it.book.title == "小站首位" }.score, .0001)
        assertTrue(works.first { it.book.title == "小站首位" }.score > works.first { it.book.title == "大站次位" }.score)
    }
    @Test fun dimensionOrdersAreIndependent() {
        val a = entry("a", "甲", 100).copy(discussions = 5, updatedAt = 500, rank = 2)
        val b = entry("a", "乙", 5).copy(discussions = 100, updatedAt = 100, rank = 1)
        assertEquals("甲", DiscoverEngine.merge(listOf(a,b), DiscoverDimension.CLICKS).first().book.title)
        assertEquals("乙", DiscoverEngine.merge(listOf(a,b), DiscoverDimension.DISCUSSION).first().book.title)
        assertEquals("甲", DiscoverEngine.merge(listOf(a,b), DiscoverDimension.UPDATES).first().book.title)
        assertEquals("乙", DiscoverEngine.merge(listOf(a,b), DiscoverDimension.RANKING).first().book.title)
    }
    @Test fun sameNameDifferentAuthorsRemainSeparate() {
        assertEquals(2, DiscoverEngine.merge(listOf(entry("a", "同名", 2, "作者甲"), entry("b", "同名", 2, "作者乙")), DiscoverDimension.CLICKS).size)
    }
    @Test fun aliasesMergeButConflictingTrustedIdsDoNot() {
        val a = entry("a", "星海", 12).copy(book = entry("a", "星海", 12).book.copy(comicInfo = com.example.source.ComicInfo(alternateTitles = listOf("Star Sea"))))
        val b = entry("b", "Star Sea", 3)
        assertEquals(1, DiscoverEngine.merge(listOf(a,b), DiscoverDimension.CLICKS).size)
        assertEquals(2, DiscoverEngine.merge(listOf(a.copy(canonicalId = "work:1"), b.copy(canonicalId = "work:2")), DiscoverDimension.CLICKS).size)
    }
    @Test fun editionsOnOneSourceNeverReceiveExtraVotes() {
        val a = entry("a", "同名", 12)
        val works = DiscoverEngine.merge(listOf(a, a.copy(book = a.book.copy(id = "edition")), entry("b", "另一作品", 5)), DiscoverDimension.CLICKS)
        assertEquals(.5, works.first { it.book.title == "同名" }.score, .0001)
        assertEquals(1, works.first { it.book.title == "同名" }.variants.size)
    }
    @Test fun singleSourceDoesNotMergeDifferentEditions() {
        val a = entry("a", "同名", 2)
        assertEquals(2, DiscoverEngine.merge(listOf(a, a.copy(book = a.book.copy(id = "edition"))), DiscoverDimension.CLICKS, false).size)
    }
    @Test fun missingCountsStayMissingAndNumbersUseChineseUnits() {
        assertEquals("未提供", DiscoverEngine.compact(null))
        assertEquals("1.2万", DiscoverEngine.compact(12_345))
        assertEquals("1.2亿", DiscoverEngine.compact(123_456_789))
    }
    @Test fun hierarchyAndCombinationsFollowFacetSemantics() {
        assertTrue(DiscoverTaxonomy.matches(listOf("action", "romance"), setOf("action", "magic", "love")))
        assertFalse(DiscoverTaxonomy.matches(listOf("action"), setOf("action", "love")))
        assertTrue(DiscoverTaxonomy.matches(listOf("magic"), setOf("genre")))
        assertTrue(DiscoverTaxonomy.matches(listOf("未知标签"), emptySet()))
        assertTrue(DiscoverTaxonomy.isAdult(listOf("hentai")))
        assertTrue(DiscoverTaxonomy.isAdult(listOf("内容：hentai")))
        assertFalse(DiscoverTaxonomy.isAdult(listOf("boys love")))
    }
    @Test fun taxonomyHasUniqueIdsAndAtMostThreeLevels() {
        val ids = DiscoverTaxonomy.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        DiscoverTaxonomy.roots.forEach { it.children.forEach { second -> second.children.forEach { third -> assertTrue(third.children.isEmpty()) } } }
    }
    @Test fun derivedThemeTextRetainsContrastInLightAndDarkCards() {
        val colors = listOf(androidx.compose.ui.graphics.Color.Yellow, androidx.compose.ui.graphics.Color.Green,
            androidx.compose.ui.graphics.Color.Cyan, androidx.compose.ui.graphics.Color.Magenta)
        for (background in listOf(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.Black)) {
            colors.forEach { primary -> assertTrue(contrast(themeText(primary, background), background) >= 4.5f) }
        }
    }
    @Test fun localCalendarWindowsCrossMidnightAndStartOnMonday() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).apply { timeZone = zone }.parse("2026-10-13 00:30")!!.time
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).apply { timeZone = zone }
        assertEquals("2026-10-13 00:00", fmt.format(java.util.Date(DiscoverEngine.rangeStart("今日", now, zone)!!)))
        assertEquals("2026-10-11 00:00", fmt.format(java.util.Date(DiscoverEngine.rangeStart("近 3 天", now, zone)!!)))
        assertEquals("2026-10-12 00:00", fmt.format(java.util.Date(DiscoverEngine.rangeStart("本周", now, zone)!!)))
    }
}
