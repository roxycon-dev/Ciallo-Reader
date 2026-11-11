package com.example.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.favorite.ComicProgressEntity
import com.example.source.ComicChapter
import com.example.source.SearchBook
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN-w411dp-h1200dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicChapterLabelsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun detailKeepsFractionalEpisodesAndDoesNotNumberExtrasOrNotices() {
        compose.setContent {
            MyApplicationTheme {
                ComicChaptersScreen(
                    book = SearchBook("book", "source", "测试漫画", "测试作者"),
                    chapters = listOf(ComicChapter("decimal", "180.5话 鹰身女妖", order = 0f),
                        ComicChapter("extra", "番外2", order = 1f),
                        ComicChapter("notice", "2026-10-18 停更公告", order = 2f)),
                    loading = false, error = null, downloadingChapters = emptySet(),
                    downloadProgress = emptyMap(), pausedChapters = emptySet(),
                    onBack = {}, onRetry = {}, onChapterClick = {}, onDownloadChapter = {},
                    onDownloadAll = {}, onPauseDownload = {}, onResumeDownload = {}, onCancelDownload = {},
                )
            }
        }
        compose.onNodeWithText("第 180.5 话 · 未读").assertIsDisplayed()
        compose.onNodeWithText("番外2").assertIsDisplayed()
        compose.onNodeWithText("2026-10-18 停更公告").assertIsDisplayed()
        for (wrong in listOf("第 180 话", "第 2 话", "第 3 话", "第 2026 话")) {
            compose.onNodeWithText(wrong, substring = true).assertDoesNotExist()
        }
        compose.onNodeWithText("第 180.5 话 · P1", substring = true).assertIsDisplayed()
    }

    @Test fun progressRefreshKeepsTheInitialDetailViewport() {
        val progress = mutableStateOf<ComicProgressEntity?>(null)
        val loading = mutableStateOf(true)
        val chapters = (1..25).map { ComicChapter("c$it", "第 $it 话", order = it.toFloat()) }
        compose.setContent {
            MyApplicationTheme {
                ComicChaptersScreen(
                    book = SearchBook("book", "source", "测试漫画", "测试作者"),
                    chapters = if (loading.value) emptyList() else chapters,
                    loading = loading.value, error = null, downloadingChapters = emptySet(),
                    downloadProgress = emptyMap(), pausedChapters = emptySet(),
                    onBack = {}, onRetry = {}, onChapterClick = {}, onDownloadChapter = {},
                    onDownloadAll = {}, onPauseDownload = {}, onResumeDownload = {}, onCancelDownload = {},
                    progress = progress.value,
                )
            }
        }

        compose.onNodeWithText("测试作者").assertIsDisplayed()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithText("目录").assertIsDisplayed()
        compose.runOnIdle {
            progress.value = ComicProgressEntity(
                sourceId = "source", comicId = "book", lastChapterId = "c25",
                lastChapterIndex = 24, lastPageIndex = 3, lastPageCount = 20,
            )
        }
        compose.onNodeWithText("第 25 话 · P4", substring = true).assertIsDisplayed()
        compose.onNodeWithText("目录").assertIsDisplayed()
    }

    @Test fun selectAllDownloadsOnlyEligibleChapters() {
        val requested = mutableListOf<String>()
        compose.setContent {
            MyApplicationTheme {
                ComicChaptersScreen(
                    book = SearchBook("book", "source", "测试漫画", "测试作者"),
                    chapters = listOf(
                        ComicChapter("eligible", "第 1 话", order = 1f),
                        ComicChapter("cached", "第 2 话", order = 2f),
                        ComicChapter("external", "第 3 话", order = 3f, external = true),
                        ComicChapter("paused", "第 4 话", order = 4f),
                    ),
                    loading = false, error = null, downloadingChapters = emptySet(),
                    downloadProgress = emptyMap(), pausedChapters = setOf("paused"),
                    downloadedChapterIds = setOf("cached"),
                    onBack = {}, onRetry = {}, onChapterClick = {},
                    onDownloadChapter = { requested.add(it.id) },
                    onDownloadAll = {}, onPauseDownload = {}, onResumeDownload = {}, onCancelDownload = {},
                )
            }
        }

        compose.onNodeWithText("多选").performClick()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("已选 1 话").assertIsDisplayed()
        compose.onNodeWithText("下载 1 话 · 约 2.0 MB").performClick()
        compose.runOnIdle { assertEquals(listOf("eligible"), requested) }
    }
}
