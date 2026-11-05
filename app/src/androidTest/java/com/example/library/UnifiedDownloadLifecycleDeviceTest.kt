package com.example.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AppDatabase
import com.example.data.Book
import com.example.download.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Invoked twice with a force-stop between seed and verify. */
@RunWith(AndroidJUnit4::class)
class UnifiedDownloadLifecycleDeviceTest {
    @Test fun historyAndProgressSurviveProcessRestartAndExplicitDeletionKeepsBooks() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=AppDatabase.getDatabase(context)
        val dao=db.downloadTaskDao()
        val id=DownloadManager.taskId("lifecycle-audit","completed")
        val pausedId=DownloadManager.taskId("lifecycle-audit","paused")
        val file=File(context.filesDir,"downloads/lifecycle-audit.txt")
        val phase=InstrumentationRegistry.getArguments().getString("phase")
        if(phase=="seed") {
            file.parentFile!!.mkdirs(); file.writeText("第一章 测试\n这是离线保留的书籍正文。")
            if(db.bookDao().getBookByFilePath(file.absolutePath)==null)
                db.bookDao().insertBook(Book(title="生命周期验证",filePath=file.absolutePath,sourceId="lifecycle-audit",comicId="completed"))
            dao.insertOrUpdate(DownloadTaskEntity(id,"lifecycle-audit","完成记录","",null,"https://fixture.invalid/book","txt",DownloadStatus.COMPLETED,
                file.length(),file.length(),file.absolutePath,progress=1f))
            dao.insertOrUpdate(DownloadTaskEntity(pausedId,"lifecycle-audit","章节断点","",null,"https://fixture.invalid/book2","epub",DownloadStatus.PAUSED,
                filePath=File(context.filesDir,"downloads/lifecycle-paused.epub").absolutePath,progress=.63f))
        } else {
            assertEquals(DownloadStatus.COMPLETED,dao.getTaskById(id)!!.status)
            assertEquals(.63f,dao.getTaskById(pausedId)!!.progress)
            val manager=DownloadManager(context)
            delay(500)
            assertNotNull("Completed history must survive restoration",dao.getTaskById(id))
            manager.cancelDownload(id)
            withTimeout(15000) { while(dao.getTaskById(id)!=null) delay(100) }
            assertTrue("Deleting a record must retain the downloaded file",file.isFile)
            assertNotNull("Deleting a record must retain the shelf book",db.bookDao().getBookByFilePath(file.absolutePath))
            val other=dao.getTaskById(pausedId)!!
            assertEquals(.63f,other.progress)
        }
    }
}
