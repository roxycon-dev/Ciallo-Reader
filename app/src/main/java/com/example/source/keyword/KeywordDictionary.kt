package com.example.source.keyword

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.data.readImportBytes
import com.example.source.SharedHttpTransport
import com.example.source.executeCancellable
import com.example.source.zlibrary.network.SystemProxyResolver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Rebuildable reverse index, isolated from books/favorites; never keeps the full dictionary in RAM. */
internal class KeywordDictionary(private val context: Context) {
    private val gate = Mutex()
    private val helper by lazy { Helper(context) }
    private val settings = context.getSharedPreferences("keyword_dictionary", Context.MODE_PRIVATE)
    private var ready = false
    private val client by lazy { SharedHttpTransport.builder().connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build() }

    private class Helper(context: Context) : SQLiteOpenHelper(context,
        File(context.cacheDir, "keyword_dictionary_v1.db").absolutePath, null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE aliases (concept TEXT NOT NULL, raw TEXT NOT NULL, lang TEXT NOT NULL, match_key TEXT NOT NULL, PRIMARY KEY(concept,raw,lang))")
            db.execSQL("CREATE INDEX alias_match ON aliases(match_key)")
            db.execSQL("CREATE TABLE metadata (version TEXT NOT NULL)")
            createNameParts(db)
        }
        private fun createNameParts(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS name_parts (concept TEXT NOT NULL, match_key TEXT NOT NULL, PRIMARY KEY(concept,match_key))")
            db.execSQL("CREATE INDEX IF NOT EXISTS name_part_match ON name_parts(match_key)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DELETE FROM aliases"); db.execSQL("DELETE FROM metadata")
            createNameParts(db)
            db.execSQL("DELETE FROM name_parts")
        }
    }

    suspend fun find(input: String): List<KeywordConcept> = gate.withLock {
        prepare()
        val db = helper.readableDatabase
        val ids = mutableListOf<String>()
        db.rawQuery("SELECT DISTINCT concept FROM aliases WHERE match_key=? LIMIT 4", arrayOf(KeywordKeys.match(input))).use { c ->
            while (c.moveToNext()) ids += c.getString(0)
        }
        if (ids.isEmpty()) {
            db.rawQuery("SELECT DISTINCT concept FROM name_parts WHERE match_key=? LIMIT 2", arrayOf(KeywordKeys.match(input))).use { c ->
                while (c.moveToNext()) ids += c.getString(0)
            }
            // Same short name across entities needs more context, not a guess.
            if (ids.size > 1) return@withLock emptyList()
        }
        ids.map { id ->
            val names = mutableListOf<KeywordName>()
            db.rawQuery("SELECT raw,lang FROM aliases WHERE concept=?", arrayOf(id)).use { c ->
                while (c.moveToNext()) names += KeywordName(c.getString(0), c.getString(1))
            }
            KeywordConcept(id, names, "EhTagTranslation (CC BY-NC-SA 3.0)")
        }
    }

    private suspend fun prepare() {
        if (ready) return
        val db = helper.writableDatabase
        val exists = db.rawQuery("SELECT COUNT(*) FROM metadata", null).use { it.moveToFirst(); it.getInt(0) > 0 }
        if (!exists) {
            db.beginTransaction()
            try {
                val statement = db.compileStatement("INSERT OR IGNORE INTO aliases(concept,raw,lang,match_key) VALUES(?,?,?,?)")
                statement.use { insert ->
                    GZIPInputStream(context.assets.open("keyword_ehtag.tsv.gzip")).bufferedReader(Charsets.UTF_8).use { reader ->
                        reader.lineSequence().forEach { line ->
                            currentCoroutineContext().ensureActive()
                            val parts = line.split('\t')
                            if (parts.size == 3) put(insert, parts[0], parts[1], parts[2])
                        }
                    }
                }
                val version = context.assets.open("keyword_dictionary_version.txt").bufferedReader().use { it.readText().trim() }
                db.execSQL("INSERT INTO metadata(version) VALUES(?)", arrayOf(version))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            settings.edit().putLong("checked_at", System.currentTimeMillis()).apply()
        }
        ready = true
    }

    private fun put(insert: android.database.sqlite.SQLiteStatement, namespace: String, raw: String, name: String) {
        if (raw.isBlank() || name.isBlank() || raw.length > 160 || name.length > 160) return
        val id = "ehtag:$namespace:$raw"
        // Upstream display names can include decorative emoji, which aren't query words.
        val cleanName = buildString {
            name.codePoints().forEach { point ->
                if (Character.getType(point) != Character.OTHER_SYMBOL.toInt() &&
                    Character.getType(point) != Character.MODIFIER_SYMBOL.toInt() &&
                    point != 0xFE0F && point != 0xFE0E && point != 0x200D) appendCodePoint(point)
            }
        }.trim()
        val names = listOf(KeywordName(raw, if (namespace in setOf("parody", "character", "artist", "group")) "latin" else "en"), KeywordName(cleanName, KeywordKeys.language(cleanName)))
        val shortNames = if (namespace in setOf("character", "artist")) KeywordPersonNames.shortForms(cleanName) else emptyList()
        if (shortNames.isNotEmpty()) {
            helper.writableDatabase.compileStatement("INSERT OR IGNORE INTO name_parts(concept,match_key) VALUES(?,?)").use { part ->
                shortNames.forEach {
                    part.clearBindings(); part.bindString(1, id); part.bindString(2, KeywordKeys.match(it)); part.executeInsert()
                }
            }
        }
        for (n in names.distinct()) {
            if (n.text.isBlank()) continue
            insert.clearBindings()
            insert.bindString(1, id); insert.bindString(2, n.text); insert.bindString(3, n.language)
            insert.bindString(4, KeywordKeys.match(n.text)); insert.executeInsert()
        }
    }

    /** On a missing word, at most one attempt per day, without erasing a usable old version. */
    suspend fun refreshIfDue(allowed: () -> Boolean): Boolean {
        val due = gate.withLock {
            prepare()
            val stamp = System.currentTimeMillis()
            if (!allowed() || stamp - settings.getLong("checked_at", 0) < 24 * 60 * 60 * 1_000L) false
            else { settings.edit().putLong("checked_at", stamp).apply(); true }
        }
        if (!due) return false
        val request = Request.Builder().url("https://raw.githubusercontent.com/EhTagTranslation/DatabaseReleases/master/db.text.json")
            .header("User-Agent", "CialloReader/1.2 (+https://github.com/roxycon-dev/Ciallo-Reader)").build()
        val transport = SystemProxyResolver.resolve(context)?.let { client.newBuilder().proxy(it).build() } ?: client
        transport.newCall(request).executeCancellable().use { response ->
            if (!response.isSuccessful) return false
            val bytes = response.body?.byteStream()?.use { it.readImportBytes(16 * 1024 * 1024) } ?: return false
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.optInt("version") == 7) { "Unsupported keyword dictionary format" }
            val head = root.optJSONObject("head")?.optString("sha").orEmpty()
            val groups = root.getJSONArray("data")
            return gate.withLock {
            val db = helper.writableDatabase
            val current = db.rawQuery("SELECT version FROM metadata LIMIT 1", null).use { if (it.moveToFirst()) it.getString(0) else "" }
            if (head.isNotBlank() && head == current) return@withLock false
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM aliases")
                db.execSQL("DELETE FROM name_parts")
                var count = 0
                db.compileStatement("INSERT OR IGNORE INTO aliases(concept,raw,lang,match_key) VALUES(?,?,?,?)").use { insert ->
                    for (i in 0 until groups.length()) {
                        currentCoroutineContext().ensureActive()
                        if (!allowed()) return@withLock false
                        val group = groups.getJSONObject(i)
                        val namespace = group.getString("namespace")
                        if (namespace in setOf("rows", "temp")) continue
                        val data = group.getJSONObject("data")
                        data.keys().forEach { raw ->
                            put(insert, namespace, raw, data.getJSONObject(raw).optString("name"))
                            count++
                        }
                    }
                }
                require(count in 1_000..100_000) { "Invalid keyword dictionary size" }
                db.execSQL("DELETE FROM metadata")
                db.execSQL("INSERT INTO metadata(version) VALUES(?)", arrayOf(head.ifBlank { "online-${System.currentTimeMillis()}" }))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            true
            }
        }
    }
}
