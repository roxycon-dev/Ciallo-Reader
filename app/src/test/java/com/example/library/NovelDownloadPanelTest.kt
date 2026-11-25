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
        assertEquals("下载中", downloadStatusLabel(DownloadState.Downloading(1024, -1, 0f)))
        assertEquals("校验入库…", downloadStatusLabel(DownloadState.Downloading(1024, 1024, 1f)))
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
        compose.onNodeWithContentDescription("重新获取并重试").performClick()
        assertEquals(failed, retried)
        compose.onNodeWithText("已恢复的下载").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("下载中 · 50%").performScrollTo().assertIsDisplayed()
    }
    @Test fun completedHistoryRequiresConfirmationOnEveryDeletion() {
        val task=DownloadTaskEntity("done","fixture","已完成的书","作者",null,"https://example.com/book","epub",DownloadStatus.COMPLETED,filePath="/book.epub",progress=1f)
        var deleted=0
        compose.setContent { MaterialTheme {
            NovelDownloadPanel(listOf(task),emptyMap(),emptyMap(),{},{},{deleted++},{},{})
        } }
        compose.onNodeWithText("已完成").assertIsDisplayed()
        compose.onNodeWithContentDescription("删除记录").performClick()
        assertEquals(0,deleted)
        compose.onNodeWithText("保留").performClick()
        assertEquals(0,deleted)
        compose.onNodeWithContentDescription("删除记录").performClick()
        compose.onNodeWithText("删除下载记录？").assertIsDisplayed()
        compose.onNodeWithText("删除",useUnmergedTree=true).performClick()
        compose.waitUntil { deleted == 1 }
        assertEquals(1,deleted)
        compose.onNodeWithContentDescription("删除记录").performClick()
        compose.onNodeWithText("删除下载记录？").assertIsDisplayed()
        assertEquals(1,deleted)
    }

    @Test fun longPressSelectAllDeletesEveryRecordOnce() {
        val first = DownloadTaskEntity("one", "fixture", "第一本", "", null, "", "epub", DownloadStatus.COMPLETED, filePath = "/one.epub")
        val second = first.copy(id = "two", title = "第二本", filePath = "/two.epub")
        val remaining = androidx.compose.runtime.mutableStateOf(listOf(first, second))
        val deleted = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            NovelDownloadPanel(remaining.value, emptyMap(), emptyMap(), {}, {}, { id ->
                deleted += id
                remaining.value = remaining.value.filterNot { it.id == id }
            }, {}, {})
        } }

        compose.onNodeWithText(first.title).performTouchInput { longClick() }
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("已选择 2 / 2").assertIsDisplayed()
        compose.onNodeWithText("删除所选").performClick()
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        compose.onNodeWithText("暂无下载任务").assertIsDisplayed()
        assertEquals(listOf(first.id, second.id), deleted)
    }

    @Test fun chapterProgressRestoresWithoutAnInMemoryBroadcaster() {
        val task=DownloadTaskEntity("chapter-progress","fixture","断点任务","作者",null,"https://example.com/book","epub",DownloadStatus.DOWNLOADING,
            filePath="/book.epub",progress=.63f)
        compose.setContent { MaterialTheme { NovelDownloadPanel(listOf(task),emptyMap(),emptyMap(),{},{},{},{},{}) } }
        compose.onNodeWithText("下载中 · 63%").assertIsDisplayed()
    }

}
