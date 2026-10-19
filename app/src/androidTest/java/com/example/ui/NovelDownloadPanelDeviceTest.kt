package com.example.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.download.*
import com.example.library.NovelDownloadPanel
import com.example.ui.comic.ComicReaderTestActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.runBlocking
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

@RunWith(AndroidJUnit4::class)
class NovelDownloadPanelDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    @Test fun actualCompletedDownloadsAreVisible() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("zlibActualUi") == "true")
        val tasks = com.example.data.AppDatabase.getDatabase(compose.activity).downloadTaskDao()
            .getAllTasksSync().filter { it.sourceId == "zlibrary" && it.status == DownloadStatus.COMPLETED }
        org.junit.Assert.assertTrue(tasks.size >= 2)
        val dao = com.example.data.AppDatabase.getDatabase(compose.activity).bookDao()
        val audit = org.json.JSONArray()
        tasks.forEach { task ->
            val book = dao.getBookBySourceResource(task.sourceId, DownloadManager.originalBookId(task.id, task.sourceId))!!
            val chapters = dao.getChaptersListForBook(book.id)
            org.junit.Assert.assertTrue(chapters.any { it.content.length > 100 && it.title != com.example.data.UNSUPPORTED_CHAPTER_TITLE })
            java.util.zip.ZipFile(File(task.filePath)).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry -> zip.getInputStream(entry).use { it.copyTo(java.io.OutputStream.nullOutputStream()) } }
            }
            audit.put(org.json.JSONObject().put("title", task.title).put("bytes", File(task.filePath).length())
                .put("chapters", chapters.size).put("contentCharacters", chapters.sumOf { it.content.length }).put("zipReadPassed", true))
        }
        File(compose.activity.filesDir, "zlibrary-completed-books-audit.json").writeText(audit.toString(2))
        compose.setContent { MaterialTheme {
            NovelDownloadPanel(tasks, emptyMap(), tasks.associate { it.id to DownloadState.Success(it.filePath) }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("下载任务").assertIsDisplayed()
        compose.onAllNodesWithText("已完成 · 已存入书架")[0].assertIsDisplayed()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.filesDir, "zlibrary-actual-downloads.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun downloadProgressAndFailureAreVisible() {
        val activeId = DownloadManager.taskId("zlibrary", "book/100/abc")
        val failedId = DownloadManager.taskId("zlibrary", "book/200/def")
        val tasks = listOf(
            DownloadTaskEntity(activeId, "zlibrary", "下载进度演示", "测试样例", null, "https://example.com/file", "epub",
                DownloadStatus.DOWNLOADING, 2 * 1024 * 1024, 5 * 1024 * 1024, "/downloads/sample.epub"),
            DownloadTaskEntity(failedId, "zlibrary", "失败任务演示", "测试样例", null, "https://example.com/file2", "epub",
                DownloadStatus.FAILED, filePath = "/downloads/sample2.epub"))
        compose.setContent { MaterialTheme {
            NovelDownloadPanel(tasks, emptyMap(), mapOf(activeId to DownloadState.Downloading(2 * 1024 * 1024, 5 * 1024 * 1024, .4f),
                failedId to DownloadState.Error("节点暂时不可用，可重新获取链接并重试")), {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("下载中 · 40%").assertIsDisplayed()
        compose.onNodeWithText("2.0 MB / 5.0 MB").assertIsDisplayed()
        compose.onNodeWithText("重新获取并重试").performScrollTo().assertIsDisplayed()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.filesDir, "zlibrary-download-panel.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
