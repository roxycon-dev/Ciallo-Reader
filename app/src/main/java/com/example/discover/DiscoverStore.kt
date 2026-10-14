package com.example.discover

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.source.ComicInfo
import com.example.source.SearchBook
import org.json.JSONArray
import org.json.JSONObject

class DiscoverStore(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("discover_preferences", Context.MODE_PRIVATE)
    val cache: SharedPreferences = context.getSharedPreferences("discover_cache", Context.MODE_PRIVATE)
    fun string(key: String, default: String) = prefs.getString(key, default) ?: default
    fun flag(key: String, default: Boolean = true) = prefs.getBoolean(key, default)
    fun selection(key: String) = prefs.getStringSet(key, emptySet()).orEmpty().toSet()
    fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun put(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    fun put(key: String, value: Set<String>) { prefs.edit().putStringSet(key, value).apply() }
    fun block(work: DiscoverWork) {
        val identities = setOf(work.key) + work.variants.map { DiscoverEngine.identity(it) }
        val groups = runCatching { JSONObject(string("blocked_groups", "{}")) }.getOrDefault(JSONObject())
        groups.put(work.key, JSONArray(identities.toList()))
        val labels = runCatching { JSONObject(string("blocked_labels", "{}")) }.getOrDefault(JSONObject())
        labels.put(work.key, work.book.title)
        prefs.edit().putStringSet("blocked", selection("blocked") + identities)
            .putString("blocked_groups", groups.toString()).putString("blocked_labels", labels.toString()).apply()
    }
    fun unblock(key: String) {
        val groups = runCatching { JSONObject(string("blocked_groups", "{}")) }.getOrDefault(JSONObject())
        val aliases = groups.optJSONArray(key)
        val removed = setOf(key) + (0 until (aliases?.length() ?: 0)).map { aliases!!.getString(it) }
        groups.remove(key)
        val labels = runCatching { JSONObject(string("blocked_labels", "{}")) }.getOrDefault(JSONObject())
        labels.remove(key)
        val retained = (groups.keys().asSequence().toList()).flatMap { group ->
            val list = groups.optJSONArray(group) ?: JSONArray()
            (0 until list.length()).map { list.getString(it) }
        }.toSet()
        prefs.edit().putStringSet("blocked", (selection("blocked") - removed) + retained)
            .putString("blocked_groups", groups.toString()).putString("blocked_labels", labels.toString()).apply()
    }
    fun blockedWorks(): List<Pair<String, String>> {
        val labels = runCatching { JSONObject(string("blocked_labels", "{}")) }.getOrDefault(JSONObject())
        val groups = runCatching { JSONObject(string("blocked_groups", "{}")) }.getOrDefault(JSONObject())
        val grouped = groups.keys().asSequence().toList().flatMap { key ->
            val aliases = groups.optJSONArray(key) ?: JSONArray()
            (0 until aliases.length()).map { aliases.getString(it) }
        }.toSet()
        return labels.keys().asSequence().map { it to labels.getString(it) }.toList() +
            (selection("blocked") - grouped).map { it to it.removePrefix("title:").substringBefore('|') }
    }
    fun clearBlocked() { prefs.edit().remove("blocked").remove("blocked_groups").remove("blocked_labels").apply() }
    fun readCache(key: String): List<DiscoverEntry> = runCatching {
        val json = JSONObject(cache.getString(key, "{}") ?: "{}")
        if (System.currentTimeMillis() - json.optLong("savedAt") > string("cache_hours", "24").toLong() * 3_600_000) return@runCatching emptyList()
        val rows = json.optJSONArray("entries") ?: JSONArray()
        (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val tags = row.optJSONArray("tags") ?: JSONArray()
            val aliases = row.optJSONArray("aliases") ?: JSONArray()
            fun long(name: String): Long? = if (row.has(name) && !row.isNull(name)) row.getLong(name) else null
            DiscoverEntry(SearchBook(row.getString("id"), row.getString("source"), row.getString("title"), row.optString("author"),
                row.optString("cover").ifBlank { null }, format = "漫画", comicId = row.getString("id"),
                comicInfo = ComicInfo(tags = (0 until tags.length()).map { tags.getString(it) },
                    alternateTitles = (0 until aliases.length()).map { aliases.getString(it) }, status = row.optString("status").ifBlank { null })),
                updatedAt = long("updatedAt"), latestChapter = row.optString("latestChapter").ifBlank { null },
                discussions = long("discussions"), clicks = long("clicks"), rank = long("rank")?.toInt(),
                rankChange = long("rankChange")?.toInt(), growth = if (row.has("growth")) row.optDouble("growth") else null,
                newEntry = row.optBoolean("newEntry"), hotComment = row.optString("hotComment").ifBlank { null },
                canonicalId = row.optString("canonicalId").ifBlank { null }, nativeOrder = long("nativeOrder")?.toInt(),
                origin = row.optString("origin").ifBlank { null }, subtitle = row.optString("subtitle").ifBlank { null }, native = row.optBoolean("native"))
        }
    }.getOrDefault(emptyList())
    fun writeCache(key: String, entries: List<DiscoverEntry>) {
        if (!flag("cache")) return
        val rows = JSONArray()
        entries.take(300).forEach { e -> rows.put(JSONObject().put("id", e.book.id).put("source", e.book.sourceId)
            .put("title", e.book.title).put("author", e.book.author).put("cover", e.book.cover.orEmpty())
            .put("tags", JSONArray(e.book.comicInfo?.tags.orEmpty())).put("aliases", JSONArray(e.book.comicInfo?.alternateTitles.orEmpty())).put("status", e.book.comicInfo?.status.orEmpty())
            .put("updatedAt", e.updatedAt).put("latestChapter", e.latestChapter).put("discussions", e.discussions)
            .put("clicks", e.clicks).put("rank", e.rank).put("rankChange", e.rankChange).put("growth", e.growth)
            .put("newEntry", e.newEntry).put("hotComment", e.hotComment).put("canonicalId", e.canonicalId)
            .put("nativeOrder", e.nativeOrder).put("origin", e.origin).put("subtitle", e.subtitle).put("native", e.native)) }
        val editor = cache.edit()
        if (cache.all.size >= 24 && !cache.contains(key)) cache.all.keys.firstOrNull()?.let { editor.remove(it) }
        editor.putString(key, JSONObject().put("savedAt", System.currentTimeMillis()).put("entries", rows).toString()).apply()
    }
}

@Composable
fun rememberDiscoverStore(): Pair<DiscoverStore, Int> {
    val context = LocalContext.current
    val store = remember(context) { DiscoverStore(context.applicationContext) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return store to revision
}
