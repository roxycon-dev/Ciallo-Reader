package com.example.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.Book
import com.example.data.Chapter
import com.example.data.PrivacyManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class PortableArchiveTest {
    private lateinit var context:Context
    private lateinit var db:AppDatabase
    private lateinit var root:File
    private fun file()=File(context.cacheDir,"test-${UUID.randomUUID()}.ciallo")
    @Before fun setup()=runBlocking {
        val base=ApplicationProvider.getApplicationContext<Context>()
        root=java.nio.file.Files.createTempDirectory("pt-").toFile()
        // SQLite4Java on Windows cannot open the very long Robolectric method-specific paths.
        context=object:android.content.ContextWrapper(base) {
            override fun getFilesDir()=File(root,"f").apply {mkdirs()}
            override fun getCacheDir()=File(root,"c").apply {mkdirs()}
        }
        db=androidx.room.Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible=true;set(null,db) }
        val sql=db.openHelper.writableDatabase
        ArchiveRows.journalTables.asReversed().forEach { sql.execSQL("DELETE FROM `$it`") }
        PortableSettings.stores.forEach { context.getSharedPreferences(it,0).edit().clear().commit() }
        context.getSharedPreferences("privacy_prefs",0).edit().clear().commit()
        listOf("transfer_journal","transfer_undo","source_configs").forEach { File(context.filesDir,it).deleteRecursively() }
    }
    @After fun cleanup(){if(::db.isInitialized)db.close();if(::root.isInitialized)root.deleteRecursively()}
    private suspend fun book(title:String="同名书",body:String="测试正文",category:String="默认"):Int {
        return db.bookDao().insertBookWithChapters(Book(title=title,filePath="",category=category,totalChapters=1),
            listOf(Chapter(bookId=0,chapterOrder=0,title="第一章",content=body))).id
    }
    @Test fun versionTwoRoundTripAndRepeatedMergeAreIdempotent()=runBlocking {
        book(); val archive=file(); PortableArchive.export(context,archive,ExportSelection()){}
        val preview=PortableArchive.inspect(context,archive)
        assertEquals(2,preview.version); assertEquals(1,preview.counts["books"])
        ArchiveRows.tables.asReversed().forEach { db.openHelper.writableDatabase.execSQL("DELETE FROM `$it`") }
        val result=ArchiveImporter.import(context,archive,ImportMode.MERGE){}
        assertTrue(result.added>0); assertEquals(1,db.bookDao().getBooksCount())
        val repeated=ArchiveImporter.import(context,archive,ImportMode.MERGE){}
        assertEquals(0,repeated.added); assertEquals(1,db.bookDao().getBooksCount())
        assertEquals("测试正文",db.bookDao().getChaptersListForBook(db.bookDao().getAllBooksSync().single().id).single().content)
    }
    @Test fun distinctPackagesOfSameDataDoNotDuplicateBooksOrAnnotations()=runBlocking {
        val id=book()
        val row=JSONObject().put("bookId",id).put("chapterIndex",0).put("selectedText","片段").put("note","笔记").put("colorHex","#abcdef").put("createdTime",123L)
        ArchiveRows.insert(db.openHelper.writableDatabase,"highlights",row)
        val first=file(); val second=file()
        PortableArchive.export(context,first,ExportSelection()){};PortableArchive.export(context,second,ExportSelection()){}
        ArchiveImporter.import(context,first,ImportMode.MERGE){};ArchiveImporter.import(context,second,ImportMode.MERGE){}
        assertEquals(1,db.bookDao().getBooksCount()); assertEquals(1,ArchiveRows.all(db.openHelper.writableDatabase,"highlights").size)
    }
    @Test fun sameTitleWithDifferentBodyStaysSeparate()=runBlocking {
        book(body="第一份正文");val archive=file();PortableArchive.export(context,archive,ExportSelection()){}
        db.openHelper.writableDatabase.execSQL("DELETE FROM chapters");db.openHelper.writableDatabase.execSQL("DELETE FROM books")
        book(body="另一版正文")
        ArchiveImporter.import(context,archive,ImportMode.MERGE){}
        assertEquals(2,db.bookDao().getBooksCount())
    }
    @Test fun protectedCategoriesAndTheirAnnotationsAreExcludedByDefault()=runBlocking {
        ArchiveRows.insert(db.openHelper.writableDatabase,"categories",JSONObject().put("name","私密").put("isProtected",1))
        val id=book(category="私密");book(title="公开书")
        ArchiveRows.insert(db.openHelper.writableDatabase,"highlights",JSONObject().put("bookId",id).put("chapterIndex",0).put("selectedText","秘密").put("note","").put("colorHex","#abcdef").put("createdTime",1))
        val archive=file();PortableArchive.export(context,archive,ExportSelection()){}
        val extracted=PortableArchive.extract(context,archive)
        try {assertEquals(1,extracted.preview.counts["books"]);assertNull(extracted.preview.counts["highlights"])
            assertFalse(File(extracted.directory,"database.rows").readText().contains("秘密"))
        }finally{extracted.directory.deleteRecursively()}
    }
    @Test fun settingsKeepReceiverValuesDuringMergeAndCredentialsNeverExport()=runBlocking {
        val prefs=context.getSharedPreferences("novel_reader_prefs",0)
        prefs.edit().putFloat("font_size",21f).putString("api_key","do-not-export").commit()
        val archive=file();PortableArchive.export(context,archive,ExportSelection(setOf(TransferCategory.SETTINGS))){}
        val extracted=PortableArchive.extract(context,archive)
        try {assertFalse(extracted.settings.toString().contains("do-not-export"))}finally{extracted.directory.deleteRecursively()}
        prefs.edit().putFloat("font_size",30f).commit()
        ArchiveImporter.import(context,archive,ImportMode.MERGE){}
        assertEquals(30f,prefs.getFloat("font_size",0f));assertEquals("do-not-export",prefs.getString("api_key",null))
    }
    @Test fun changedPayloadIsRejectedBeforeMutatingReceiver()=runBlocking {
        book();val archive=file();PortableArchive.export(context,archive,ExportSelection()){}
        val corrupt=file();rewrite(archive,corrupt) { name,data -> if(name=="database.rows")data+" ".toByteArray()else data }
        assertFails { PortableArchive.inspect(context,corrupt) }
        assertEquals(1,db.bookDao().getBooksCount());assertFalse(File(context.filesDir,"transfer_journal/pending.json").exists())
    }
    @Test fun zipTraversalAndUnknownVersionsAreRejected()=runBlocking {
        val archive=file();ZipOutputStream(archive.outputStream()).use { zip ->zip.putNextEntry(ZipEntry("files/../../outside"));zip.write(byteArrayOf(1));zip.closeEntry()}
        assertFails {PortableArchive.inspect(context,archive)}
        val valid=file();PortableArchive.export(context,valid,ExportSelection()){}
        val future=file();rewrite(valid,future) {name,data ->if(name=="manifest.json")JSONObject(data.toString(Charsets.UTF_8)).put("version",99).toString().toByteArray()else data}
        assertFails {PortableArchive.inspect(context,future)}
    }
    @Test fun partialCommitRecoveryRestoresRowsAndPreferences()=runBlocking {
        book();val sql=db.openHelper.writableDatabase
        val prefs=context.getSharedPreferences("novel_reader_prefs",0);prefs.edit().putFloat("font_size",22f).commit()
        ImportJournal.prepare(context,sql,"received_${UUID.randomUUID()}",false)
        sql.execSQL("DELETE FROM books");prefs.edit().putFloat("font_size",40f).commit()
        ImportJournal.recover(context,sql)
        assertEquals(1,db.bookDao().getBooksCount());assertEquals(22f,prefs.getFloat("font_size",0f))
        assertFalse(File(context.filesDir,"transfer_journal/pending.json").exists())
    }
    @Test fun replacementRetainsUndoAndUndoRestoresOldShelf()=runBlocking {
        book(title="发送方书籍");val archive=file();PortableArchive.export(context,archive,ExportSelection()){}
        db.openHelper.writableDatabase.execSQL("DELETE FROM chapters");db.openHelper.writableDatabase.execSQL("DELETE FROM books")
        book(title="接收方原有书籍",body="原有正文")
        ArchiveImporter.import(context,archive,ImportMode.REPLACE){}
        assertEquals("发送方书籍",db.bookDao().getAllBooksSync().single().title);assertTrue(ImportJournal.hasUndo(context))
        ImportJournal.activateUndo(context);ImportJournal.recover(context,db.openHelper.writableDatabase)
        assertEquals("接收方原有书籍",db.bookDao().getAllBooksSync().single().title)
    }
    @Test fun protectedImportRequiresReceiverPin()=runBlocking {
        ArchiveRows.insert(db.openHelper.writableDatabase,"categories",JSONObject().put("name","受保护").put("isProtected",1));book(category="受保护")
        val archive=file();PortableArchive.export(context,archive,ExportSelection(protectedAuthorized=true)){}
        assertFails {ArchiveImporter.import(context,archive,ImportMode.MERGE){}}
        PrivacyManager(context).enableWithPin("123456")
        ArchiveImporter.import(context,archive,ImportMode.MERGE){}
        Unit
    }
    @Test fun legacyZipIsRecognizedAndMerged()=runBlocking {
        book();val archive=file();com.example.data.BackupArchive.export(context,archive)
        assertEquals(1,PortableArchive.inspect(context,archive).version)
        ArchiveImporter.import(context,archive,ImportMode.MERGE){}
        assertEquals(1,db.bookDao().getBooksCount())
    }
    @Test fun readingTimeCountsUnionOfSessionsAndDoesNotGrowOnRoundTrip()=runBlocking {
        val id=book();val sql=db.openHelper.writableDatabase
        fun session(start:Long)=JSONObject().put("bookId",id).put("bookTitle","同名书").put("dateStr","2026-10-18")
            .put("startTimeMs",start).put("endTimeMs",start+60000).put("durationSeconds",60).put("startHour",10)
        ArchiveRows.insert(sql,"reading_sessions",session(1000))
        ArchiveRows.insert(sql,"reading_records",JSONObject().put("bookId",id).put("bookTitle","同名书").put("dateStr","2026-10-18").put("durationSeconds",60))
        val a=file();PortableArchive.export(context,a,ExportSelection()){}
        sql.execSQL("DELETE FROM reading_sessions");sql.execSQL("DELETE FROM reading_records")
        ArchiveRows.insert(sql,"reading_sessions",session(100000))
        ArchiveRows.insert(sql,"reading_records",JSONObject().put("bookId",id).put("bookTitle","同名书").put("dateStr","2026-10-18").put("durationSeconds",60))
        ArchiveImporter.import(context,a,ImportMode.MERGE){}
        assertEquals(120L,ArchiveRows.all(sql,"reading_records").sumOf {it.getLong("durationSeconds")})
        val b=file();PortableArchive.export(context,b,ExportSelection()){}
        ArchiveImporter.import(context,b,ImportMode.MERGE){}
        assertEquals(120L,ArchiveRows.all(sql,"reading_records").sumOf {it.getLong("durationSeconds")})
    }
    @Test fun selectingHistoryWithoutBooksDoesNotCreateUnreadableShelfItems()=runBlocking {
        book();val archive=file();PortableArchive.export(context,archive,ExportSelection(setOf(TransferCategory.HISTORY))){}
        db.openHelper.writableDatabase.execSQL("DELETE FROM chapters");db.openHelper.writableDatabase.execSQL("DELETE FROM books")
        val result=ArchiveImporter.import(context,archive,ImportMode.MERGE){}
        assertEquals(0,db.bookDao().getBooksCount());assertTrue(result.warnings.isNotEmpty())
    }
    @Test fun settingsOnlyContainsNoNovelBodyAndNoWrongLocalBookConfiguration()=runBlocking {
        val id=book(body="不应进入设置包的正文")
        context.getSharedPreferences("comic_reader_store",0).edit().putString("book_cfg_$id","{\"mode\":\"SINGLE\"}").commit()
        val archive=file();PortableArchive.export(context,archive,ExportSelection(setOf(TransferCategory.SETTINGS))){}
        val extracted=PortableArchive.extract(context,archive)
        try {assertFalse(File(extracted.directory,"database.rows").readText().contains("不应进入设置包的正文"))}finally{extracted.directory.deleteRecursively()}
        db.openHelper.writableDatabase.execSQL("DELETE FROM chapters");db.openHelper.writableDatabase.execSQL("DELETE FROM books")
        context.getSharedPreferences("comic_reader_store",0).edit().clear().commit()
        ArchiveImporter.import(context,archive,ImportMode.MERGE){}
        assertFalse(context.getSharedPreferences("comic_reader_store",0).contains("book_cfg_$id"))
    }
    private suspend fun assertFails(action:suspend()->Unit) {
        var failed=false;try{action()}catch(_:Exception){failed=true};assertTrue("Expected validation failure",failed)
    }
    private fun rewrite(source:File,target:File,transform:(String,ByteArray)->ByteArray) {
        ZipInputStream(source.inputStream()).use { input ->ZipOutputStream(target.outputStream()).use { output ->while(true){val entry=input.nextEntry?:break
            output.putNextEntry(ZipEntry(entry.name));output.write(transform(entry.name,input.readBytes()));output.closeEntry();input.closeEntry()
        } } }
    }
}
