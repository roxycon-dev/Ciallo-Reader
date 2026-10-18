package com.example.transfer

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.ArchiveBudget
import com.example.data.PrivacyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object PortableArchive {
    suspend fun estimate(context:Context,selection:ExportSelection):TransferEstimate=withContext(Dispatchers.IO) {
        val sql=AppDatabase.getDatabase(context).openHelper.writableDatabase
        val hidden=if(selection.protectedAuthorized)emptySet()else ArchiveRows.all(sql,"categories","isProtected=1").map {it.getString("name")}.toSet()
        val books=if(TransferCategory.BOOKS in selection.categories)ArchiveRows.all(sql,"books").filter {it.getString("category") !in hidden}else emptyList()
        val paths=linkedSetOf<String>();var bytes=0L;val warnings=mutableListOf<String>()
        for(book in books) {
            currentCoroutineContext().ensureActive()
            listOf("filePath","coverUri").mapNotNull(book::stringOrNull).filter {it.isNotBlank()}.forEach(paths::add)
            sql.query("SELECT content FROM chapters WHERE bookId=?",arrayOf(book.getInt("id"))).use {c ->while(c.moveToNext()){
                val body=c.getString(0);bytes+=body.toByteArray().size
                Regex("\\[IMG:([^]]+)]").findAll(body).forEach {paths.add(it.groupValues[1])}
            }}
        }
        if(TransferCategory.RESOURCES in selection.categories)context.getSharedPreferences("novel_reader_prefs",0).let {prefs ->
            for(key in listOf("custom_splash_poster_uri","custom_app_background_uri","custom_font_path"))prefs.getString(key,null)?.takeIf {it.isNotBlank()}?.let(paths::add)
        }
        for(path in paths) {
            if(path.startsWith("content://"))try {context.contentResolver.openAssetFileDescriptor(Uri.parse(path),"r")?.use { if(it.length>=0)bytes+=it.length else warnings.add("部分外部资源体积将在打包时确认") }?:warnings.add("部分外部资源暂时无法读取")}
            catch(_:Exception){warnings.add("部分外部资源暂时无法读取")}
            else if(!path.startsWith("http")) {
                val file=File(path.removePrefix("file://"))
                if(file.isFile)bytes+=file.length()else if(file.isDirectory)bytes+=file.walkTopDown().filter {it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".tmp")}.sumOf {it.length()}
                else warnings.add("部分原文件或封面已缺失，将在发送前列出")
            }
        }
        val protectedFavorites=context.getSharedPreferences("novel_reader_prefs",0).getBoolean("favorites_protected",false)
        val favorites=if(TransferCategory.FAVORITES in selection.categories && (!protectedFavorites || selection.protectedAuthorized))
            sql.query("SELECT COUNT(*) FROM favorites").use {it.moveToFirst();it.getInt(0)}else 0
        TransferEstimate(books.size,favorites,bytes,warnings.distinct())
    }
    private val tableCategory = mapOf("books" to TransferCategory.BOOKS, "chapters" to TransferCategory.BOOKS,
        "categories" to TransferCategory.BOOKS, "favorites" to TransferCategory.FAVORITES, "favorite_categories" to TransferCategory.FAVORITES,
        "bookmarks" to TransferCategory.ANNOTATIONS, "highlights" to TransferCategory.ANNOTATIONS, "god_moments" to TransferCategory.ANNOTATIONS,
        "reading_records" to TransferCategory.HISTORY, "reading_sessions" to TransferCategory.HISTORY,
        "comic_progress" to TransferCategory.HISTORY, "comic_chapter_read" to TransferCategory.HISTORY)

    suspend fun export(context: Context, target: File, selection: ExportSelection, progress: (TransferProgress) -> Unit): File = withContext(Dispatchers.IO) {
        require(selection.categories.isNotEmpty()) { "请至少选择一类数据" }
        val db = AppDatabase.getDatabase(context); val sql = db.openHelper.writableDatabase
        val stage = File(context.cacheDir, "portable_${UUID.randomUUID()}").apply { mkdirs() }
        val warnings = mutableListOf<String>(); val resources = linkedMapOf<String, File>()
        val root = context.filesDir.canonicalPath
        val copyRoot = File(stage, "external").apply { mkdirs() }
        val protectedNames = ArchiveRows.all(sql, "categories", "isProtected=1").map { it.getString("name") }.toSet()
        val allBooks = ArchiveRows.all(sql, "books")
        val selectedBooks = allBooks.filter { selection.protectedAuthorized || it.getString("category") !in protectedNames }
        val hiddenKeys = allBooks.filter { !selection.protectedAuthorized && it.getString("category") in protectedNames }
            .flatMap { listOfNotNull(it.getString("id"), it.stringOrNull("sourceId")?.let { source -> "$source::${it.getString("comicId")}" }) }.toSet()
        val bookIds = selectedBooks.map { it.getInt("id") }.toSet()
        val selectedKeys = selectedBooks.map { it.stringOrNull("sourceId")?.let { source -> "$source::${it.getString("comicId")}" } ?: it.getString("id") }.toSet()
        val favoritesProtected = context.getSharedPreferences("novel_reader_prefs", 0).getBoolean("favorites_protected", false)
        val counts = linkedMapOf<String, Int>()
        var containsProtected = false
        var containsProtectedFavorites = false
        fun resource(path: String): String {
            if(path.startsWith("http://") || path.startsWith("https://")) return path
            if(path.startsWith("content://")) {
                val key = sha256(path.toByteArray())
                val file = File(copyRoot, key)
                return try {
                    if(!file.exists()) context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                        file.outputStream().use { ArchiveBudget(512L*1024*1024, 512L*1024*1024, 1).copyEntry(input, it) }
                    } ?: error("无法读取")
                    resources["files/external/$key"] = file
                    "@FILES@/external/$key"
                } catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e
                    warnings.add("外部资源无法读取：${Uri.parse(path).lastPathSegment ?: "未命名资源"}"); "" }
            }
            val raw = path.removePrefix("file://")
            val file = File(raw)
            if(!file.isAbsolute) return path
            if(!file.exists()) { warnings.add("资源已缺失：${file.name}"); return "" }
            val canonical = file.canonicalPath
            val relative = if(canonical.startsWith("$root${File.separator}")) file.canonicalFile.relativeTo(context.filesDir.canonicalFile).invariantSeparatorsPath
                else "external/${sha256(canonical.toByteArray())}/${file.name}"
            if(file.isDirectory) file.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".tmp") }.forEach {
                require(it.canonicalPath.startsWith("$canonical${File.separator}")) { "资源路径越界" }
                resources["files/$relative/${it.relativeTo(file).invariantSeparatorsPath}"] = it
            } else resources["files/$relative"] = file
            return (if(path.startsWith("file://")) "file://" else "") + "@FILES@/$relative"
        }
        fun portable(row: JSONObject, includeResources: Boolean): JSONObject {
            row.keys().asSequence().toList().forEach { key -> val value = row.opt(key)
                if(value is String) {
                    val converted = if(key in setOf("filePath", "coverUri", "localThumbPath", "coverPath") && includeResources) resource(value)
                        else if(key == "content" && includeResources) Regex("\\[IMG:([^]]+)]").replace(value) { "[IMG:${resource(it.groupValues[1])}]" }
                        else value
                    row.put(key, converted.replace(root, "@FILES@"))
                }
            }
            return row
        }
        try {
            progress(TransferProgress(TransferPhase.PREPARING, message = "整理所选数据"))
            val fingerprints = selectedBooks.associate { book -> book.getInt("id") to
                (ArchiveRows.all(sql,"transfer_identities","localKey=?",arrayOf("books:${book.getInt("id")}")).firstOrNull()?.optString("stableId")
                    ?: bookFingerprint(context, sql, book)) }
            val rows = File(stage, "database.rows")
            db.withTransaction {
                rows.bufferedWriter().use { out ->
                    out.appendLine(JSONObject().put("version", 2).put("schema", 13).toString())
                    for(table in ArchiveRows.tables) {
                        val category = tableCategory.getValue(table)
                        // Include book identities and chapter metadata when annotations/history need a parent.
                        val needsParents = selection.categories.any { it in setOf(TransferCategory.ANNOTATIONS, TransferCategory.HISTORY, TransferCategory.SETTINGS) }
                        if(category !in selection.categories && !(table in setOf("books", "chapters", "categories") && needsParents)) continue
                        if(table in setOf("favorites", "favorite_categories", "comic_progress", "comic_chapter_read") && favoritesProtected && !selection.protectedAuthorized) continue
                        sql.query("SELECT * FROM `$table`").use { c -> while(c.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            val row = ArchiveRows.row(c)
                            if(table == "books" && row.getInt("id") !in bookIds) continue
                            if(table in setOf("chapters", "bookmarks", "highlights", "reading_records", "reading_sessions") && !row.isNull("bookId") && row.getInt("bookId") !in bookIds) continue
                            if(table == "categories" && row.getString("name") in protectedNames && !selection.protectedAuthorized) continue
                            if(table == "god_moments") {
                                val id = row.getString("bookId")
                                if(id in hiddenKeys) continue
                                if(id.toIntOrNull() != null && id.toInt() !in bookIds) continue
                                if("::" in id && favoritesProtected && !selection.protectedAuthorized) continue
                                if("::" in id && favoritesProtected) { containsProtected=true;containsProtectedFavorites=true }
                            }
                            if(table == "books" && row.getString("category") in protectedNames) containsProtected = true
                            if(favoritesProtected && table in setOf("favorites", "comic_progress", "comic_chapter_read")) { containsProtected = true;containsProtectedFavorites=true }
                            if(table == "books" && TransferCategory.HISTORY !in selection.categories) {
                                row.put("currentChapterIndex",0).put("scrollOffset",0).put("lastReadTime",0).put("isFinished",0)
                            }
                            val parent = table in setOf("books", "chapters", "categories") && TransferCategory.BOOKS !in selection.categories
                            if(parent && table == "books") row.put("filePath", "").put("coverUri", JSONObject.NULL)
                            if(parent && table == "chapters") row.put("content", "")
                            portable(row, !parent)
                            val envelope = JSONObject().put("table",table).put("row",row)
                            if(table == "books") envelope.put("identity", fingerprints.getValue(row.getInt("id"))).put("parentOnly", parent)
                                .put("bodyVersion",chapterVersion(sql,row.getInt("id")))
                            out.appendLine(envelope.toString()); counts[table] = (counts[table] ?: 0)+1
                        } }
                    }
                }
            }
            val settings = PortableSettings.export(context, selection, selectedKeys, warnings)
            // Resources selected separately control background/font assets, not book payload files.
            if(TransferCategory.RESOURCES in selection.categories) settings.optJSONObject("novel_reader_prefs")?.let { prefs ->
                for(key in listOf("custom_splash_poster_uri","custom_app_background_uri","custom_font_path")) prefs.optJSONObject(key)?.let { item -> item.put("value",resource(item.getString("value"))) }
            } else settings.optJSONObject("novel_reader_prefs")?.let { prefs ->
                listOf("custom_splash_poster_uri","custom_app_background_uri","custom_font_path").forEach(prefs::remove)
            }
            File(stage,"settings.json").writeText(settings.toString())
            val entries = linkedMapOf("database.rows" to rows, "settings.json" to File(stage,"settings.json")).apply { putAll(resources) }
            val manifestEntries = JSONArray(); var expanded = 0L
            for((name,file) in entries) {
                currentCoroutineContext().ensureActive()
                require(file.length() <= 512L*1024*1024) { "单个资源超过 512MiB：${file.name}" }
                expanded += file.length(); require(expanded <= MAX_ARCHIVE_BYTES && entries.size < 50000) { "所选数据超过 4GiB 或文件过多" }
                manifestEntries.put(JSONObject().put("path", name).put("size", file.length()).put("sha256", fileHash(file)))
            }
            val manifest = JSONObject().put("format","ciallo-transfer").put("version",2).put("schema",13)
                .put("packageId", UUID.randomUUID().toString()).put("createdAt",System.currentTimeMillis())
                .put("categories",JSONArray(selection.categories.map { it.name })).put("counts",JSONObject(counts))
                .put("expandedBytes",expanded).put("entries",manifestEntries).put("warnings",JSONArray(warnings.distinct()))
                .put("containsProtected",containsProtected).put("protectedFavorites", containsProtectedFavorites)
            require(manifest.toString().toByteArray().size <= 8*1024*1024) { "清单过大" }
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile,"${target.name}.tmp")
            try { ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
                var done = 0L
                for((name,file) in entries) { zip.putNextEntry(ZipEntry(name)); file.inputStream().use { input ->
                    val buffer = ByteArray(64*1024)
                    while(true) { currentCoroutineContext().ensureActive(); val n=input.read(buffer); if(n<0) break
                        zip.write(buffer,0,n); done+=n; progress(TransferProgress(TransferPhase.PREPARING,done,expanded,message="打包所选内容")) }
                }; zip.closeEntry() }
            }; check(temporary.renameTo(target)) { "数据包保存失败" } } finally { temporary.delete() }
            target
        } finally { stage.deleteRecursively() }
    }

    internal fun bookFingerprint(context: Context, db: androidx.sqlite.db.SupportSQLiteDatabase, book: JSONObject): String {
        val source = book.stringOrNull("sourceId"); val id = book.stringOrNull("comicId")
        if(source != null && id != null) return "online:" + sha256("$source\u0000$id".toByteArray())
        originalFileFingerprint(context,book)?.let {return it}
        val path = book.optString("filePath").removePrefix("file://")
        val file = File(path)
        val digest=MessageDigest.getInstance("SHA-256")
        if(file.isDirectory) file.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(file).invariantSeparatorsPath }.forEach {
            digest.update(it.relativeTo(file).invariantSeparatorsPath.toByteArray()); digest.update(fileHash(it).toByteArray())
        } else db.query("SELECT chapterOrder,title,content FROM chapters WHERE bookId=? ORDER BY chapterOrder",arrayOf(book.getInt("id"))).use { c ->
            while(c.moveToNext()) for(i in 0..2) { digest.update(c.getString(i).replace(context.filesDir.absolutePath,"@FILES@").toByteArray()); digest.update(0.toByte()) }
        }
        // Empty/unavailable books must not all collapse to a single identity.
        digest.update(book.getString("contentType").toByteArray())
        if(book.optInt("totalChapters")==0) digest.update((book.optString("title")+"\u0000"+book.optString("author")+"\u0000"+path).toByteArray())
        return "body:"+digest.digest().hex()
    }

    internal fun originalFileFingerprint(context:Context,book:JSONObject):String? {
        val path=book.optString("filePath").removePrefix("file://")
        return try {
            if(path.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
                while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
                "file:"+digest.digest().hex()
            } else File(path).takeIf {it.isFile}?.let {"file:"+fileHash(it)}
        } catch(_:Exception){null}
    }

    internal fun chapterVersion(db:androidx.sqlite.db.SupportSQLiteDatabase,bookId:Int):String {
        val digest=MessageDigest.getInstance("SHA-256")
        db.query("SELECT chapterOrder,title,content FROM chapters WHERE bookId=? ORDER BY chapterOrder",arrayOf(bookId)).use {c ->while(c.moveToNext()) {
            digest.update(c.getString(0).toByteArray());digest.update(0.toByte());digest.update(c.getString(1).toByteArray());digest.update(0.toByte())
            digest.update(Regex("\\[IMG:([^]]+)]").replace(c.getString(2),"[IMG]").toByteArray());digest.update(0.toByte())
        }}
        return digest.digest().hex()
    }

    internal data class Extracted(val directory: File, val preview: ArchivePreview, val settings: JSONObject)
    suspend fun inspect(context: Context, archive: File): ArchivePreview = withContext(Dispatchers.IO) {
        extract(context,archive).let { try { it.preview } finally { it.directory.deleteRecursively() } }
    }
    internal suspend fun extract(context: Context, archive: File): Extracted {
        require(archive.isFile && archive.length() in 1..MAX_ARCHIVE_BYTES) { "文件为空或超过容量限制" }
        val stage=File(context.filesDir,"received_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val seen=hashSetOf<String>(); val budget=ArchiveBudget(512L*1024*1024,MAX_ARCHIVE_BYTES,50000)
            ZipInputStream(archive.inputStream().buffered()).use { zip -> while(true) {
                currentCoroutineContext().ensureActive(); val entry=zip.nextEntry ?: break
                require(entry.name.length <= 1024 && seen.add(entry.name) && seen.size <= 50000) { "压缩包包含重复或过多条目" }
                require(entry.name in setOf("manifest.json","database.rows","settings.json") || entry.name.startsWith("files/")) { "不是 Ciallo 数据包" }
                val file=ArchiveBudget.destination(stage,entry.name)
                if(!entry.isDirectory) {
                    file.parentFile?.mkdirs()
                    require(stage.usableSpace > 8L*1024*1024) { "存储空间不足" }
                    file.outputStream().use { budget.copyEntry(zip,it) }
                }; zip.closeEntry()
            } }
            val settingsFile=File(stage,"settings.json"); require(settingsFile.isFile && settingsFile.length()<=8*1024*1024) { "设置文件缺失或过大" }
            val rows=File(stage,"database.rows"); require(rows.isFile)
            val manifestFile=File(stage,"manifest.json")
            val manifest=if(manifestFile.isFile) { require(manifestFile.length()<=8*1024*1024); JSONObject(manifestFile.readText()) } else null
            val rawSettings=JSONObject(settingsFile.readText())
            val settings=if(manifest==null) JSONObject().put("novel_reader_prefs",JSONObject().apply {
                rawSettings.keys().forEach { key -> if(com.example.data.BackupArchive.settingType(key)!=null && key!="favorites_protected" && com.example.data.BackupArchive.safeSetting(key)) put(key,rawSettings.get(key)) }
            }) else rawSettings
            var expanded=0L
            if(manifest!=null) {
                require(manifest.getString("format")=="ciallo-transfer" && manifest.getInt("version")==2 && manifest.getInt("schema")==13) { "不支持的数据版本，请升级应用" }
                require(manifest.getString("packageId").matches(Regex("[a-f0-9-]{36}"))) { "数据包标识错误" }
                val listed=hashSetOf<String>(); val entries=manifest.getJSONArray("entries")
                for(i in 0 until entries.length()) {
                    currentCoroutineContext().ensureActive(); val entry=entries.getJSONObject(i); val name=entry.getString("path")
                    require(name!="manifest.json" && listed.add(name))
                    val file=ArchiveBudget.destination(stage,name)
                    require(file.isFile && file.length()==entry.getLong("size") && fileHash(file)==entry.getString("sha256")) { "数据包校验失败：$name" }
                    expanded+=file.length()
                }
                require(listed==seen-setOf("manifest.json") && expanded==manifest.getLong("expandedBytes")) { "数据包清单不完整" }
            } else expanded=stage.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            PortableSettings.validate(settings)
            val sql=AppDatabase.getDatabase(context).openHelper.writableDatabase
            val schemas=ArchiveRows.tables.associateWith { table -> sql.query("PRAGMA table_info(`$table`)").use { c -> buildMap {
                while(c.moveToNext()) put(c.getString(1), c.getString(2) to (c.getInt(3)!=0))
            } } }
            val counts=linkedMapOf<String,Int>(); val bookIds=hashSetOf<Int>(); val references=hashSetOf<Int>()
            val protectedFavorites=manifest?.optBoolean("protectedFavorites") ?: rawSettings.optJSONObject("favorites_protected")?.optBoolean("value",false) ?: false
            var protected=(manifest?.optBoolean("containsProtected") ?: false) || protectedFavorites
            ArchiveRows.forEach(rows) { table,row,_ ->
                val schema=schemas.getValue(table)
                require(row.keys().asSequence().toSet()==schema.keys) { "数据字段与当前版本不兼容" }
                schema.forEach { (key,type) -> val value=row.get(key)
                    require(value!=JSONObject.NULL || !type.second) { "必填数据为空" }
                    require(value==JSONObject.NULL || when(type.first) { "TEXT" -> value is String; "INTEGER" -> value is Int || value is Long; "REAL" -> value is Number && value.toDouble().isFinite(); else -> false })
                    if(value is String && "@FILES@" in value) {
                        // Literal portable paths are checked again after relocation.
                        require(!value.contains("@FILES@/../") && !value.contains("@FILES@\\..\\")) { "资源路径越界" }
                        if(key in setOf("filePath","coverUri","localThumbPath","coverPath")) {
                            val relative=value.removePrefix("file://").removePrefix("@FILES@/")
                            ArchiveBudget.destination(File(stage,"files"),relative)
                        }
                    }
                }
                if(table=="books") require(bookIds.add(row.getInt("id"))) { "重复书籍标识" }
                if(table in setOf("chapters","bookmarks","highlights") || table in setOf("reading_records","reading_sessions") && !row.isNull("bookId")) references.add(row.getInt("bookId"))
                if(table in setOf("reading_records","reading_sessions")) require(row.getLong("durationSeconds") in 0..172800) { "阅读时长超出合理范围" }
                if(table=="reading_sessions")require(row.getLong("endTimeMs")>=row.getLong("startTimeMs") && row.getInt("startHour") in 0..23)
                if(table=="chapters") require(row.getString("content").length<=256000)
                if(table=="categories" && row.getInt("isProtected")!=0) protected=true
                counts[table]=(counts[table]?:0)+1
            }
            require(references.all { it in bookIds }) { "数据包含缺失的关联书籍" }
            val categories=manifest?.getJSONArray("categories")?.let { a -> (0 until a.length()).map { TransferCategory.valueOf(a.getString(it)) }.toSet() } ?: TransferCategory.entries.toSet()
            if(manifest!=null) require(counts==manifest.getJSONObject("counts").let { obj -> obj.keys().asSequence().associateWith { obj.getInt(it) } }) { "数据数量与清单不符" }
            val warnings=manifest?.optJSONArray("warnings")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf("旧版 ZIP：缺少完整性清单，将按兼容方式导入")
            return Extracted(stage,ArchivePreview(manifest?.getString("packageId") ?: fileHash(archive),if(manifest==null)1 else 2,counts,expanded,categories,warnings,protected,protectedFavorites),settings)
        } catch(e: Exception) { stage.deleteRecursively(); throw e }
    }
}
