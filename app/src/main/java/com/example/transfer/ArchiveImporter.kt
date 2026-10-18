package com.example.transfer

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.AppDatabase
import com.example.data.PrivacyManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

internal object ArchiveImporter {
    suspend fun import(context: Context, archive: File, mode: ImportMode, progress: (TransferProgress) -> Unit): ImportResult {
        progress(TransferProgress(TransferPhase.VERIFYING,message="检查数据完整性"))
        val extracted=PortableArchive.extract(context,archive)
        val stage=extracted.directory; val preview=extracted.preview
        val db=AppDatabase.getDatabase(context); val sql=db.openHelper.writableDatabase
        var prepared=false; var finished=false
        var chapterIndex:IncomingChapterIndex?=null
        try {
            if(mode==ImportMode.MERGE && ArchiveRows.all(sql,"transfer_receipts","packageId=?",arrayOf(preview.packageId)).isNotEmpty())
                return ImportResult(0,0,preview.counts.values.sum(),listOf("这份数据已导入，无需重复接收"))
            require(!preview.containsProtected || PrivacyManager(context).isEnabled()) { "请先在接收设备设置隐私 PIN，再导入受保护内容" }
            require(context.filesDir.usableSpace > preview.expandedBytes.coerceAtMost(64L*1024*1024)+16L*1024*1024) { "空间不足以创建恢复快照" }
            val rows=File(stage,"database.rows"); val files=File(stage,"files").absolutePath
            val incomingIndex=IncomingChapterIndex(stage,rows);chapterIndex=incomingIndex
            val bookIds=linkedMapOf<Int,Int>(); val chapterIds=linkedMapOf<Int,Int>(); val bookIdentities=hashMapOf<Int,String>()
            var added=0; var merged=0; var skipped=0
            val warnings=preview.warnings.toMutableList()
            val existingBooks=ArchiveRows.all(sql,"books")
            val existingFingerprints=existingBooks.associateBy { PortableArchive.bookFingerprint(context,sql,it) }
            val total=preview.counts.values.sum().toLong(); var completed=0L
            ImportJournal.prepare(context,sql,stage.name,mode==ImportMode.REPLACE); prepared=true
            // No cancellation across the commit boundary: a cancellation is observed before it, or after a complete commit.
            withContext(NonCancellable) {
                db.withTransaction {
                    if(mode==ImportMode.REPLACE) {
                        val chosen=preview.categories
                        ArchiveRows.tables.asReversed().filter { table -> when(table) {
                            "books","chapters","categories" -> TransferCategory.BOOKS in chosen
                            "favorites","favorite_categories" -> TransferCategory.FAVORITES in chosen
                            "bookmarks","highlights","god_moments" -> TransferCategory.ANNOTATIONS in chosen || TransferCategory.BOOKS in chosen
                            else -> TransferCategory.HISTORY in chosen || TransferCategory.BOOKS in chosen
                        } }.forEach { sql.execSQL("DELETE FROM `$it`") }
                        sql.execSQL("DELETE FROM transfer_receipts"); sql.execSQL("DELETE FROM transfer_identities")
                    }
                    fun relocated(row: JSONObject): JSONObject {
                        row.keys().asSequence().toList().forEach { key -> (row.opt(key) as? String)?.let { text ->
                            var value=text.replace("@FILES@",files)
                            fun portablePath(path:String):Boolean = path.startsWith("http://") || path.startsWith("https://") ||
                                path.removePrefix("file://").startsWith(files+File.separator) || path.isBlank()
                            if(key in setOf("filePath","coverUri","localThumbPath","coverPath") && !portablePath(value))value=""
                            if(key=="content")value=Regex("\\[IMG:([^]]+)]").replace(value){if(portablePath(it.groupValues[1]))it.value else ""}
                            row.put(key,value)
                        } }; return row
                    }
                    fun same(table: String, where: String, args: Array<out Any?>): JSONObject? = ArchiveRows.all(sql,table,where,args).firstOrNull()
                    fun putIdentity(key: String,id: String) = ArchiveRows.insert(sql,"transfer_identities",JSONObject().put("localKey",key).put("stableId",id),true)
                    fun dayKey(row:JSONObject)=listOf(row.stringOrNull("bookId")?:"null",row.getString("bookTitle"),row.getString("dateStr"))
                    val localDaily=ArchiveRows.all(sql,"reading_records").groupBy(::dayKey).mapValues { it.value.sumOf { row -> row.getLong("durationSeconds") } }
                    val localSessions=ArchiveRows.all(sql,"reading_sessions").groupBy(::dayKey).mapValues { it.value.sumOf { row -> row.getLong("durationSeconds") } }
                    val order=listOf("categories","favorite_categories","books","chapters","favorites","comic_progress","comic_chapter_read","bookmarks","highlights","god_moments","reading_sessions")
                    for(table in order) ArchiveRows.forEach(rows,table) { _,raw,envelope ->
                        val row=relocated(raw); completed++
                        if(table in setOf("chapters","bookmarks","highlights","reading_sessions") && !row.isNull("bookId") && row.getInt("bookId") !in bookIds) {
                            skipped++;return@forEach
                        }
                        progress(TransferProgress(TransferPhase.IMPORTING,completed,total,message="合并${tableLabel(table)}"))
                        when(table) {
                            "categories" -> {
                                val old=same(table,"name=?",arrayOf(row.getString("name")))
                                row.remove("id")
                                if(old==null) { ArchiveRows.insert(sql,table,row); added++ }
                                else { if(row.getInt("isProtected")!=0) sql.execSQL("UPDATE categories SET isProtected=1 WHERE id=?",arrayOf(old.getInt("id"))); merged++ }
                            }
                            "favorite_categories" -> if(same(table,"name=?",arrayOf(row.getString("name")))==null) { ArchiveRows.insert(sql,table,row); added++ } else skipped++
                            "books" -> {
                                val original=row.getInt("id")
                                val identity=envelope.optString("identity").takeIf { it.isNotBlank() } ?: run {
                                    val source=row.stringOrNull("sourceId");val remote=row.stringOrNull("comicId")
                                    val payload=File(row.optString("filePath").removePrefix("file://"))
                                    when { source!=null && remote!=null -> "online:"+sha256("$source\u0000$remote".toByteArray())
                                        payload.exists() -> PortableArchive.bookFingerprint(context,sql,row)
                                        else -> incomingIndex.bodyIdentity(original,row.getString("contentType")) }
                                }
                                val mapped=if(mode==ImportMode.MERGE) same("transfer_identities","stableId=? AND localKey LIKE 'books:%'",arrayOf(identity))?.optString("localKey")?.removePrefix("books:")?.toIntOrNull()?.let { id -> same("books","id=?",arrayOf(id)) } else null
                                val candidates=if(mode==ImportMode.MERGE)listOfNotNull(mapped,existingFingerprints[identity])+
                                    (if(row.stringOrNull("sourceId")!=null)ArchiveRows.all(sql,"books","sourceId=? AND comicId=?",arrayOf(row.getString("sourceId"),row.getString("comicId")))else emptyList())else emptyList()
                                val old=candidates.firstOrNull {
                                    if(envelope.optBoolean("parentOnly")) envelope.optString("bodyVersion").let { version -> version.isBlank() || version==PortableArchive.chapterVersion(sql,it.getInt("id")) }
                                    else incomingIndex.compatible(sql,original,it.getInt("id"))
                                }
                                if(old==null && envelope.optBoolean("parentOnly")) {
                                    warnings.add("《${row.getString("title")}》未在本机找到，相关标注和记录已跳过；选择书籍类别可完整传送")
                                    skipped++;return@forEach
                                }
                                if(old!=null) {
                                    val id=old.getInt("id"); bookIds[original]=id
                                    if(TransferCategory.HISTORY in preview.categories && row.getLong("lastReadTime")>old.getLong("lastReadTime"))
                                        sql.execSQL("UPDATE books SET currentChapterIndex=?,scrollOffset=?,lastReadTime=?,isFinished=? WHERE id=?",arrayOf(row.getInt("currentChapterIndex"),row.getInt("scrollOffset"),row.getLong("lastReadTime"),row.getInt("isFinished"),id))
                                    merged++
                                } else {
                                    row.remove("id")
                                    bookIds[original]=ArchiveRows.insert(sql,table,row).toInt(); added++
                                }
                                bookIdentities[original]=identity
                                putIdentity("books:${bookIds.getValue(original)}",identity)
                            }
                            "chapters" -> {
                                val oldId=row.getInt("id"); row.put("bookId",bookIds.getValue(row.getInt("bookId")))
                                val old=same(table,"bookId=? AND chapterOrder=?",arrayOf(row.getInt("bookId"),row.getInt("chapterOrder")))
                                if(old!=null) { chapterIds[oldId]=old.getInt("id"); skipped++ }
                                else { row.remove("id"); chapterIds[oldId]=ArchiveRows.insert(sql,table,row).toInt(); added++ }
                            }
                            "favorites","comic_progress","comic_chapter_read" -> {
                                val columns=if(table=="comic_chapter_read") listOf("sourceId","comicId","chapterId") else listOf("sourceId","comicId")
                                val old=same(table,columns.joinToString(" AND ") { "$it=?" },columns.map { row.getString(it) }.toTypedArray())
                                val newer=old!=null && when(table) { "comic_progress" -> row.getLong("lastReadAt")>old.getLong("lastReadAt"); "comic_chapter_read" -> row.getLong("updatedAt")>old.getLong("updatedAt"); else -> false }
                                if(old==null || newer) { ArchiveRows.insert(sql,table,row,true); if(old==null) added++ else merged++ } else skipped++
                            }
                            "bookmarks","highlights" -> {
                                row.put("bookId",bookIds.getValue(row.getInt("bookId"))); row.remove("id")
                                val old=if(table=="bookmarks") same(table,"bookId=? AND chapterIndex=?",arrayOf(row.getInt("bookId"),row.getInt("chapterIndex")))
                                    else same(table,"bookId=? AND chapterIndex=? AND selectedText=? AND note=? AND colorHex=?",arrayOf(row.getInt("bookId"),row.getInt("chapterIndex"),row.getString("selectedText"),row.getString("note"),row.getString("colorHex")))
                                if(old==null) { ArchiveRows.insert(sql,table,row); added++ }
                                else if(table=="bookmarks" && row.getLong("createdTime")>old.getLong("createdTime")) { row.put("id",old.getInt("id")); ArchiveRows.insert(sql,table,row,true); merged++ } else skipped++
                            }
                            "god_moments" -> {
                                row.getString("bookId").toIntOrNull()?.let { old ->
                                    val mapped=bookIds[old]
                                    if(mapped==null){skipped++;return@forEach}
                                    row.put("bookId",mapped.toString())
                                }
                                row.getString("chapterId").toIntOrNull()?.let { chapterIds[it]?.let { id -> row.put("chapterId",id.toString()) } }
                                val old=same(table,"bookId=? AND chapterId=?",arrayOf(row.getString("bookId"),row.getString("chapterId")))
                                if(old==null || row.getLong("updatedAt")>old.getLong("updatedAt")) {
                                    row.remove("id"); old?.let { row.put("id",it.getLong("id")) }; ArchiveRows.insert(sql,table,row,true)
                                    if(old==null) added++ else merged++
                                } else skipped++
                            }
                            "reading_sessions" -> {
                                if(!row.isNull("bookId")) row.put("bookId",bookIds.getValue(row.getInt("bookId")))
                                val old=same(table,"bookId IS ? AND bookTitle=? AND startTimeMs=?",arrayOf(if(row.isNull("bookId"))null else row.getInt("bookId"),row.getString("bookTitle"),row.getLong("startTimeMs")))
                                if(old==null) { row.remove("id"); ArchiveRows.insert(sql,table,row); added++ }
                                else if(row.getLong("endTimeMs")>old.getLong("endTimeMs")) { row.put("id",old.getLong("id")); ArchiveRows.insert(sql,table,row,true);merged++ } else skipped++
                            }
                        }
                    }
                    val incomingDaily=linkedMapOf<List<String>,Long>();val incomingSessions=linkedMapOf<List<String>,Long>()
                    for(table in listOf("reading_records","reading_sessions")) ArchiveRows.forEach(rows,table) { _,row,_ ->
                        if(!row.isNull("bookId") && row.getInt("bookId") !in bookIds)return@forEach
                        if(!row.isNull("bookId"))row.put("bookId",bookIds.getValue(row.getInt("bookId")))
                        val key=dayKey(row);val sums=if(table=="reading_records")incomingDaily else incomingSessions
                        sums[key]=(sums[key]?:0)+row.getLong("durationSeconds")
                    }
                    val unionSessions=ArchiveRows.all(sql,"reading_sessions").groupBy(::dayKey).mapValues { it.value.sumOf { row -> row.getLong("durationSeconds") } }
                    // Count the union of known sessions once; only unknown historical remainders use max.
                    for(key in incomingDaily.keys+incomingSessions.keys) {
                        val legacy=maxOf(((localDaily[key]?:0)-(localSessions[key]?:0)).coerceAtLeast(0),((incomingDaily[key]?:0)-(incomingSessions[key]?:0)).coerceAtLeast(0))
                        val seconds=(unionSessions[key]?:0)+legacy
                        val id=key[0].toIntOrNull()
                        sql.execSQL("DELETE FROM reading_records WHERE bookId IS ? AND bookTitle=? AND dateStr=?",arrayOf(id,key[1],key[2]))
                        ArchiveRows.insert(sql,"reading_records",JSONObject().put("bookId",id?:JSONObject.NULL).put("bookTitle",key[1]).put("dateStr",key[2]).put("durationSeconds",seconds));merged++
                    }
                    if(TransferCategory.HISTORY in preview.categories) {
                        val seconds=ArchiveRows.all(sql,"reading_records").sumOf { it.getLong("durationSeconds") }
                        check(context.getSharedPreferences("novel_reader_prefs",0).edit().putLong("total_read_time_seconds",seconds).commit())
                    }
                    val settings=JSONObject(extracted.settings.toString().replace("@FILES@",files))
                    settings.optJSONObject("novel_reader_prefs")?.let { values ->
                        for(key in listOf("custom_splash_poster_uri","custom_app_background_uri","custom_font_path")) {
                            values.optJSONObject(key)?.optString("value")?.takeIf {it.removePrefix("file://").startsWith(files+File.separator)!=true}?.let {values.remove(key)}
                        }
                    }
                    PortableSettings.apply(context,settings,mode,bookIds)
                    if(preview.protectedFavorites) check(context.getSharedPreferences("novel_reader_prefs",0).edit().putBoolean("favorites_protected",true).commit())
                    ArchiveRows.insert(sql,"transfer_receipts",JSONObject().put("packageId",preview.packageId).put("importedAt",System.currentTimeMillis()),true)
                }
                ImportJournal.finish(context); finished=true
                db.invalidationTracker.refreshVersionsAsync()
            }
            return ImportResult(added,merged,skipped,warnings.distinct())
        } catch(e: Exception) {
            if(prepared && !finished) withContext(NonCancellable) { ImportJournal.recover(context,sql); db.invalidationTracker.refreshVersionsAsync() }
            throw e
        } finally { chapterIndex?.close();if(!finished) stage.deleteRecursively() else {
            File(stage,"database.rows").delete(); File(stage,"settings.json").delete(); File(stage,"manifest.json").delete()
        } }
    }
    private fun tableLabel(table: String)=when(table) { "books","chapters" -> "书籍"; "reading_sessions","reading_records" -> "阅读记录"; "favorites" -> "收藏"; else -> "用户数据" }
}
