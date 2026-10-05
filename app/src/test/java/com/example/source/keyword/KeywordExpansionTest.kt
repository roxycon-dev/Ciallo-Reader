package com.example.source.keyword

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeywordExpansionTest {
    @Test fun enteringIncognitoDuringLookupNeverPublishesTheLateOnlineNames() = runTest {
        var allowed = true
        val engine = KeywordExpansion({ emptyList() }, {
            delay(500); allowed = false
            listOf(concept("late" to "en"))
        }, { allowed }, onlineDisabledReason = { "无痕浏览中，在线补充暂停，使用本地关键词" })
        val updates = engine.observe("缺失名称").toList()
        assertTrue(updates.flatMap { it.added }.isEmpty())
        assertEquals("无痕浏览中，在线补充暂停，使用本地关键词", updates.last().status)
    }

    private fun concept(vararg names: Pair<String, String>) = KeywordConcept("test", names.map { KeywordName(it.first, it.second) }, "test")

    @Test fun chineseAndJapaneseAreNotDeduplicatedAfterCjkFolding() = runTest {
        val engine = KeywordExpansion({ listOf(concept("glasses" to "en", "眼鏡" to "ja", "眼镜" to "zh")) },
            { fail("Complete local mapping must not use network"); emptyList() }, { true })
        assertEquals(listOf(listOf("glasses", "眼鏡")), engine.expand("眼镜").toList())
        assertNotEquals(KeywordKeys.query("眼镜"), KeywordKeys.query("眼鏡"))
        assertNotEquals(KeywordKeys.query("コート"), KeywordKeys.query("コト"))
        assertNotEquals(KeywordKeys.query("name-a"), KeywordKeys.query("namea"))
    }

    @Test fun localNamesArriveBeforeOnlineSupplement() = runTest {
        val events = mutableListOf<Pair<Long, List<String>>>()
        val engine = KeywordExpansion({ listOf(concept("日文の名前" to "ja")) },
            { delay(3_000); listOf(concept("English Name" to "en")) }, { true })
        val job = launch { engine.expand("某个名字").collect { events += currentTime to it } }
        runCurrent()
        assertEquals(0L to listOf("日文の名前"), events.single())
        assertTrue(job.isActive)
        advanceUntilIdle()
        assertEquals(3_000L to listOf("English Name"), events.last())
    }

    @Test fun onlineTimeoutRetainsLocalNamesAndEnds() = runTest {
        val engine = KeywordExpansion({ listOf(concept("known" to "en")) },
            { delay(30_000); listOf(concept("late" to "ja")) }, { true }, onlineWindowMs = 100)
        assertEquals(listOf(listOf("known")), engine.expand("中文").toList())
        assertEquals(100L, currentTime)
    }

    @Test fun onlineDisabledNeverInvokesProvider() = runTest {
        val engine = KeywordExpansion({ listOf(concept("known" to "en")) },
            { fail("Online disabled"); emptyList() }, { false })
        assertEquals(listOf(listOf("known")), engine.expand("中文").toList())
    }

    @Test fun localFailureStillAllowsOnlineMetadata() = runTest {
        val engine = KeywordExpansion({ error("database unavailable") },
            { listOf(concept("glasses" to "en", "眼鏡" to "ja")) }, { true })
        assertEquals(listOf(listOf("glasses", "眼鏡")), engine.expand("眼镜").toList())
    }

    @Test fun wholeTitleWinsOverSplittingAndNeverLosesPunctuation() = runTest {
        val calls = mutableListOf<String>()
        val engine = KeywordExpansion({ calls += it; listOf(concept("Name-A" to "en", "NameA" to "en", "タイトル" to "ja")) },
            { emptyList() }, { false })
        assertEquals(listOf("中文 作品"), calls.also { engine.expand("中文 作品").toList() })
        val words = engine.expand("中文 作品").toList().flatten()
        assertTrue(words.containsAll(listOf("Name-A", "NameA")))
    }

    @Test fun multiTermQueriesStayWholeAndUnknownPartsArePreserved() = runTest {
        val engine = KeywordExpansion({ when (it) {
            "红色" -> listOf(concept("red" to "en", "赤" to "ja"))
            "眼镜" -> listOf(concept("glasses" to "en", "眼鏡" to "ja"))
            else -> emptyList()
        } }, { emptyList() }, { false })
        assertEquals(listOf(listOf("red glasses", "赤 眼鏡")), engine.expand("红色 眼镜").toList())
        assertEquals(listOf(listOf("red 未知名字", "赤 未知名字")), engine.expand("红色 未知名字").toList())
    }

    @Test fun totalBudgetIncludesOriginalAndLanguagesGetFairSlots() = runTest {
        val local = concept(*(1..8).map { "English $it" to "en" }.toTypedArray())
        val engine = KeywordExpansion({ listOf(local) },
            { listOf(concept("日本語" to "ja", "Romaji" to "romaji")) }, { true })
        val batches = engine.expand("中文").toList()
        assertEquals(3, batches.first().size)
        assertEquals(5, batches.flatten().size)
        assertTrue(batches.flatten().contains("日本語"))
        assertTrue(batches.flatten().contains("Romaji"))
    }

    @Test fun failedOnlineLookupReleasesReservedLocalSlots() = runTest {
        val engine = KeywordExpansion({ listOf(concept(*(1..8).map { "Word$it" to "en" }.toTypedArray())) },
            { error("offline") }, { true })
        assertEquals(5, engine.expand("中文").toList().flatten().size)
    }

    @Test fun manualSearchExpressionIsNeverRewritten() = runTest {
        val engine = KeywordExpansion({ fail("Explicit syntax must stay intact"); emptyList() }, { emptyList() }, { true })
        assertTrue(engine.expand("artist:someone -excluded").toList().isEmpty())
    }

    @Test fun cancellingOldExpansionStopsItsOnlineWork() = runTest {
        var cancelled = false
        val engine = KeywordExpansion({ emptyList() }, {
            try { delay(30_000); emptyList() } finally { cancelled = true }
        }, { true })
        val events = mutableListOf<List<String>>()
        val old = launch { engine.expand("旧词").collect(events::add) }
        runCurrent()
        old.cancelAndJoin()
        assertTrue(cancelled)
        assertTrue(events.isEmpty())
    }

    @Test fun onlineToggleDuringLookupDiscardsNewNames() = runTest {
        var allowed = true
        val engine = KeywordExpansion({ emptyList() }, {
            delay(100); allowed = false; listOf(concept("unexpected" to "en"))
        }, { allowed })
        assertTrue(engine.expand("中文").toList().isEmpty())
    }

    @Test fun legacyBuilderKeepsSingleCharacterOriginal() {
        assertEquals(listOf("角", "horns"), com.example.source.anilist.SearchVariantBuilder.build("角", listOf("horns")))
    }
    @Test fun statusRemainsVisibleWhenOnlyOriginalIsUsed() = runTest {
        val engine = KeywordExpansion({ emptyList() }, { emptyList() }, { true })
        val updates = engine.observe("未收录名字").toList()
        assertTrue(updates.any { it.status == "正在在线补充名称" })
        assertTrue(updates.last().status.contains("未找到"))
        assertTrue(updates.all { it.added.isEmpty() })
    }

    @Test fun onlineNamesHaveVisibleOriginAndPersistenceStatus() = runTest {
        val engine = KeywordExpansion({ emptyList() }, { emptyList() }, { true },
            onlineEvents = { _, _ -> kotlinx.coroutines.flow.flow {
                emit(OnlineKeywordResult(listOf(concept("Yukino Yukinoshita" to "en", "雪ノ下雪乃" to "ja")), persisted = true))
            } })
        val updates = engine.observe("雪之下雪乃").toList()
        assertTrue(updates.flatMap { it.added }.contains("雪ノ下雪乃"))
        assertEquals("在线", updates.last().origins[KeywordKeys.query("雪ノ下雪乃")])
        assertEquals("已在线补充并缓存", updates.last().status)
    }

    @Test fun unrelatedCandidatesCannotPretendToHaveCompleteLanguages() = runTest {
        var lookedUp = false
        val engine = KeywordExpansion({ listOf(
            KeywordConcept("work:one", listOf(KeywordName("First Work", "en")), "local"),
            KeywordConcept("work:two", listOf(KeywordName("別の作品", "ja")), "local")) },
            { lookedUp = true; emptyList() }, { true })
        engine.expand("作品名").toList()
        assertTrue(lookedUp)
    }

    @Test fun allMissingPartsResolveConcurrentlyWithoutTwoPartCutoff() = runTest {
        val calls = mutableSetOf<String>()
        val engine = KeywordExpansion({ emptyList() }, { part ->
            calls += part
            delay(if (part == "一") 300L else 10L)
            listOf(concept("word$part" to "en"))
        }, { true }, onlineWindowMs = 500)
        val words = engine.expand("一 二 三 四").toList().flatten()
        assertEquals(setOf("一", "二", "三", "四"), calls)
        assertTrue(words.contains("word一 word二 word三 word四"))
        assertEquals(300L, currentTime)
    }

    @Test fun explicitNativeLabelWinsOverIdenticalChineseAlias() {
        val words = KeywordVariants.prioritized(listOf(
            KeywordName("渡航", "zh"), KeywordName("渡航", "native"), KeywordName("Watari Wataru", "romaji")))
        assertEquals("native", words.first { it.text == "渡航" }.language)
        assertTrue(KeywordConcepts.complete(listOf(KeywordConcept("bangumi:person:7567", words, "test"))))
    }

    @Test fun timeoutStillKeepsAnEarlierPersistedOnlineAnswer() = runTest {
        val engine = KeywordExpansion({ emptyList() }, { emptyList() }, { true }, onlineWindowMs = 100,
            onlineEvents = { _, _ -> kotlinx.coroutines.flow.flow {
                emit(OnlineKeywordResult(listOf(concept("Known Name" to "en")), persisted = true))
                delay(10_000)
            } })
        val updates = engine.observe("中文名字").toList()
        assertTrue(updates.flatMap { it.added }.contains("Known Name"))
        assertEquals("已补充并缓存，部分在线查询超时", updates.last().status)
    }

    @Test fun finalWholeQueriesGetReservedSlotsWhenSeveralPartsFinishAtDifferentTimes() = runTest {
        val engine = KeywordExpansion({ emptyList() }, { part ->
            delay(if (part == "一") 300L else 10L)
            listOf(concept("word$part" to "en", "日本$part" to "ja"))
        }, { true })
        val words = engine.expand("一 二 三 四").toList().flatten()
        assertTrue(words.contains("word一 word二 word三 word四"))
        assertTrue(words.contains("日本一 日本二 日本三 日本四"))
        assertTrue(words.size <= 5)
    }

    @Test fun onlineNamesReleasedFromReservedFinalSlotsStillReportTheirCacheStatus() = runTest {
        val local = KeywordConcept("work:local", listOf(KeywordName("Local One", "en"),
            KeywordName("Local Two", "en"), KeywordName("Local Three", "en")), "local")
        val other = KeywordConcept("work:online", listOf(KeywordName("New Online Alias", "en")), "provider")
        val engine = KeywordExpansion({ listOf(local) }, { emptyList() }, { true },
            onlineEvents = { _, _ -> kotlinx.coroutines.flow.flow {
                emit(OnlineKeywordResult(listOf(other), persisted = true))
            } })
        val updates = engine.observe("中文作品").toList()
        assertEquals(listOf("New Online Alias"), updates.last().added)
        assertEquals("在线", updates.last().origins[KeywordKeys.query("New Online Alias")])
        assertEquals("已在线补充并缓存", updates.last().status)
    }

    @Test fun workWithNativeAndRomajiStillSupplementsMissingEnglishTitle() = runTest {
        var lookedUp = false
        val work = KeywordConcept("seed:work:title", listOf(KeywordName("テスト作品", "ja"),
            KeywordName("Test Sakuhin", "romaji")), "local")
        val engine = KeywordExpansion({ listOf(work) }, {
            lookedUp = true
            listOf(work.copy(names = listOf(KeywordName("English Work Title", "en"))))
        }, { true })
        val words = engine.expand("测试作品").toList().flatten()
        assertTrue(lookedUp)
        assertTrue(words.contains("English Work Title"))
    }

}
