package com.example.source.keyword

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Rebuildable metadata only; no migration of the user's books database is needed. */
internal class KeywordCache(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    data class Entry(val concepts: List<KeywordConcept>, val fresh: Boolean,
        val states: List<KeywordProviderState> = emptyList())

    private fun file(input: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("v1:" + KeywordKeys.query(input)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.json")
    }

    @Synchronized fun read(input: String): Entry? = runCatching {
        val target = file(input)
        if (!target.isFile || target.length() > 128 * 1024) return null
        val obj = JSONObject(AtomicFile(target).openRead().bufferedReader().use { it.readText() })
        val version = obj.optInt("version")
        if (version !in 1..3) return null
        val rows = obj.getJSONArray("concepts")
        require(rows.length() <= 16)
        val concepts = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val names = row.getJSONArray("names")
            require(names.length() <= 64)
            KeywordConcept(row.getString("id"), (0 until names.length()).map { j ->
                val n = names.getJSONObject(j)
                KeywordName(n.getString("text").take(160), n.getString("lang"))
            }, row.getString("source"))
        }
        val attempts = obj.optJSONArray("providers")
        val states = if (attempts == null || version < 3) emptyList() else (0 until minOf(attempts.length(), 12)).map { i ->
            val row = attempts.getJSONObject(i)
            KeywordProviderState(row.getString("id"), row.getString("outcome"), row.getLong("retryAt"), row.optString("detail"))
        }
        Entry(concepts, version >= 3 && obj.getLong("expires") > now(), states)
    }.getOrNull()

    @Synchronized fun write(input: String, concepts: List<KeywordConcept>, ttlMs: Long,
        states: List<KeywordProviderState> = emptyList()) {
        directory.mkdirs()
        val rows = JSONArray()
        concepts.take(16).forEach { c ->
            val names = JSONArray()
            c.names.take(64).forEach { n -> names.put(JSONObject().put("text", n.text.take(160)).put("lang", n.language)) }
            rows.put(JSONObject().put("id", c.id).put("source", c.source).put("names", names))
        }
        val providers = JSONArray()
        states.take(12).forEach { providers.put(JSONObject().put("id", it.provider)
            .put("outcome", it.outcome).put("retryAt", it.retryAt).put("detail", it.detail)) }
        val bytes = JSONObject().put("version", 3).put("expires", now() + ttlMs).put("concepts", rows)
            .put("providers", providers)
            .toString().toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(file(input))
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
        // Fixed bound, including stale entries; no growing archive of searched words.
        directory.listFiles().orEmpty().filter { it.extension == "json" }
            .sortedByDescending { it.lastModified() }.drop(512).forEach { it.delete() }
    }

    /** A few canonical aliases reuse the same entity after process restarts. */
    @Synchronized fun writeAliases(input: String, concepts: List<KeywordConcept>, ttlMs: Long) {
        if (concepts.size != 1) return
        val concept = concepts.single()
        concept.names.filter { it.language in setOf("en", "ja", "native", "romaji", "latin") && it.text.length >= 4 }
            .distinctBy { it.language }.take(5).forEach { alias ->
                if (KeywordKeys.query(alias.text) != KeywordKeys.query(input)) {
                    val existing = read(alias.text)
                    if (existing == null || existing.concepts.all { it.id == concept.id }) write(alias.text, concepts, ttlMs)
                }
            }
    }
}
