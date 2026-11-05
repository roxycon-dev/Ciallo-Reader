package com.example.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.download.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UnifiedDownloadEntryDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun libraryAndDiscoverOpenTheSamePersistentCenter() = runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("novel_reader_prefs",Context.MODE_PRIVATE)
        prefs.edit().putBoolean("has_seen_onboarding",true).putBoolean("has_seen_welcome",true).putInt("render_quality",2).commit()
        val dao=AppDatabase.getDatabase(context).downloadTaskDao()
        val id=DownloadManager.taskId("entry-audit","persistent")
        dao.insertOrUpdate(DownloadTaskEntity(id,"entry-audit","统一记录验证","",null,"fixture","txt",DownloadStatus.COMPLETED,filePath="/unavailable-history.txt",progress=1f))
        val downloads=DownloadManager(context)
        kotlinx.coroutines.delay(500)
        val resume=dao.getAllTasksSync().firstOrNull { it.sourceId=="fanqie" && it.status in setOf(DownloadStatus.PAUSED,DownloadStatus.FAILED) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(30000) { compose.onAllNodesWithText("我的书架").fetchSemanticsNodes().isNotEmpty() }
            resume?.let { downloads.resumeDownload(it.id) }
            compose.onNodeWithText("书库").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("download_tasks_entry").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("download_progress_banner").assertDoesNotExist()
            compose.onNodeWithTag("download_tasks_entry").performClick()
            try { compose.waitUntil(15000) { compose.onAllNodesWithText("统一记录验证").fetchSemanticsNodes().isNotEmpty() } }
            catch(error:Throwable) {
                println(compose.onAllNodes(isRoot(),useUnmergedTree=true).printToString())
                val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(context.filesDir,"download-entry-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                throw error
            }
            compose.onNodeWithText("统一记录验证").performScrollTo().assertIsDisplayed()
            fun screenshot(name:String) {
                val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(context.filesDir,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            screenshot("download-center-library.png")
            compose.onNodeWithText("收起").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("download_task_panel").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("发现").performClick()
            compose.onNodeWithContentDescription("下载任务").performClick()
            try { compose.waitUntil(15000) { compose.onAllNodesWithText("统一记录验证").fetchSemanticsNodes().isNotEmpty() } }
            catch(error:Throwable) {
                println(compose.onAllNodes(isRoot(),useUnmergedTree=true).printToString())
                val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(context.filesDir,"download-entry-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                throw error
            }
            compose.onNodeWithText("统一记录验证").performScrollTo().assertIsDisplayed()
            screenshot("download-center-discover.png")
        }
    }
}
