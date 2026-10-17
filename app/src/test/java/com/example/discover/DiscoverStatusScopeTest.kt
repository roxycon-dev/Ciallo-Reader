package com.example.discover

import org.junit.Assert.*
import org.junit.Test

class DiscoverStatusScopeTest {
    @Test fun completedRankingOverridesEveryConflictingSelection() {
        val scope = discoverStatusScope(DiscoverDimension.RANKING, "完结", null, null)
        listOf("全部", "连载", "停更", "状态未知").forEach { assertEquals("完结", scope.effectiveStatus(it)) }
        assertFalse(scope.showStatusChoices)
    }
    @Test fun newRankingClearsThePreviousStatusWithoutClaimingCompletion() {
        val scope = discoverStatusScope(DiscoverDimension.RANKING, "新作", null, null)
        assertEquals("全部", scope.effectiveStatus("完结"))
        assertFalse(scope.completedOnly)
        assertTrue(scope.newWorks)
        assertFalse(scope.showStatusChoices)
    }
    @Test fun nativeFeedAndCategoryTakePrecedenceOverAnInactiveRankingRange() {
        val catalog = DiscoverCatalog(feeds = listOf(DiscoverFeed("new", "新作上架"), DiscoverFeed("end", "完结排行")),
            categories = listOf(DiscoverNativeCategory("serial", "未完结"), DiscoverNativeCategory("end", "已完結")))
        assertTrue(discoverStatusScope(DiscoverDimension.RANKING, "完结", "native", null, catalog).newWorks)
        assertTrue(discoverStatusScope(DiscoverDimension.RANKING, "热门", "end", null, catalog).completedOnly)
        assertFalse(discoverStatusScope(DiscoverDimension.RANKING, "完结", null, "serial", catalog).completedOnly)
        assertTrue(discoverStatusScope(DiscoverDimension.UPDATES, "最新", null, "end", catalog).completedOnly)
    }
    @Test fun negativeCompletionLabelsNeverForceCompletedWorks() {
        listOf("未完结", "未完結", "uncompleted", "incomplete", "unfinished", "not completed", "not yet finished", "连载 / 完结").forEach { label ->
            val scope = discoverStatusScope(DiscoverDimension.UPDATES, "最新", "native", null,
                DiscoverCatalog(feeds = listOf(DiscoverFeed("feed", label))))
            assertFalse(label, scope.completedOnly)
            assertEquals("连载", scope.effectiveStatus("连载"))
        }
    }
    @Test fun ordinaryRankingsAndUpdatesRetainIndependentStatusChoices() {
        for ((dimension, range) in listOf(DiscoverDimension.UPDATES to "最新", DiscoverDimension.RANKING to "热门", DiscoverDimension.RANKING to "飙升")) {
            val scope = discoverStatusScope(dimension, range, null, null)
            assertTrue(scope.showStatusChoices)
            assertEquals("连载", scope.effectiveStatus("连载"))
        }
    }
}
