package com.example.transfer

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.security.MessageDigest

/** Disk-backed chapter fingerprints avoid rescanning a large archive once for every book. */
internal class IncomingChapterIndex(directory:File,rows:File):java.io.Closeable {
    private val file=File(directory,"chapter_index.sqlite")
    private val database=SQLiteDatabase.openOrCreateDatabase(file,null)
    private val bodyDigests=hashMapOf<Int,MessageDigest>()
    init {
        database.execSQL("CREATE TABLE incoming_chapters (bookId INTEGER NOT NULL, chapterOrder INTEGER NOT NULL, bodyHash TEXT NOT NULL, PRIMARY KEY(bookId,chapterOrder))")
        database.beginTransaction()
        try {
            database.compileStatement("INSERT INTO incoming_chapters VALUES (?,?,?)").use { statement ->
                ArchiveRows.forEach(rows,"chapters") {_,row,_ ->
                    val id=row.getInt("bookId")
                    val digest=bodyDigests.getOrPut(id){MessageDigest.getInstance("SHA-256")}
                    for(key in listOf("chapterOrder","title","content")){digest.update(row.get(key).toString().toByteArray());digest.update(0.toByte())}
                    statement.clearBindings();statement.bindLong(1,id.toLong());statement.bindLong(2,row.getLong("chapterOrder"));statement.bindString(3,normalizedHash(row.getString("content")));statement.executeInsert()
                }
            }
            database.setTransactionSuccessful()
        }finally{database.endTransaction()}
    }
    fun bodyIdentity(bookId:Int,contentType:String):String {
        val digest=(bodyDigests[bookId]?.clone() as? MessageDigest)?:MessageDigest.getInstance("SHA-256")
        digest.update(contentType.toByteArray());return "body:"+digest.digest().hex()
    }
    fun compatible(local:SupportSQLiteDatabase,incomingId:Int,localId:Int):Boolean {
        val hashes=local.query("SELECT chapterOrder,content FROM chapters WHERE bookId=?",arrayOf(localId)).use { c -> buildMap {
            while(c.moveToNext())if(c.getString(1).isNotBlank())put(c.getInt(0),normalizedHash(c.getString(1)))
        } }
        database.rawQuery("SELECT chapterOrder,bodyHash FROM incoming_chapters WHERE bookId=?",arrayOf(incomingId.toString())).use { c ->
            while(c.moveToNext()){val old=hashes[c.getInt(0)];if(old!=null && old!=c.getString(1))return false}
        }
        return true
    }
    private fun normalizedHash(body:String):String = sha256(Regex("\\[IMG:([^]]+)]").replace(body,"[IMG]").toByteArray())
    override fun close(){database.close();file.delete();File(file.path+"-journal").delete()}
}
