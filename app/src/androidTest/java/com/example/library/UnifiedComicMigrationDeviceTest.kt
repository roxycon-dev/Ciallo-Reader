package com.example.library

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AppDatabase
import com.example.data.Book
import com.example.download.DownloadStatus
import com.example.source.ComicChapter
import com.example.source.SearchBook
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in migration against real AtomicFile/Room; restores unrelated audit data. */
@RunWith(AndroidJUnit4::class)
class UnifiedComicMigrationDeviceTest {
    @Test fun oldQueueMigratesWithoutLosingHistoryOrShelfFiles() = runBlocking {
        if(InstrumentationRegistry.getArguments().getString("auditComicMigration")!="true") return@runBlocking
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=AppDatabase.getDatabase(context); val dao=db.downloadTaskDao()
        val saved=dao.getAllTasksSync().filter { it.format=="comic" }
        val legacy=File(context.filesDir,"comic_download_tasks.json")
        val previous=legacy.takeIf { it.exists() }?.readBytes()
        val book=SearchBook("legacy-migration-fixture","migration-fixture","迁移验证","",format="comic")
        val chapter=ComicChapter("completed","完成章节")
        val id=ComicDownloadManager.taskId(book,chapter.id)
        val paused=ComicDownloadManager.taskId(book,"paused")
        val directory=ComicDownloadManager.directory(context,id).apply { mkdirs() }
        val retained=File(directory,"retained.txt").apply { writeText("retained") }
        var shelfId:Long?=null
        try {
            db.withTransaction { saved.forEach { dao.deleteTaskById(it.id) } }
            legacy.writeText("{broken")
            assertTrue(runCatching { ComicDownloadManager.awaitInitialized(context) }.isFailure)
            assertEquals("{broken",legacy.readText())
            fun row(chapterId:String,status:String,progress:Double)=JSONObject()
                .put("book",JSONObject().put("id",book.id).put("sourceId",book.sourceId).put("title",book.title).put("author","").put("format","comic"))
                .put("chapter",JSONObject().put("id",chapterId).put("title",chapterId).put("order",0))
                .put("status",status).put("progress",progress).put("error","")
            legacy.writeText(JSONArray().put(row("completed","SUCCESS",1.0)).put(row("paused","PAUSED",0.63)).toString())
            ComicDownloadManager.awaitInitialized(context)
            assertFalse("Remove the old file only after Room commit",legacy.exists())
            assertEquals(DownloadStatus.COMPLETED,dao.getTaskById(id)!!.status)
            assertEquals(.63f,dao.getTaskById(paused)!!.progress)
            assertEquals("comic",dao.getTaskById(id)!!.format)
            shelfId=db.bookDao().insertBook(Book(title="迁移验证",filePath=directory.absolutePath))
            ComicDownloadManager.cancel(id)
            withTimeout(15000) { while(dao.getTaskById(id)!=null) delay(100) }
            assertTrue(retained.isFile)
            assertNotNull(db.bookDao().getBookByFilePath(directory.absolutePath))
            assertEquals(.63f,dao.getTaskById(paused)!!.progress)
        } finally {
            db.withTransaction {
                dao.deleteTaskById(id);dao.deleteTaskById(paused)
                saved.forEach { dao.insertOrUpdate(it) }
                shelfId?.let { db.bookDao().getBookByFilePath(directory.absolutePath)?.let { book -> db.bookDao().deleteBook(book) } }
            }
            if(previous!=null) legacy.writeBytes(previous) else legacy.delete()
            directory.deleteRecursively()
        }
    }
}
