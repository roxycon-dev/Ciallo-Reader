package com.example.transfer

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.BackupArchive
import org.json.JSONObject
import java.io.File
import java.io.Reader

internal object ArchiveRows {
    val tables = BackupArchive.tables
    val journalTables = tables + listOf("transfer_identities", "transfer_receipts")
    fun row(cursor: Cursor): JSONObject = JSONObject().apply {
        for (i in 0 until cursor.columnCount) put(cursor.getColumnName(i), when(cursor.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
            Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
            else -> error("不支持的数据库类型")
        })
    }
    fun all(db: SupportSQLiteDatabase, table: String, where: String = "1", args: Array<out Any?> = emptyArray()): List<JSONObject> {
        require(table in journalTables)
        return db.query("SELECT * FROM `$table` WHERE $where", args).use { c -> buildList { while(c.moveToNext()) add(row(c)) } }
    }
    fun insert(db: SupportSQLiteDatabase, table: String, row: JSONObject, replace: Boolean = false): Long {
        require(table in journalTables)
        val keys = row.keys().asSequence().toList()
        require(keys.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*")) })
        val sql = "INSERT ${if (replace) "OR REPLACE " else ""}INTO `$table` (${keys.joinToString { "`$it`" }}) VALUES (${keys.joinToString { "?" }})"
        db.compileStatement(sql).use { statement -> keys.forEachIndexed { i, key ->
            when(val value = row.get(key)) {
                JSONObject.NULL -> statement.bindNull(i+1)
                is String -> statement.bindString(i+1, value)
                is Float, is Double -> statement.bindDouble(i+1, (value as Number).toDouble())
                is Number -> statement.bindLong(i+1, value.toLong())
                else -> error("无效数据库字段")
            }
        }; return statement.executeInsert() }
    }
    fun boundedLine(reader: Reader): String? {
        val text = StringBuilder()
        while(true) { val ch = reader.read(); if(ch < 0) return text.toString().takeIf { it.isNotEmpty() }
            if(ch == 10) return text.toString()
            require(text.length < 512 * 1024) { "数据行过大" }; text.append(ch.toChar()) }
    }
    fun forEach(file: File, table: String? = null, action: (String, JSONObject, JSONObject) -> Unit) {
        file.bufferedReader().use { reader ->
            val header = JSONObject(boundedLine(reader) ?: error("缺少数据头"))
            require(header.getInt("version") in 1..2 && header.getInt("schema") in 12..13) { "不支持的数据版本，请升级应用" }
            var count = 0
            while(true) { val line = boundedLine(reader) ?: break
                require(++count <= 1_000_000) { "数据条目过多" }
                val envelope = JSONObject(line); val name = envelope.getString("table")
                require(name in tables) { "包含未知数据表" }
                if(table == null || name == table) action(name, envelope.getJSONObject("row"), envelope)
            }
        }
    }
    fun snapshot(db: SupportSQLiteDatabase, destination: File) {
        destination.bufferedWriter().use { out -> for(table in journalTables) db.query("SELECT * FROM `$table`").use { c ->
            while(c.moveToNext()) out.appendLine(JSONObject().put("table", table).put("row", row(c)).toString())
        } }
    }
    fun restoreSnapshot(db: SupportSQLiteDatabase, source: File) {
        journalTables.asReversed().forEach { db.execSQL("DELETE FROM `$it`") }
        source.bufferedReader().use { reader -> while(true) { val line = boundedLine(reader) ?: break
            val item = JSONObject(line); insert(db, item.getString("table"), item.getJSONObject("row"))
        } }
    }
}
