package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.source.ComicChapter
import com.example.source.SearchBook
import com.example.ui.theme.MyApplicationTheme
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
}
