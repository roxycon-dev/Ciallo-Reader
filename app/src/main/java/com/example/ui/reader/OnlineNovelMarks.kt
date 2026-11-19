package com.example.ui.reader

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal data class OnlineNovelMark(val id: String, val mark: ReaderMark, val text: String, val note: String)

internal class OnlineNovelMarks(context: Context, book: String, chapter: String) {
    private val storage = context.getSharedPreferences("online_reader_marks", Context.MODE_PRIVATE)
    private val key = MessageDigest.getInstance("SHA-256").digest((book + "\u0000" + chapter).toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun load(): List<OnlineNovelMark> = runCatching {
        val array = JSONArray(storage.getString(key, "[]"))
        List(minOf(array.length(), 100)) { index ->
            val item = array.getJSONObject(index)
            OnlineNovelMark(item.getString("id"),
                ReaderMark(item.getInt("start"), item.getInt("end"), item.optBoolean("underline"), Color(0xff71ccba)),
                item.getString("text"), item.optString("note"))
        }
    }.getOrDefault(emptyList())

    fun save(existing: List<OnlineNovelMark>, start: Int, end: Int, text: String, note: String, underline: Boolean): List<OnlineNovelMark> {
        val previous = existing.firstOrNull { it.mark.start == start && it.mark.end == end }
        val value = OnlineNovelMark(previous?.id ?: UUID.randomUUID().toString(),
            ReaderMark(start, end, underline, Color(0xff71ccba)), text, note)
        return persist((listOf(value) + existing.filter { it.id != value.id }).take(100))
    }

    fun remove(existing: List<OnlineNovelMark>, id: String): List<OnlineNovelMark> =
        persist(existing.filter { it.id != id })

    private fun persist(marks: List<OnlineNovelMark>): List<OnlineNovelMark> {
        val array = JSONArray()
        for (item in marks) {
            array.put(JSONObject().put("id", item.id).put("start", item.mark.start).put("end", item.mark.end)
                .put("text", item.text).put("note", item.note).put("underline", item.mark.underline))
        }
        storage.edit().putString(key, array.toString()).apply()
        return marks
    }
}
