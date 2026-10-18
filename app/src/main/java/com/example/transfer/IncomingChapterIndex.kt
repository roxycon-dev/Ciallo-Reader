package com.example.transfer

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.security.MessageDigest

/** Disk-backed chapter fingerprints avoid rescanning a large archive once for every book. */
internal class IncomingChapterIndex(directory:File,rows:File,context:android.content.Context):java.io.Closeable {
    private val file=File(directory,"chapter_index.sqlite")
    private val database=SQLiteDatabase.openOrCreateDatabase(file,null)
    private val bodyDigests=hashMapOf<Int,MessageDigest>()
    private val bodyFingerprint=BookBodyFingerprint(context,File(directory,"files"))
    private val comicBooks=hashSetOf<Int>()
    init {
        ArchiveRows.forEach(rows,"books") {_,row,_ ->if(row.getString("contentType")=="COMIC")comicBooks.add(row.getInt("id"))}
        database.execSQL("CREATE TABLE incoming_chapters (bookId INTEGER NOT NULL, chapterOrder INTEGER NOT NULL, bodyHash TEXT NOT NULL, title TEXT NOT NULL, contentHash TEXT, hasContent INTEGER NOT NULL, PRIMARY KEY(bookId,chapterOrder))")
        database.beginTransaction()
        try {
            database.compileStatement("INSERT INTO incoming_chapters VALUES (?,?,?,?,?,?)").use { statement ->
                ArchiveRows.forEach(rows,"chapters") {_,row,_ ->
                    val id=row.getInt("bookId")
                    val digest=bodyDigests.getOrPut(id){MessageDigest.getInstance("SHA-256")}
                    for(key in listOf("chapterOrder","title","content")){digest.update(row.get(key).toString().toByteArray());digest.update(0.toByte())}
                    val content=row.getString("content")
                    val hash=bodyFingerprint.chapterHash(content,if(id in comicBooks)"COMIC" else "NOVEL")
                    statement.clearBindings();statement.bindLong(1,id.toLong());statement.bindLong(2,row.getLong("chapterOrder"));statement.bindString(3,hash?:unresolvedHash(content))
                    statement.bindString(4,row.getString("title"));hash?.let {statement.bindString(5,it)}
                    statement.bindLong(6,if(content.isNotBlank())1L else 0L);statement.executeInsert()
                }
            }
            database.setTransactionSuccessful()
        }finally{database.endTransaction()}
    }
    fun bodyIdentity(bookId:Int,contentType:String):String {
        val digest=(bodyDigests[bookId]?.clone() as? MessageDigest)?:MessageDigest.getInstance("SHA-256")
        digest.update(contentType.toByteArray());return "body:"+digest.digest().hex()
    }
    fun contentIdentity(bookId:Int,contentType:String):String? {
        val body=BookBodyFingerprint.Accumulator()
        database.rawQuery("SELECT chapterOrder,title,contentHash,hasContent FROM incoming_chapters WHERE bookId=? ORDER BY chapterOrder",arrayOf(bookId.toString())).use {c ->
            while(c.moveToNext())body.add(c.getInt(0),c.getString(1),if(c.isNull(2))null else c.getString(2),c.getInt(3)!=0)
        }
        return body.finish(contentType)
    }
    fun compatible(local:SupportSQLiteDatabase,incomingId:Int,localId:Int):Boolean {
        val hashes=local.query("SELECT chapterOrder,content FROM chapters WHERE bookId=?",arrayOf(localId)).use { c -> buildMap {
            while(c.moveToNext())if(c.getString(1).isNotBlank())put(c.getInt(0),bodyFingerprint.chapterHash(c.getString(1),if(incomingId in comicBooks)"COMIC" else "NOVEL")?:unresolvedHash(c.getString(1)))
        } }
        database.rawQuery("SELECT chapterOrder,bodyHash FROM incoming_chapters WHERE bookId=? AND hasContent=1",arrayOf(incomingId.toString())).use { c ->
            while(c.moveToNext()){val old=hashes[c.getInt(0)];if(old!=null && old!=c.getString(1))return false}
        }
        return true
    }
    fun compatibleIncoming(first:Int,second:Int):Boolean = database.rawQuery(
        "SELECT 1 FROM incoming_chapters a JOIN incoming_chapters b ON a.chapterOrder=b.chapterOrder WHERE a.bookId=? AND b.bookId=? AND a.hasContent=1 AND b.hasContent=1 AND a.bodyHash!=b.bodyHash LIMIT 1",
        arrayOf(first.toString(),second.toString())).use { !it.moveToFirst() }
    private fun unresolvedHash(body:String):String = "unresolved:"+sha256(body.toByteArray())
    override fun close(){database.close();file.delete();File(file.path+"-journal").delete()}
}
