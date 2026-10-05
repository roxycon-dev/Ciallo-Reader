package com.example.source.keyword

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnlineKeywordLookupTest {
    @Test fun embeddedSearchAliasesAvoidSlowAndUnnecessaryDetailRequests() = runBlocking {
        val details = java.util.concurrent.atomic.AtomicInteger()
        val provider = lookup { request -> when {
            request.url.host == "wiki.test" -> 200 to """{"search":[]}"""
            request.url.encodedPath.contains("search/characters") -> 200 to """{"data":[{"id":19040,"name":"雪ノ下雪乃","infobox":[{"key":"简体中文名","value":"雪之下雪乃"},{"key":"别名","value":[{"k":"英文名","v":"Yukino Yukinoshita"}]}]}]}"""
            request.url.encodedPath.contains("search/") -> 200 to """{"data":[]}"""
            else -> { details.incrementAndGet(); 500 to "{}" }
        } }
        val names = provider.lookup("雪之下雪乃")!!.flatMap { it.names }
        assertTrue(names.contains(KeywordName("Yukino Yukinoshita", "en")))
        assertEquals(0, details.get())
    }

    @Test fun failedSubjectEndpointDoesNotPoisonCharacterRequestsOnTheSameHost() = runBlocking {
        val provider = lookup { request -> when {
            request.url.host == "wiki.test" -> 200 to """{"search":[]}"""
            request.url.encodedPath.contains("subjects") -> 404 to "{}"
            request.url.encodedPath.contains("search/characters") -> 200 to """{"data":[{"id":19040,"name":"雪ノ下雪乃","infobox":[{"key":"简体中文名","value":"雪之下雪乃"},{"key":"别名","value":[{"k":"英文名","v":"Yukino Yukinoshita"}]}]}]}"""
            else -> 200 to """{"data":[]}"""
        } }
        provider.stream("作品", listOf(KeywordConcept("seed:work:x", listOf(KeywordName("作品", "zh")), "local"))).toList()
        val names = provider.lookup("雪之下雪乃")!!.flatMap { it.names }
        assertTrue(names.any { it.text == "雪ノ下雪乃" })
    }

    @Test fun failuresExposeHttpAndTimeoutReasonsInsteadOfAnOpaqueFailure() = runBlocking {
        val provider = lookup { request ->
            if (request.url.host == "wiki.test") throw java.net.SocketTimeoutException("timeout")
            403 to "{}"
        }
        val states = provider.stream("名称", emptyList()).toList().flatMap { it.states }
        assertTrue(states.any { it.detail == "网络连接超时" })
        assertTrue(states.any { it.detail == "HTTP 403" })
    }

    private fun characterRow(id: Int, chinese: String, native: String = "ロキシー", english: String = "Roxy Migurdia") =
        """{"id":$id,"name":"$native","infobox":[{"key":"简体中文名","value":"$chinese"},{"key":"英文名","value":"$english"}]}"""

    @Test fun uniqueChineseGivenNameResolvesWithoutAnArtificialKindHint() = runBlocking {
        val provider = lookup { request -> when {
            request.url.host == "wiki.test" -> 200 to """{"search":[]}"""
            request.url.encodedPath.contains("search/characters") -> 200 to """{"data":[${characterRow(46465, "洛琪希·米格路迪亚·格雷拉特")},${characterRow(2,"洛洛佩琪卡")}]}"""
            else -> 200 to """{"data":[]}"""
        } }
        val result = provider.stream("洛琪希", emptyList()).toList()
        assertTrue(result.flatMap { it.concepts }.flatMap { it.names }.any { it.text == "Roxy Migurdia" })
        assertTrue(result.flatMap { it.states }.any { it.provider == "bangumi:characters" && it.outcome == "found" })
        assertTrue(provider.lookup("米格路迪亚").orEmpty().isEmpty())
        assertTrue(provider.lookup("洛琪").orEmpty().isEmpty())
    }

    @Test fun sharedGivenNameIsAmbiguousInsteadOfSelectingTheFirstSearchHit() = runBlocking {
        val provider = lookup { request -> when {
            request.url.host == "wiki.test" -> 200 to """{"search":[]}"""
            request.url.encodedPath.contains("search/characters") -> 200 to """{"data":[${characterRow(1,"洛琪希·甲","アカ","Aka")},${characterRow(2,"洛琪希·乙","アオ","Ao")}]}"""
            else -> 200 to """{"data":[]}"""
        } }
        val results = provider.stream("洛琪希", emptyList()).toList()
        assertTrue(results.flatMap { it.concepts }.isEmpty())
        assertTrue(results.flatMap { it.states }.any { it.outcome == "ambiguous" })
    }

    @Test fun exactAliasWinsOverASeparatedGivenNameAndWorksDoNotUsePersonShortForms() = runBlocking {
        val provider = lookup { request -> when {
            request.url.host == "wiki.test" -> 200 to """{"search":[]}"""
            request.url.encodedPath.contains("search/") -> 200 to """{"data":[${characterRow(1,"洛琪希")},${characterRow(2,"洛琪希·乙","アオ","Ao")}]}"""
            else -> 200 to """{"data":[]}"""
        } }
        val known = listOf(KeywordConcept("seed:character:x", listOf(KeywordName("洛琪希","zh")), "test"))
        val result = provider.stream("洛琪希",known).toList().flatMap { it.concepts }
        assertEquals(listOf("bangumi:character:1"), result.map { it.id })
        assertNull(KeywordPersonNames.rank(listOf(KeywordName("洛琪希·乙","zh")), setOf(KeywordKeys.match("洛琪希")), false))
    }

    private fun lookup(respond: (okhttp3.Request) -> Pair<Int, String>): OnlineKeywordLookup {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = respond(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").header("Retry-After", "60")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return OnlineKeywordLookup(client, "https://wiki.test/w/api.php", "https://bgm.test/v0/", aniListEndpoint = "https://anilist.test/graphql")
    }

    @Test fun resolvesVerifiedChineseEntityToEnglishAndJapaneseLabels() = runBlocking {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val provider = lookup { request ->
            if (request.url.host == "wiki.test") calls += request.url.queryParameter("action").orEmpty()
            assertTrue(request.header("User-Agent")!!.contains("CialloReader"))
            when (request.url.queryParameter("action")) {
                "wbsearchentities" -> 200 to """{"search":[{"id":"Q1","label":"眼镜","match":{"text":"眼镜"}}]}"""
                "wbgetentities" -> 200 to """{"entities":{"Q1":{"labels":{"zh":{"value":"眼镜"},"en":{"value":"glasses"},"ja":{"value":"眼鏡"}},"aliases":{}}}}"""
                else -> 200 to """{"data":[]}"""
            }
        }
        val result = provider.lookup("眼镜")!!.single()
        assertEquals("wikidata:Q1", result.id)
        assertTrue(result.names.containsAll(listOf(KeywordName("glasses", "en"), KeywordName("眼鏡", "ja"))))
        assertEquals(listOf("wbsearchentities", "wbgetentities"), calls)
    }

    @Test fun searchRankingAloneCannotInventAnEquivalentName() = runBlocking {
        var details = 0
        val provider = lookup { request ->
            when {
                request.url.queryParameter("action") == "wbsearchentities" ->
                    200 to """{"search":[{"id":"Q1","label":"完全不同的名称","match":{"text":"完全不同的名称"}}]}"""
                request.url.host == "bgm.test" -> 200 to """{"data":[]}"""
                else -> { details++; error("Should not fetch unrelated detail") }
            }
        }
        assertTrue(provider.lookup("测试名称")!!.isEmpty())
        assertEquals(0, details)
    }

    @Test fun wikidataFailureFallsBackToBangumiNamesWithoutTranslation() = runBlocking {
        val provider = lookup { request ->
            when {
                request.url.host == "wiki.test" -> 503 to "{}"
                request.url.encodedPath.contains("search/subjects") ->
                    200 to """{"data":[{"id":42,"name":"テスト作品","name_cn":"测试作品"}]}"""
                request.url.encodedPath.contains("search/") || request.url.host == "anilist.test" -> 200 to """{"data":[]}"""
                else -> 200 to """{"name":"テスト作品","name_cn":"测试作品","infobox":[{"key":"别名","value":[{"v":"Test Work"}]}]}"""
            }
        }
        val result = provider.lookup("测试作品")!!.single()
        assertEquals("bangumi:subject:42", result.id)
        assertTrue(result.names.contains(KeywordName("Test Work", "latin")))
        assertTrue(result.names.contains(KeywordName("テスト作品", "ja")))
    }

    @Test fun rateLimitedProvidersAreNotHammeredAndFailureIsNotNegativeEvidence() = runBlocking {
        val counter = java.util.concurrent.atomic.AtomicInteger()
        val provider = lookup { counter.incrementAndGet(); 429 to "{}" }
        assertNull(provider.lookup("未知名字"))
        assertTrue(counter.get() in 2..4)
        val before = counter.get()
        assertNull(provider.lookup("另一个名字"))
        assertEquals(before, counter.get())
    }

    @Test fun malformedResponseSafelyFallsBack() = runBlocking {
        val provider = lookup { request -> if (request.url.host == "wiki.test") 200 to "not JSON" else 200 to """{"data":[]}""" }
        assertNull(provider.lookup("测试名称"))
    }

    @Test fun incompleteWikidataAnswerCanStillSupplementJapaneseFromBangumi() = runBlocking {
        val provider = lookup { request -> when {
            request.url.queryParameter("action") == "wbsearchentities" ->
                200 to """{"search":[{"id":"Q1","label":"测试作品"}]}"""
            request.url.queryParameter("action") == "wbgetentities" ->
                200 to """{"entities":{"Q1":{"labels":{"zh":{"value":"测试作品"},"en":{"value":"Test Work"}},"aliases":{}}}}"""
            request.url.encodedPath.contains("search/subjects") ->
                200 to """{"data":[{"id":42,"name":"テスト作品","name_cn":"测试作品"}]}"""
            request.url.encodedPath.contains("search/") || request.url.host == "anilist.test" -> 200 to """{"data":[]}"""
            else -> 200 to """{"name":"テスト作品","name_cn":"测试作品","infobox":[]}"""
        } }
        val names = provider.lookup("测试作品")!!.flatMap { it.names }
        assertTrue(names.contains(KeywordName("Test Work", "en")))
        assertTrue(names.contains(KeywordName("テスト作品", "ja")))
    }
    @Test fun characterChineseInfoboxAliasesProduceJapaneseEnglishAndRomaji() = runBlocking {
        val provider = lookup { request -> when {
            request.url.host == "wiki.test" -> 200 to """{"search":[]}"""
            request.url.encodedPath.contains("search/characters") -> 200 to """{"data":[{"id":19040,"name":"雪ノ下雪乃"}]}"""
            request.url.encodedPath.endsWith("characters/19040") -> 200 to """{"name":"雪ノ下雪乃","infobox":[{"key":"简体中文名","value":"雪之下雪乃"},{"key":"别名","value":[{"k":"英文名","v":"Yukino Yukinoshita"},{"k":"日文名","v":"雪ノ下 雪乃"},{"k":"罗马字","v":"Yukinoshita Yukino"}]}]}"""
            else -> 200 to """{"data":[]}"""
        } }
        val result = provider.lookup("雪之下雪乃")!!.single()
        assertEquals("character", result.kind)
        assertTrue(result.names.containsAll(listOf(KeywordName("雪ノ下雪乃", "ja"),
            KeywordName("Yukino Yukinoshita", "en"), KeywordName("Yukinoshita Yukino", "romaji"))))
    }

    @Test fun creatorChineseNameProducesKanaAndRomajiWithoutPretendingKanjiIsChineseOnly() = runBlocking {
        val provider = lookup { request -> when {
            request.url.host == "wiki.test" -> 200 to """{"search":[]}"""
            request.url.encodedPath.contains("search/persons") -> 200 to """{"data":[{"id":7567,"name":"渡航"}]}"""
            request.url.encodedPath.endsWith("persons/7567") -> 200 to """{"name":"渡航","infobox":[{"key":"简体中文名","value":"渡航"},{"key":"别名","value":[{"k":"纯假名","v":"わたりわたる"},{"k":"罗马字","v":"Watari Wataru"}]}]}"""
            else -> 200 to """{"data":[]}"""
        } }
        val result = provider.lookup("渡航")!!.single()
        assertTrue(result.names.contains(KeywordName("渡航", "native")))
        assertTrue(result.names.contains(KeywordName("わたりわたる", "ja")))
        assertTrue(result.names.contains(KeywordName("Watari Wataru", "romaji")))
    }

    @Test fun ambiguousCharactersAreNotSilentlyCombined() = runBlocking {
        val provider = lookup { request -> when {
            request.url.host == "wiki.test" -> 200 to """{"search":[]}"""
            request.url.encodedPath.contains("search/characters") -> 200 to """{"data":[{"id":1,"name":"A"},{"id":2,"name":"B"}]}"""
            request.url.encodedPath.contains("characters/") -> 200 to """{"name":"別の名前${request.url.pathSegments.last()}","infobox":[{"key":"简体中文名","value":"同名角色"}]}"""
            else -> 200 to """{"data":[]}"""
        } }
        val updates = provider.stream("同名角色", emptyList()).toList()
        assertTrue(updates.flatMap { it.concepts }.isEmpty())
        assertTrue(updates.flatMap { it.states }.any { it.outcome == "ambiguous" })
    }

    @Test fun knownCharacterAliasBridgesAniListAndRejectsUnrelatedTopHit() = runBlocking {
        val provider = lookup { request -> when {
            request.url.host == "anilist.test" -> 200 to """{"data":{"Page":{"characters":[{"id":99,"name":{"full":"Unrelated Name","native":"別人","alternative":[]}},{"id":42,"name":{"full":"Yukino Yukinoshita","native":"雪ノ下雪乃","alternative":[]}}]}}}"""
            request.url.host == "wiki.test" -> 200 to """{"search":[]}"""
            else -> 200 to """{"data":[]}"""
        } }
        val known = listOf(KeywordConcept("ehtag:character:yukino yukinoshita", listOf(
            KeywordName("雪之下雪乃", "zh"), KeywordName("yukino yukinoshita", "latin")), "local"))
        val names = provider.stream("雪之下雪乃", known).toList().flatMap { it.concepts }
        assertEquals(listOf("anilist:character:42"), names.map { it.id })
        assertTrue(names.single().names.any { it.text == "雪ノ下雪乃" })
    }

    @Test fun providerSpecificCacheSkipsOnlyThatProvider() = runBlocking {
        val hosts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val provider = lookup { request -> hosts += request.url.host; 200 to """{"data":[]}""" }
        provider.stream("缺失日文", emptyList(), setOf("wikidata")).toList()
        assertFalse(hosts.contains("wiki.test"))
        assertTrue(hosts.contains("bgm.test"))
    }

    @Test fun unexpectedSuccessfulJsonShapeIsFailureNotNegativeEvidence() = runBlocking {
        val provider = lookup { 200 to """{"message":"temporarily unavailable"}""" }
        val updates = provider.stream("未收录名字", emptyList()).toList()
        assertTrue(updates.flatMap { it.states }.all { it.outcome == "failed" })
        assertTrue(updates.flatMap { it.concepts }.isEmpty())
    }

}
