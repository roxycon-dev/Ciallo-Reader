package com.example.source.keyword

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.PreferencesManager
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KeywordRepositoryTest {
    @Test fun manualRetryBypassesOldFailureAndReplacesItsStatus() = runBlocking {
        enable()
        val cache = KeywordCache(File(context.filesDir, "keyword_lookup_v2"))
        cache.write(input, emptyList(), 60_000, listOf(
            KeywordProviderState("wikidata", "failed", System.currentTimeMillis() + 60_000, "网络连接超时"),
            KeywordProviderState("bangumi:characters", "failed", System.currentTimeMillis() + 60_000, "HTTP 403")))
        assertEquals("HTTP 403", cache.read(input)!!.states.last().detail)
        val updates = KeywordRepository(context, provider(), titleLookup = { _, _ -> emptyList() })
            .observe(input, forceRefresh = true).toList()
        assertTrue(updates.flatMap { it.added }.contains(native))
        assertEquals("已在线补充并缓存", updates.last().status)
        assertTrue(updates.last().providers.none { it.outcome == "failed" })
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val input = "在线人物阿甲${java.util.UUID.randomUUID()}"
    private val native = "アカの名前"
    private val english = "Aka Online Character"
    private val calls = CopyOnWriteArrayList<String>()

    private fun provider(fail: Boolean = false): OnlineKeywordLookup {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request.url.host + request.url.encodedPath
            val json = when {
                request.url.host == "wiki.test" -> """{"search":[]}"""
                request.url.encodedPath.contains("search/characters") -> """{"data":[{"id":501,"name":"$native"}]}"""
                request.url.encodedPath.endsWith("characters/501") -> """{"name":"$native","infobox":[{"key":"简体中文名","value":"$input"},{"key":"别名","value":[{"k":"英文名","v":"$english"}]}]}"""
                request.url.host == "anilist.test" -> """{"data":{"Page":{"characters":[]}}}"""
                else -> """{"data":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (fail) 500 else 200)
                .message("fixture").body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return OnlineKeywordLookup(client, "https://wiki.test/w/api.php", "https://bgm.test/v0/",
            aniListEndpoint = "https://anilist.test/graphql")
    }

    private fun enable(): PreferencesManager {
        context.getSharedPreferences("keyword_dictionary", Context.MODE_PRIVATE).edit()
            .putLong("checked_at", System.currentTimeMillis()).apply()
        return PreferencesManager(context).apply {
        multiLanguageSearch = true; multiLanguageOnlineLookup = true; incognitoBrowsingEnabled = false
        }
    }

    @Test fun firstOnlineResolutionIsVisibleAndNextRepositoryUsesPersistentCacheOffline() = runBlocking {
        val prefs = enable()
        val updates = KeywordRepository(context, provider(), titleLookup = { _, _ -> emptyList() }).observe(input).toList()
        assertTrue(updates.flatMap { it.added }.containsAll(listOf(native, english)))
        assertEquals("在线", updates.last().origins[KeywordKeys.query(native)])
        assertEquals(calls.toString(), "已在线补充并缓存", updates.last().status)
        assertTrue(calls.any { it.endsWith("/characters/501") })
        val persisted = KeywordCache(File(context.filesDir, "keyword_lookup_v2")).read(input)!!
        assertTrue(persisted.concepts.flatMap { it.names }.any { it.text == native })
        calls.clear()
        prefs.multiLanguageOnlineLookup = false
        val offline = KeywordRepository(context, provider(fail = true), titleLookup = { _, _ -> emptyList() }).observe(input).toList()
        assertTrue(offline.flatMap { it.added }.containsAll(listOf(native, english)))
        assertEquals("已使用缓存", offline.last().status)
        assertTrue(calls.isEmpty())
        val reverse = KeywordRepository(context, provider(fail = true), titleLookup = { _, _ -> emptyList() }).expand(english).toList().flatten()
        assertTrue(reverse.contains(native))
        assertTrue(calls.isEmpty())
    }

    @Test fun freshEnglishOnlyCacheDoesNotBlockJapaneseAndOnlyCompletedProviderIsSkipped() = runBlocking {
        enable()
        val partial = KeywordConcept("bangumi:character:501", listOf(
            KeywordName(input, "zh"), KeywordName(english, "en")), "Bangumi")
        val cache = KeywordCache(File(context.filesDir, "keyword_lookup_v2"))
        cache.write(input, listOf(partial), 60_000, listOf(
            KeywordProviderState("wikidata", "found", System.currentTimeMillis() + 60_000)))
        val updates = KeywordRepository(context, provider(), titleLookup = { _, _ -> emptyList() }).observe(input).toList()
        assertTrue(updates.flatMap { it.added }.contains(native))
        assertFalse(calls.any { it.startsWith("wiki.test") })
        assertTrue(calls.any { it.contains("search/characters") })
        assertTrue(cache.read(input)!!.concepts.flatMap { it.names }.any { it.text == native })
    }

    @Test fun temporaryFailureIsStoredAsRetryStateRatherThanNoSuchName() = runBlocking {
        enable()
        val updates = KeywordRepository(context, provider(fail = true), titleLookup = { _, _ -> emptyList() }).observe(input).toList()
        assertTrue(updates.last().status.contains("失败"))
        val entry = KeywordCache(File(context.filesDir, "keyword_lookup_v2")).read(input)!!
        assertTrue(entry.concepts.isEmpty())
        assertTrue(entry.states.isNotEmpty())
        assertTrue(entry.states.all { it.outcome == "failed" })
        assertTrue(entry.states.all { it.retryAt <= System.currentTimeMillis() + 65_000 })
    }

    @Test fun upgradeRetainsUsefulLegacyCacheInsteadOfLosingKnownNames() = runBlocking {
        val prefs = enable(); prefs.multiLanguageOnlineLookup = false
        val legacy = KeywordCache(File(context.cacheDir, "keyword_lookup_v1"))
        val concept = KeywordConcept("bangumi:character:501", listOf(
            KeywordName(english, "en"), KeywordName(native, "ja")), "Bangumi")
        legacy.write(input, listOf(concept), 60_000)
        val updates = KeywordRepository(context, provider(fail = true), titleLookup = { _, _ -> emptyList() }).observe(input).toList()
        assertTrue(updates.flatMap { it.added }.containsAll(listOf(english, native)))
        assertTrue(calls.isEmpty())
        assertNotNull(KeywordCache(File(context.filesDir, "keyword_lookup_v2")).read(input))
    }

    @Test fun protectingShelfCategoriesWithPinDoesNotDisableEnabledSearchLookup() = runBlocking {
        enable()
        val privacy = context.getSharedPreferences("privacy_prefs", Context.MODE_PRIVATE)
        privacy.edit().putBoolean("privacy_mode_enabled", true).commit()
        try {
            assertTrue(com.example.data.PrivacyManager(context).isEnabled())
            val updates = KeywordRepository(context, provider(), titleLookup = { _, _ -> emptyList() }).observe(input).toList()
            assertTrue(updates.flatMap { it.added }.contains(native))
            assertEquals("已在线补充并缓存", updates.last().status)
            assertTrue(calls.isNotEmpty())
        } finally { privacy.edit().clear().commit() }
    }

    @Test fun globalIncognitoPausesLookupWithAnAccurateReason() = runBlocking {
        val prefs = enable(); prefs.incognitoBrowsingEnabled = true
        try {
            val updates = KeywordRepository(context, provider(), titleLookup = { _, _ -> emptyList() }).observe(input).toList()
            assertEquals("无痕浏览中，在线补充暂停，使用本地关键词", updates.last().status)
            assertTrue(calls.isEmpty())
        } finally { prefs.incognitoBrowsingEnabled = false }
    }

    @Test fun changingOnlinePreferenceIsSeenByTheSameRepositoryOnTheNextSearch() = runBlocking {
        val prefs = enable(); prefs.multiLanguageOnlineLookup = false
        val repository = KeywordRepository(context, provider(), titleLookup = { _, _ -> emptyList() })
        assertEquals("在线补充已关闭，使用本地关键词", repository.observe(input).toList().last().status)
        assertTrue(calls.isEmpty())
        prefs.multiLanguageOnlineLookup = true
        val updates = repository.observe(input).toList()
        assertTrue(updates.flatMap { it.added }.contains(native))
        assertEquals("已在线补充并缓存", updates.last().status)
    }
}
