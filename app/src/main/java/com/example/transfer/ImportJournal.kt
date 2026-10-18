package com.example.transfer

import android.content.Context
import android.util.AtomicFile
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/** A private undo log, recovered by Room.onOpen before readers can see a partial import. */
internal object ImportJournal {
    private fun directory(context: Context) = File(context.filesDir, "transfer_journal")
    private fun atomic(file: File, value: String) {
        val target = AtomicFile(file); val stream = target.startWrite()
        try { stream.write(value.toByteArray()); target.finishWrite(stream) }
        catch(e: Exception) { target.failWrite(stream); throw e }
    }
    suspend fun prepare(context: Context, db: SupportSQLiteDatabase, stageName: String, keepUndo: Boolean) {
        val dir = directory(context)
        check(!File(dir, "pending.json").exists()) { "上一次导入需要先恢复" }
        if(dir.exists())check(dir.deleteRecursively()) { "无法清理上次未开始提交的临时快照" }
        dir.mkdirs()
        ArchiveRows.snapshot(db, File(dir, "database.rows"))
        val prefs = JSONObject()
        PortableSettings.stores.forEach { prefs.put(it, PortableSettings.encode(context.getSharedPreferences(it, 0).all)) }
        prefs.put("god", com.example.god.GodMomentSettingsStore(context).exportPortable())
        atomic(File(dir, "settings.json"), prefs.toString())
        for(name in listOf("source_configs","js_sources")) {
            val sources = File(context.filesDir, name)
            val sourceUndo = File(dir, name)
            sourceUndo.deleteRecursively()
            if(sources.exists()) check(sources.copyRecursively(sourceUndo, overwrite = true))
        }
        atomic(File(dir, "pending.json"), JSONObject().put("stage", stageName).put("keep", keepUndo).toString())
    }
    fun finish(context: Context) {
        val dir = directory(context)
        val pending = JSONObject(File(dir, "pending.json").readText())
        // A committed marker makes a crash during snapshot rotation safe.
        atomic(File(dir, "committed"), "1")
        finalizeCommitted(context, pending)
    }
    private fun finalizeCommitted(context: Context, pending: JSONObject) {
        val dir = directory(context)
        if(pending.optBoolean("keep")) {
            val undo = File(context.filesDir, "transfer_undo")
            undo.deleteRecursively()
            check(dir.renameTo(undo)) { "无法保留迁移回退快照" }
            File(undo, "pending.json").delete(); File(undo, "committed").delete()
        } else dir.deleteRecursively()
    }
    fun recover(context: Context, db: SupportSQLiteDatabase) {
        val dir = directory(context); val marker = File(dir, "pending.json")
        if(!marker.exists()) return
        val pending = JSONObject(marker.readText())
        if(File(dir, "committed").exists()) { finalizeCommitted(context, pending); return }
        db.beginTransaction()
        try { ArchiveRows.restoreSnapshot(db, File(dir, "database.rows"))
            val settings = JSONObject(File(dir, "settings.json").readText())
            PortableSettings.stores.forEach { PortableSettings.write(context.getSharedPreferences(it, 0), settings.getJSONObject(it), true) }
            runBlocking { com.example.god.GodMomentSettingsStore(context).importPortable(settings.getJSONObject("god")) }
            for(name in listOf("source_configs","js_sources")) {
                val sources = File(context.filesDir, name); sources.deleteRecursively()
                File(dir, name).takeIf { it.exists() }?.let { check(it.copyRecursively(sources, overwrite = true)) }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val stageName = pending.optString("stage")
        if(stageName.matches(Regex("received_[a-f0-9-]+"))) File(context.filesDir, stageName).deleteRecursively()
        dir.deleteRecursively()
    }
    fun hasUndo(context: Context): Boolean = File(context.filesDir, "transfer_undo/database.rows").isFile
    fun activateUndo(context: Context) {
        val undo = File(context.filesDir, "transfer_undo")
        check(hasUndo(context)) { "没有可用的回退快照" }
        val dir = directory(context); check(!File(dir,"pending.json").exists())
        dir.deleteRecursively(); check(undo.renameTo(dir))
        atomic(File(dir, "pending.json"), JSONObject().put("stage", "").put("keep", false).toString())
    }
}
