package com.example.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.download.*
import com.example.source.SearchBook
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelDownloadPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownLengthDoesNotClaimZeroPercent() {
        assertEquals("下载中 · 总大小未知", downloadStatusLabel(DownloadState.Downloading(1024, -1, 0f)))
        assertEquals("校验文件并存入书架…", downloadStatusLabel(DownloadState.Downloading(1024, 1024, 1f)))
    }

    @Test fun restoredTasksAndPreTransferFailureRemainVisibleAndRetryable() {
        val task = DownloadTaskEntity("v2:8:zlibrarybook/1/abc", "zlibrary", "已恢复的下载", "作者", null,
            "https://example.com/file", "epub", DownloadStatus.DOWNLOADING, 1024, 2048, "/downloads/book.epub")
        val key = DownloadManager.taskId(task.sourceId, "book/1/abc")
        val failed = SearchBook("book/2/def", "zlibrary", "准备阶段失败", "作者", format = "epub")
        var retried: SearchBook? = null
        compose.setContent { MaterialTheme {
            NovelDownloadPanel(listOf(task), mapOf("failed" to LibraryViewModel.DownloadPreparation(failed,
                DownloadState.Error("节点不可用，请重试"))), mapOf(key to DownloadState.Downloading(1024, 2048, .5f)),
                {}, {}, {}, { retried = it }, {})
        } }
        compose.onNodeWithText("准备阶段失败").assertIsDisplayed()
        compose.onNodeWithText("节点不可用，请重试").assertIsDisplayed()
        compose.onNodeWithText("重新获取并重试").performClick()
        assertEquals(failed, retried)
        compose.onNodeWithText("已恢复的下载").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("下载中 · 50%").performScrollTo().assertIsDisplayed()
    }
}
