package com.example.source.keyword

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AniListTitleEntity
import com.example.data.AppDatabase
import com.example.source.anilist.TitleNormalizer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KeywordLocalDataTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun oldVersionTwoDictionaryRebuildsItsShortNameIndexOnUpgrade() = runBlocking {
        val file = File(context.cacheDir,"keyword_dictionary_v1.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            db.execSQL("CREATE TABLE aliases (concept TEXT NOT NULL, raw TEXT NOT NULL, lang TEXT NOT NULL, match_key TEXT NOT NULL, PRIMARY KEY(concept,raw,lang))")
            db.execSQL("CREATE INDEX alias_match ON aliases(match_key)")
            db.execSQL("CREATE TABLE metadata (version TEXT NOT NULL)")
            db.execSQL("INSERT INTO metadata(version) VALUES('old')")
            db.version=2
        }
        assertTrue(KeywordDictionary(context).find("洛琪希").flatMap { it.names }.any { it.text == "roxy migurdia" })
    }

    @Test fun shippedDictionaryResolvesRoxyGivenNameLocallyAndAfterReopen() = runBlocking {
        val names = KeywordDictionary(context).find("洛琪希").flatMap { it.names }
        assertTrue(names.any { it.text == "roxy migurdia" })
        assertTrue(KeywordDictionary(context).find("洛琪希").flatMap { it.names }.containsAll(names))
        assertEquals(emptyList<String>(), KeywordPersonNames.shortForms("洛琪希米格路迪亚"))
        assertEquals(listOf("洛琪希"), KeywordPersonNames.shortForms("洛琪希・米格路迪亚"))
        assertTrue(KeywordPersonNames.shortForms("洛·米格路迪亚").isEmpty())
    }

    @Test fun dictionaryShortNameCollisionIsRejectedButExactNameRemainsUsable() = runBlocking {
        val dictionary = KeywordDictionary(context)
        dictionary.find("初始化")
        android.database.sqlite.SQLiteDatabase.openDatabase(File(context.cacheDir,"keyword_dictionary_v1.db").absolutePath,
            null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            for ((id, full) in listOf("ehtag:character:probe_a" to "测试琪·甲", "ehtag:character:probe_b" to "测试琪·乙")) {
                db.execSQL("INSERT INTO aliases(concept,raw,lang,match_key) VALUES(?,?,?,?)",arrayOf(id,full,"zh",KeywordKeys.match(full)))
                db.execSQL("INSERT INTO name_parts(concept,match_key) VALUES(?,?)",arrayOf(id,KeywordKeys.match("测试琪")))
            }
        }
        assertTrue(dictionary.find("测试琪").isEmpty())
        assertEquals("ehtag:character:probe_a",dictionary.find("测试琪·甲").single().id)
    }

    @Test fun oldCacheKeepsNamesButDoesNotKeepAStaleEmptyLookupDecision() {
        val directory = File(context.cacheDir, "keyword_test_${UUID.randomUUID()}")
        try {
            val cache = KeywordCache(directory)
            val concept = KeywordConcept("bangumi:character:1",listOf(KeywordName("Roxy","en")),"test")
            cache.write("洛琪希",listOf(concept),60_000,listOf(KeywordProviderState("bangumi:characters","empty",Long.MAX_VALUE)))
            val file = directory.listFiles()!!.single()
            val json = org.json.JSONObject(file.readText()).put("version",2)
            file.writeText(json.toString())
            val entry = cache.read("洛琪希")!!
            assertEquals(listOf(concept),entry.concepts)
            assertFalse(entry.fresh)
            assertTrue(entry.states.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun realSeedContainsCommonWordsCharactersCreatorsAndJapaneseForms() {
        val index = LocalKeywordIndex(context.assets.open("keyword_seed.tsv").reader())
        val eye = index.find("眼镜").single()
        assertTrue(eye.names.contains(KeywordName("glasses", "en")))
        assertTrue(eye.names.contains(KeywordName("眼鏡", "ja")))
        assertEquals(eye.id, index.find("戴眼镜").single().id)
        assertTrue(index.find("博丽灵梦").single().names.any { it.text == "Reimu Hakurei" })
        assertTrue(index.find("鸟山明").single().names.any { it.text == "Akira Toriyama" })
        assertTrue(index.find("不存在的测试词条").isEmpty())
    }

    @Test fun persistentCacheSurvivesRecreationAndTracksExpiry() {
        val directory = File(context.cacheDir, "keyword_test_${UUID.randomUUID()}")
        var now = 10_000L
        try {
            val concepts = listOf(KeywordConcept("wiki:1", listOf(KeywordName("Name", "en")), "test"))
            KeywordCache(directory) { now }.write("某中文名", concepts, 1_000)
            assertEquals(concepts, KeywordCache(directory) { now }.read("某中文名")!!.concepts)
            assertTrue(KeywordCache(directory) { now }.read("某中文名")!!.fresh)
            now += 1_001
            assertFalse(KeywordCache(directory) { now }.read("某中文名")!!.fresh)
            assertEquals(concepts, KeywordCache(directory) { now }.read("某中文名")!!.concepts)
            assertNull(KeywordCache(directory) { now }.read("另一个名"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptedCacheFailsOpenWithoutCrashingSearch() {
        val directory = File(context.cacheDir, "keyword_test_${UUID.randomUUID()}")
        try {
            val cache = KeywordCache(directory)
            cache.write("词", emptyList(), 1_000)
            directory.listFiles()!!.single().writeText("not JSON")
            assertNull(cache.read("词"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun titleRankingPrefersExactNameOverSmallerMediaId() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            fun row(id: Int, type: String, text: String) = AniListTitleEntity(
                mediaId = id, titleType = type, rawTitle = text,
                normalizedTitle = TitleNormalizer.normalize(text), compactTitle = TitleNormalizer.compact(text))
            db.anilistDao().insertTitles(listOf(
                row(1, "NATIVE", "测试名称外传"), row(1, "ENGLISH", "Spinoff"),
                row(100, "NATIVE", "测试名称"), row(100, "ENGLISH", "Main Title"),
            ))
            val works = KeywordRepository.titleConcepts(db.anilistDao(), "测试名称")
            assertEquals("anilist:media:100", works.first().id)
            assertTrue(works.first().names.any { it.text == "Main Title" })
        } finally { db.close() }
    }

    @Test fun fullBundledDictionaryReverseLooksUpPlainKeywordsAndSurvivesReopen() = runBlocking {
        val dictionary = KeywordDictionary(context)
        val matches = dictionary.find("眼镜")
        assertTrue(matches.isNotEmpty())
        assertTrue(matches.flatMap { it.names }.any { it.text == "glasses" })
        assertTrue(matches.flatMap { it.names }.none { it.text.startsWith("female:") || it.text.startsWith("male:") })
        assertTrue(KeywordDictionary(context).find("眼镜").flatMap { it.names }.any { it.text == "glasses" })
    }

    @Test fun repositoryUsesCommonAndProperNamesOfflineIncludingMultiTermInput() = runBlocking {
        val prefs = com.example.data.PreferencesManager(context)
        val old = prefs.multiLanguageOnlineLookup
        prefs.multiLanguageOnlineLookup = false
        try {
            val repository = KeywordRepository(context)
            val glasses = repository.expand("眼镜").toList().flatten()
            assertTrue(glasses.containsAll(listOf("glasses", "眼鏡")))
            val character = repository.expand("博丽灵梦").toList().flatten()
            assertTrue(character.contains("Reimu Hakurei"))
            assertTrue(character.contains("博麗霊夢"))
            val combined = repository.expand("红色 眼镜").toList().flatten()
            assertTrue(combined.contains("red glasses"))
            assertTrue(combined.contains("赤 眼鏡"))
        } finally { prefs.multiLanguageOnlineLookup = old }
    }
    @Test fun oregairuChineseDictionaryBridgesRealBundledJapaneseTitleOffline() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            // Use the shipped rows for this actual work, not invented equivalent names.
            val lines = java.util.zip.GZIPInputStream(context.assets.open("anilist_titles.tsv.gzip"))
                .bufferedReader().use { reader -> reader.lineSequence().filter { it.startsWith("70171\t") }.toList() }
            assertTrue(lines.isNotEmpty())
            db.anilistDao().insertTitles(lines.map { line ->
                val fields = line.split('\t')
                AniListTitleEntity(mediaId = fields[0].toInt(), titleType = fields[1], rawTitle = fields[2],
                    normalizedTitle = TitleNormalizer.normalize(fields[2]), compactTitle = TitleNormalizer.compact(fields[2]))
            })
            val chinese = "我的青春恋爱物语果然有问题"
            val dictionary = KeywordDictionary(context).find(chinese)
            assertTrue(dictionary.isNotEmpty())
            assertTrue(dictionary.flatMap { it.names }.any { it.language == "latin" })
            val linked = KeywordRepository.linkTitles(db.anilistDao(), chinese, dictionary)
            val concepts = KeywordConcepts.merge(dictionary + linked)
            val engine = KeywordExpansion({ concepts }, { fail("Must work offline"); emptyList() }, { true })
            val words = engine.expand(chinese).toList().flatten()
            assertTrue(words.contains("やはり俺の青春ラブコメはまちがっている。"))
            assertTrue(words.contains("My Youth Romantic Comedy Is Wrong, As I Expected"))
            assertTrue(words.any { it.startsWith("Yahari Ore") || it.startsWith("yahari ore") })
            assertTrue(words.contains("Oregairu"))
        } finally { db.close() }
    }

    @Test fun dictionaryDoesNotMakeCompleteSeedIncomplete() = runBlocking {
        val seed = LocalKeywordIndex(context.assets.open("keyword_seed.tsv").reader()).find("眼镜")
        val merged = KeywordConcepts.merge(seed + KeywordDictionary(context).find("眼镜"))
        assertTrue(KeywordConcepts.complete(merged))
    }

    @Test fun cachePreservesProviderRetryStateAndCanonicalReverseAliases() {
        val directory = File(context.cacheDir, "keyword_test_${UUID.randomUUID()}")
        try {
            val c = KeywordConcept("bangumi:character:19040", listOf(KeywordName("雪ノ下雪乃", "ja"),
                KeywordName("Yukino Yukinoshita", "en")), "Bangumi")
            val state = KeywordProviderState("wikidata", "empty", 50_000L)
            val cache = KeywordCache(directory) { 10_000L }
            cache.write("雪之下雪乃", listOf(c), 30_000L, listOf(state))
            cache.writeAliases("雪之下雪乃", listOf(c), 30_000L)
            val reopened = KeywordCache(directory) { 10_000L }
            assertEquals(listOf(state), reopened.read("雪之下雪乃")!!.states)
            assertEquals(listOf(c), reopened.read("Yukino Yukinoshita")!!.concepts)
            assertEquals(listOf(c), reopened.read("雪ノ下雪乃")!!.concepts)
        } finally { directory.deleteRecursively() }
    }

}
