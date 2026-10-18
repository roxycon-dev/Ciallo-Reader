package com.example.transfer

import android.content.Context
import android.content.SharedPreferences
import com.example.data.BackupArchive
import com.example.source.storage.SharedPreferencesSourceStorage
import org.json.JSONArray
import org.json.JSONObject

/** Only named, typed settings cross the device boundary. Private script stores never do. */
internal object PortableSettings {
    val stores = listOf("novel_reader_prefs", "comic_reader_store", "book_sources_config")
    private val comicKeys = setOf("global_config", "presets", "default_preset", "custom_fit_presets")
    private val sensitive = Regex("(?i)(cookie|authorization|credential|password|secret|api.?key|access.?token|refresh.?token)")
    fun encode(values: Map<String, *>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> if (value != null) put(key, JSONObject()
            .put("type", when (value) { is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is Set<*> -> "set"; else -> "string" })
            .put("value", if (value is Set<*>) JSONArray(value.toList()) else value)) }
    }
    fun write(prefs: SharedPreferences, encoded: JSONObject, replace: Boolean = false) {
        val edit = prefs.edit()
        if (replace) edit.clear()
        encoded.keys().forEach { key -> val item = encoded.getJSONObject(key)
            when (item.getString("type")) {
                "boolean" -> edit.putBoolean(key, item.getBoolean("value"))
                "int" -> edit.putInt(key, item.getInt("value"))
                "long" -> edit.putLong(key, item.getLong("value"))
                "float" -> edit.putFloat(key, item.getDouble("value").toFloat().also { require(it.isFinite()) })
                "string" -> edit.putString(key, item.getString("value"))
                "set" -> edit.putStringSet(key, item.getJSONArray("value").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                else -> error("不支持的设置类型")
            }
        }
        check(edit.commit()) { "设置保存失败" }
    }
    suspend fun export(context: Context, selection: ExportSelection, selectedBookKeys: Set<String>, warnings: MutableList<String>): JSONObject {
        val output = JSONObject()
        if (TransferCategory.SETTINGS in selection.categories || TransferCategory.RESOURCES in selection.categories) {
            val novel = context.getSharedPreferences(stores[0], 0).all.filterKeys { key ->
                BackupArchive.safeSetting(key) && BackupArchive.settingType(key) != null &&
                    key !in setOf("favorites_protected", "total_read_time_seconds", "legacy_unattributed_read_seconds", "reading_totals_reconciled_v1") &&
                    !key.startsWith("daily_read_time_")
                    && (TransferCategory.SETTINGS in selection.categories || key in setOf("custom_splash_poster_uri","custom_app_background_uri","custom_font_path"))
            }
            output.put(stores[0], encode(novel))
            if(TransferCategory.SETTINGS in selection.categories) output.put(stores[1], encode(context.getSharedPreferences(stores[1], 0).all.filterKeys { key ->
                key in comicKeys || selectedBookKeys.any { key == "book_cfg_$it" || key == "book_state_$it" || key.startsWith("resume_${it}_") }
            }))
            if(TransferCategory.SETTINGS in selection.categories) output.put("god", com.example.god.GodMomentSettingsStore(context).exportPortable())
        }
        if (TransferCategory.SOURCES in selection.categories) {
            val storage = SharedPreferencesSourceStorage(context)
            val custom = JSONObject()
            storage.getCustomSourceJsons().forEach { (id, body) ->
                // Opaque rule strings can contain credentials; skip the entire source rather than guessing a redaction.
                if (sensitive.containsMatchIn(body) || Regex("(?i)https?://[^/\\s]+:[^/@\\s]+@").containsMatchIn(body)) warnings.add("书源 $id 含认证字段，未发送")
                else custom.put(id, JSONObject(body))
            }
            output.put("sources", custom)
            output.put("scripts",com.example.source.js.JsSourceRepo.exportPortable(context,warnings))
            output.put("source_states", JSONObject(storage.getSourceStates()))
            storage.getActiveSourceId()?.let { output.put("active_source", it) }
        }
        return output
    }
    fun validate(value: JSONObject) {
        value.optJSONObject(stores[0])?.let { settings -> settings.keys().forEach { key ->
            require(BackupArchive.safeSetting(key) && BackupArchive.settingType(key) != null && key != "favorites_protected") { "包含不可迁移设置" }
            val item = settings.getJSONObject(key)
            require(item.getString("type") == BackupArchive.settingType(key)) { "设置类型不符" }
            if (item.getString("type") == "float") require(item.getDouble("value").isFinite() && kotlin.math.abs(item.getDouble("value")) <= 10000)
            when(key) {
                "font_size" -> require(item.getDouble("value") in 8.0..80.0)
                "line_height" -> require(item.getDouble("value") in 8.0..120.0)
                "reader_theme" -> require(item.getInt("value") in 0..5)
                "page_turn_mode" -> require(item.getInt("value") in 0..4)
                "screen_orientation_lock" -> require(item.getInt("value") in 0..2)
            }
        } }
        value.optJSONObject(stores[1])?.let { settings -> settings.keys().forEach { key ->
            require(key in comicKeys || key.startsWith("book_cfg_") || key.startsWith("book_state_") || key.startsWith("resume_"))
            val item = settings.getJSONObject(key); require(item.getString("type") == "string")
            val raw = item.getString("value"); require(raw.length <= 1024 * 1024)
            if (key == "presets" || key == "custom_fit_presets") JSONArray(raw)
            else if (key != "default_preset") JSONObject(raw)
        } }
        value.optJSONObject("sources")?.let { sources -> sources.keys().forEach { id ->
            require(id.length in 1..512 && sources.getJSONObject(id).toString().length <= 2 * 1024 * 1024)
            require(!sensitive.containsMatchIn(sources.getJSONObject(id).toString())) { "书源含认证字段" }
        } }
        value.optJSONObject("god")?.let { it.getString("ranking_style"); it.getBoolean("last_page_hint"); it.getBoolean("gyro_parallax") }
        value.optJSONArray("scripts")?.let(com.example.source.js.JsSourceRepo::validatePortable)
    }
    suspend fun apply(context: Context, value: JSONObject, mode: ImportMode, bookIds: Map<Int, Int>) {
        validate(value)
        for (name in stores.take(2)) value.optJSONObject(name)?.let { input ->
            val prefs = context.getSharedPreferences(name, 0)
            val output = JSONObject()
            input.keys().forEach { oldKey ->
                var key = oldKey
                if(name==stores[1]) {
                    val numeric=Regex("^(?:book_cfg_|book_state_|resume_)(\\d+)(?:_|$)").find(oldKey)?.groupValues?.get(1)?.toIntOrNull()
                    if(numeric!=null && numeric !in bookIds)return@forEach
                }
                if (name == stores[1]) for ((old, new) in bookIds) {
                    if (oldKey == "book_cfg_$old" || oldKey == "book_state_$old") key = oldKey.substringBeforeLast('_') + "_$new"
                    if (oldKey.startsWith("resume_${old}_")) key = "resume_${new}_" + oldKey.removePrefix("resume_${old}_")
                }
                if (mode == ImportMode.REPLACE || !prefs.contains(key)) output.put(key, input.get(oldKey))
                else if (key in setOf("presets", "custom_fit_presets")) {
                    val existing = JSONArray(prefs.getString(key, "[]")); val incoming = JSONArray(input.getJSONObject(oldKey).getString("value"))
                    val ids = (0 until existing.length()).map { existing.getJSONObject(it).getString("id") }.toMutableSet()
                    for (i in 0 until incoming.length()) if (ids.add(incoming.getJSONObject(i).getString("id"))) existing.put(incoming.getJSONObject(i))
                    output.put(key, JSONObject().put("type", "string").put("value", existing.toString()))
                }
            }
            // Remove only portable keys: credentials and device preferences are never cleared.
            if (mode == ImportMode.REPLACE) {
                val edit = prefs.edit()
                prefs.all.keys.filter { key -> if (name == stores[0]) BackupArchive.settingType(key) != null && BackupArchive.safeSetting(key) && key != "favorites_protected"
                    else key in comicKeys || key.startsWith("book_cfg_") || key.startsWith("book_state_") || key.startsWith("resume_") }.forEach(edit::remove)
                check(edit.commit())
            }
            write(prefs, output)
        }
        value.optJSONObject("god")?.let { if (mode == ImportMode.REPLACE) com.example.god.GodMomentSettingsStore(context).importPortable(it) }
        val storage = SharedPreferencesSourceStorage(context)
        value.optJSONArray("scripts")?.let { com.example.source.js.JsSourceRepo.importPortable(context,it,mode==ImportMode.REPLACE) }
        val existing = storage.getCustomSourceJsons()
        value.optJSONObject("sources")?.let { sources -> sources.keys().forEach { id ->
            if (mode == ImportMode.REPLACE || id !in existing) storage.saveCustomSourceJson(id, sources.getJSONObject(id).toString())
        } }
        val states = storage.getSourceStates()
        value.optJSONObject("source_states")?.let { sources -> sources.keys().forEach { id ->
            if (mode == ImportMode.REPLACE || id !in states) storage.saveSourceState(id, sources.getBoolean(id))
        } }
        value.stringOrNull("active_source")?.let { if (mode == ImportMode.REPLACE || storage.getActiveSourceId() == null) storage.saveActiveSourceId(it) }
    }
}
