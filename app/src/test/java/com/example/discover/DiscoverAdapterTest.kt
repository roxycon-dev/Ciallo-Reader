package com.example.discover

import com.example.source.SearchBook
import com.example.source.js.JsDiscoverAdapter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiscoverAdapterTest {
    @Test fun coarseDatesDoNotLetOneSourceFillTheAggregateTopPage() {
        val sameDate = (1..30).map { DiscoverEntry(SearchBook("$it", "a", "A $it", ""), updatedAt = 123L) }
        val other = (1..8).map { DiscoverEntry(SearchBook("$it", "b", "B $it", ""), updatedAt = 456L - it) }
        assertTrue(DiscoverEngine.merge(sameDate + other, DiscoverDimension.UPDATES).take(8).count { it.book.sourceId == "b" } >= 3)
    }
    @Test fun statusChoicesAreSubsetsAndKeepOnlyMatchingReadableVariants() {
        val entries = listOf("ongoing", "completed", null).mapIndexed { i, state ->
            DiscoverEntry(SearchBook("id", "$i", "同一作品", "作者", comicInfo = com.example.source.ComicInfo(status = state))) }
        val all = DiscoverEngine.merge(entries, DiscoverDimension.RANKING)
        for (status in listOf("连载", "完结", "状态未知")) {
            val filtered = filterWorkStatus(all, status)
            assertEquals(all.single().key, filtered.single().key)
            assertTrue(filtered.single().variants.all { serialLabel(it.book.comicInfo?.status) == status })
        }
    }
    @Test fun mappedLanguageAndWorkStatusStayConsistentAcrossSources() {
        assertTrue(DiscoverTaxonomy.matches(listOf("汉语"), setOf("language")))
        assertFalse(DiscoverTaxonomy.matches(listOf("artist:Chinese"), setOf("language")))
        val unknown = DiscoverEntry(SearchBook("1", "a", "作品", "", comicInfo = com.example.source.ComicInfo(status = null)))
        val completed = unknown.copy(book = unknown.book.copy(sourceId = "b", comicInfo = com.example.source.ComicInfo(status = "completed")))
        val ongoing = unknown.copy(book = unknown.book.copy(sourceId = "c", comicInfo = com.example.source.ComicInfo(status = "ongoing")))
        assertEquals("完结", workStatus(DiscoverWork("one", listOf(unknown, completed, ongoing), 0.0)))
        assertEquals("状态未知", workStatus(DiscoverWork("one", listOf(unknown), 0.0)))
        assertEquals("128.6w", cardCompact(1_286_000))
    }
    @Test fun nativeRestrictedLabelsRespectMetadataNamespaces() {
        assertTrue(DiscoverTaxonomy.isAdult(listOf("19r")))
        assertTrue(DiscoverTaxonomy.isAdult(listOf("R-18")))
        assertFalse(DiscoverTaxonomy.isAdult(listOf("author:19r", "角色:R-18")))
        assertFalse(DiscoverTaxonomy.isAdult(listOf("百合", "少女")))
    }
    private val now = DiscoverEngine.parseDate("2026-10-13T10:00:00+08:00")!!
    @Test fun dimensionCardsShowRealCountsAndOrderingOriginWithoutMissingPlaceholders() {
        val row = DiscoverEntry(SearchBook("1", "s", "作品", ""), clicks = 12000, discussions = 3500,
            native = true, nativeOrder = 2, origin = "周阅读榜")
        val work = DiscoverWork("1", listOf(row), 0.0)
        assertTrue(metric(work, DiscoverDimension.CLICKS).contains("1.2万"))
        assertTrue(metric(work, DiscoverDimension.DISCUSSION).contains("3500"))
        assertTrue(metric(work, DiscoverDimension.CLICKS).contains("周阅读榜"))
        val unknown = DiscoverWork("2", listOf(row.copy(clicks = null, discussions = null)), 0.0)
        assertTrue(metric(unknown, DiscoverDimension.CLICKS).contains("原站顺序 #2"))
        assertFalse(metric(unknown, DiscoverDimension.CLICKS).contains("未提供"))
    }
    @Test fun sourceStatusesNormalizeWithoutAssigningUnknownWorksAState() {
        listOf("1", "连载中", "連載", "publishing").forEach { assertEquals("连载", serialLabel(it)) }
        listOf("2", "完結", "finished", "Complete").forEach { assertEquals("完结", serialLabel(it)) }
        assertEquals("状态未知", serialLabel(null))
    }
    @Test fun actualMetricOrderWinsOverAnUnrelatedSourceListPosition() {
        val high = DiscoverEntry(SearchBook("high", "s", "高点击", ""), clicks = 200, nativeOrder = 10)
        val low = DiscoverEntry(SearchBook("low", "s", "低点击", ""), clicks = 100, nativeOrder = 1)
        assertEquals("high", DiscoverEngine.merge(listOf(low, high), DiscoverDimension.CLICKS).first().book.id)
    }
    @Test fun automaticallyMappedCategoriesKeepTheRequestedMetricSort() {
        val page = JsDiscoverAdapter.page(JSONObject("""{"native":true,"entries":[{"id":"low","title":"低点击","clicks":10,"nativeOrder":1},{"id":"high","title":"高点击","clicks":100,"nativeOrder":2}]}"""), "s",
            DiscoverQuery(DiscoverDimension.CLICKS, "周", categoryIds = listOf("genre:adventure")))
        assertFalse(page.entries.first().native)
        assertEquals("high", DiscoverEngine.merge(page.entries, DiscoverDimension.CLICKS).first().book.id)
    }
    private fun parse(json: String, range: String = "最新", feed: String? = null) = JsDiscoverAdapter.page(JSONObject(json), "test",
        DiscoverQuery(DiscoverDimension.UPDATES, range, feedId = feed), now)
    @Test fun latestIncludesUndatedWithoutInventingTimestampOrAuthor() {
        val row = parse("""{"entries":[{"id":"1","title":"漫画","subtitle":"昨天更新 · 12万阅读"}]}""").entries.single()
        assertNull(row.updatedAt); assertNull(row.clicks); assertNull(row.discussions)
        assertFalse(row.book.author == "昨天更新 · 12万阅读")
    }
    @Test fun datedWindowKeepsPrecisionAndCursorWithoutAnUnsupportedBanner() {
        val page = parse("""{"entries":[{"id":"1","title":"漫画"}],"hasMore":true,"nextCursor":"next"}""", "今日")
        assertTrue(page.entries.isEmpty()); assertNull(page.notice); assertEquals("next", page.nextCursor)
    }
    @Test fun nativeBrowseDoesNotApplyUnrelatedDimensionWindow() {
        val row = parse("""{"entries":[{"id":"1","title":"漫画","updatedAt":"2020-01-01","origin":"原站精选"}]}""", "今日", "native").entries.single()
        assertTrue(row.native); assertEquals("原站精选", row.origin)
    }
    @Test fun explicitMetadataWinsAndSyntheticLoginCardsAreRemoved() {
        val rows = parse("""{"entries":[{"id":"__login__","title":"登录"},{"id":"1","title":"漫画","author":"作者","updatedAt":1791856800,"clicks":12000,"tags":["Adventure"]}]}""").entries
        assertEquals(1, rows.size); assertEquals("作者", rows.single().book.author); assertEquals(12000L, rows.single().clicks)
        assertEquals(1791856800000L, rows.single().updatedAt)
    }
    @Test fun broadGenresNeverInventNarrowLeafCategories() {
        assertTrue("action" in DiscoverTaxonomy.classify(listOf("Action")))
        assertFalse("battle" in DiscoverTaxonomy.classify(listOf("Action")))
        assertFalse("magic" in DiscoverTaxonomy.classify(listOf("Fantasy")))
    }
    @Test fun multilingualCompoundTagsFoldAndIgnoreCreatorNamespaces() {
        val ids = DiscoverTaxonomy.classify(listOf("題材：冒險/愛情", "Yaoi", "性轉"))
        assertTrue(ids.containsAll(setOf("adventure", "love", "bl", "gender")))
        assertTrue(DiscoverTaxonomy.classify(listOf("author:Magic", "authors:Magic", "藝術家：百合", "角色：恐怖", "parody:恋爱")).isEmpty())
    }
    @Test fun additionalPagesDoNotIncreaseEarlierRecommendationScores() {
        fun row(n: Int) = DiscoverEntry(SearchBook("$n", "s", "作品$n", "作者"), nativeOrder = n, clicks = 1000L - n)
        val first = DiscoverEngine.merge((1..3).map(::row), DiscoverDimension.CLICKS)
        val appended = DiscoverEngine.merge((1..30).map(::row), DiscoverDimension.CLICKS)
        first.forEach { old -> assertEquals(old.score, appended.first { it.key == old.key }.score, .000001) }
    }
    @Test fun equalMetricValuesShareScoresAndUnknownCountsUseNativeOrder() {
        val rows = listOf(DiscoverEntry(SearchBook("a", "s", "甲", "作者"), clicks = 10, nativeOrder = 1),
            DiscoverEntry(SearchBook("b", "s", "乙", "作者"), clicks = 10, nativeOrder = 2),
            DiscoverEntry(SearchBook("z", "s", "第三", "作者"), nativeOrder = 3),
            DiscoverEntry(SearchBook("c", "s", "第四", "作者"), nativeOrder = 4))
        val works = DiscoverEngine.merge(rows, DiscoverDimension.CLICKS)
        assertEquals(works[0].score, works[1].score, .000001)
        assertEquals("第三", works[2].book.title)
    }
    @Test fun nativeOrderSurvivesIrrelevantMetricValues() {
        val first = DiscoverEntry(SearchBook("1", "s", "精选首位", "作者"), clicks = 1, nativeOrder = 1, native = true)
        val next = DiscoverEntry(SearchBook("2", "s", "精选次位", "作者"), clicks = 10000, nativeOrder = 2, native = true)
        assertEquals("精选首位", DiscoverEngine.merge(listOf(next, first), DiscoverDimension.CLICKS).first().book.title)
    }
    @Test fun personalizationMovesAtMostOnePlaceAndKeepsRankingsExact() {
        val works = (1..5).map { n -> DiscoverWork("$n", listOf(DiscoverEntry(SearchBook("$n", "s", "$n", ""))), 1.0 - n * .02) }
        val personalized = DiscoverEngine.personalize(works, DiscoverDimension.UPDATES) { it.key.toInt() }
        personalized.forEachIndexed { index, work -> assertTrue(kotlin.math.abs(index - works.indexOf(work)) <= 1) }
        assertEquals(works, DiscoverEngine.personalize(works, DiscoverDimension.RANKING) { it.key.toInt() })
    }
    @Test fun unequalPageSizesDoNotSuppressSmallSourceFirstPlace() {
        fun row(source: String, n: Int) = DiscoverEntry(SearchBook("$source$n", source, "$source$n", ""), clicks = 1000L - n, nativeOrder = n)
        val works = DiscoverEngine.merge((1..100).map { row("big", it) } + row("small", 1), DiscoverDimension.CLICKS)
        assertEquals(works.first { it.book.sourceId == "big" }.score, works.first { it.book.sourceId == "small" }.score, .000001)
    }
    @Test fun fractionalAndOverflowStatisticsRemainUnknown() {
        val row = parse("""{"entries":[{"id":"1","title":"漫画","clicks":1.5,"discussions":1e30,"rank":9223372036854775807}]}""").entries.single()
        assertNull(row.clicks); assertNull(row.discussions); assertNull(row.rank)
    }
    @Test fun exactMetricConversionAcceptsLongBoundsWithoutNewPlatformApis() {
        val rows = parse("""{"entries":[{"id":"max","title":"最大值","clicks":9223372036854775807},{"id":"overflow","title":"溢出","clicks":9223372036854775808},{"id":"integer","title":"整数","clicks":1000.0}]}""").entries
        assertEquals(Long.MAX_VALUE, rows[0].clicks)
        assertNull(rows[1].clicks)
        assertEquals(1000L, rows[2].clicks)
    }
    @Test fun invalidDateSuffixesNeverQualifyForTodayAndIsoPrecisionIsRetained() {
        assertNull(DiscoverEngine.parseDate("2026-10-13 invalid"))
        assertNull(DiscoverEngine.parseDate("2026-02-30"))
        assertEquals(DiscoverEngine.parseDate("2026-10-13T08:00:00.123Z"), DiscoverEngine.parseDate("2026-10-13T08:00:00.123456Z"))
    }
    @Test fun realNativeGenreAliasesMapWithoutInventingSpecificThemes() {
        assertTrue(DiscoverTaxonomy.classify(listOf("生活", "體育", "剧情", "四格", "韩漫", "青春")).containsAll(
            setOf("daily", "sports", "drama", "fourpanel", "korea", "youth")))
        assertFalse("inspiring" in DiscoverTaxonomy.classify(listOf("青春")))
        assertFalse("gender" in DiscoverTaxonomy.classify(listOf("伪娘")))
        assertTrue(DiscoverTaxonomy.isAdult(listOf("情色")))
        assertTrue(DiscoverTaxonomy.classify(listOf("2026", "星期一", "FATE")).isEmpty())
    }
}
