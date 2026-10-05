package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.ProxyInfo
import androidx.test.core.app.ApplicationProvider
import com.example.source.keyword.OnlineKeywordLookup
import com.example.source.zlibrary.network.SystemProxyResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import java.io.File

/** Opt-in real API check. The production constructor resolves Android's proxy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [KeywordLookupLiveTest.ProxyConnectivityShadow::class])
class KeywordLookupLiveTest {
    @Implements(ConnectivityManager::class)
    class ProxyConnectivityShadow : ShadowConnectivityManager() {
        companion object { var systemProxy: ProxyInfo? = null }
        @Implementation fun getDefaultProxy(): ProxyInfo? = systemProxy
    }
    @Test fun productionLookupUsesSystemProxyForRealCharacterAndPersonNames() = runBlocking {
        val port = System.getenv("CIALLO_KEYWORD_LIVE_PROXY_PORT")?.toIntOrNull()
        assumeTrue("Live network check must be explicitly enabled", port != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric has no Android connectivity binder; supply only its system proxy getter.
        ProxyConnectivityShadow.systemProxy = ProxyInfo.buildDirectProxy("127.0.0.1", port!!)
        assertNotNull(SystemProxyResolver.resolve(context))
        val provider = OnlineKeywordLookup(context = context)
        val results = JSONArray()
        for ((input, expected) in listOf(
            "雪之下雪乃" to "雪ノ下雪乃",
            "渡航" to "Watari Wataru",
            "洛琪希" to "Roxy Migurdia Greyrat",
        )) {
            val started = System.currentTimeMillis()
            val updates = withContext(Dispatchers.IO) {
                provider.stream(input, emptyList()).toList()
            }
            val names = updates.flatMap { it.concepts }.flatMap { it.names }
            assertTrue("$input: ${updates.flatMap { it.states }}", names.any {
                when (input) {
                    "洛琪希" -> it.text.startsWith("Roxy Migurdia", ignoreCase = true)
                    "渡航" -> it.text in setOf("Watari Wataru", "Wataru Watari")
                    else -> it.text.replace(" ", "") == expected.replace(" ", "")
                }
            })
            results.put(JSONObject().put("query", input).put("elapsedMs", System.currentTimeMillis() - started)
                .put("names", JSONArray(names.map { it.text }.distinct()))
                .put("providers", JSONArray(updates.flatMap { it.states }.map {
                    JSONObject().put("name", it.displayName).put("outcome", it.outcome).put("detail", it.detail)
                })))
        }
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "gradlew.bat").exists() }
        val folder = File(root, "artifacts/multilingual-search-v5").apply { mkdirs() }
        File(folder, "live-api-results.json").writeText(results.toString(2))
    }
    @Test fun realRepositorySupplementsShortNameAndReusesItsCacheOffline() = runBlocking {
        val port = System.getenv("CIALLO_KEYWORD_LIVE_PROXY_PORT")?.toIntOrNull()
        assumeTrue("Live network check must be explicitly enabled", port != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        ProxyConnectivityShadow.systemProxy = ProxyInfo.buildDirectProxy("127.0.0.1",port!!)
        val prefs = com.example.data.PreferencesManager(context).apply {
            multiLanguageSearch=true; multiLanguageOnlineLookup=true; incognitoBrowsingEnabled=false
        }
        context.getSharedPreferences("keyword_dictionary",Context.MODE_PRIVATE).edit()
            .putLong("checked_at",System.currentTimeMillis()).apply()
        val repository = com.example.source.keyword.KeywordRepository(context)
        val updates = withContext(Dispatchers.IO) { repository.observe("洛琪希").toList() }
        val words = updates.flatMap { it.added }
        assertTrue(updates.toString(),words.any { it.startsWith("Roxy Migurdia", ignoreCase = true) })
        val onlineNative = words.firstOrNull { it.startsWith("ロキシー・ミグルディア") }
        assertNotNull(updates.toString(), onlineNative)
        assertTrue(updates.last().status,updates.last().status.contains("补充并缓存"))
        prefs.multiLanguageOnlineLookup=false
        val offline = withContext(Dispatchers.IO) { com.example.source.keyword.KeywordRepository(context).observe("洛琪希").toList() }
        assertTrue(offline.flatMap { it.added }.contains(onlineNative))
        assertEquals("已使用缓存",offline.last().status)
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it,"gradlew.bat").exists() }
        val file=File(root,"artifacts/multilingual-search-v5/live-repository-results.json")
        file.parentFile.mkdirs()
        file.writeText(JSONObject().put("query","洛琪希").put("words",JSONArray(words))
            .put("onlineStatus",updates.last().status).put("offlineStatus",offline.last().status).toString(2))
    }

}
