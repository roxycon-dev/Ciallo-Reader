package com.example.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.library.OnlineNovelChapterState
import com.example.library.OnlineNovelPage
import com.example.source.ComicChapter
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnlineNovelReaderDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test fun keepsReadingDuringFailedChapterSwitchAndRevealsRetriedText() {
        val first = ComicChapter("first", "第一章")
        val second = ComicChapter("second", "第二章")
        val initial = OnlineNovelPage(first, body("旧"), 1)
        val loaded = OnlineNovelPage(second, body("新"), 1)
        val state = mutableStateOf(OnlineNovelChapterState(requested = first, loading = true))
        var requests = 0
        var retries = 0
        compose.setContent {
            MyApplicationTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "reader") {
                    composable("reader") {
                        NovelReaderScreen(
                            bookTitle = "在线小说验证",
                            state = state.value,
                            hasNextChapter = state.value.page?.chapter == first,
                            hasPrevChapter = state.value.page?.chapter == second,
                            onBack = {},
                            onRetry = {
                                retries++
                                state.value = state.value.copy(loading = true, error = null)
                            },
                            onLoadPrev = {},
                            onLoadNext = {
                                requests++
                                state.value = state.value.copy(requested = second, loading = true)
                            }
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("正在加载正文").assertIsDisplayed()
        compose.runOnIdle { state.value = OnlineNovelChapterState(initial, first) }
        compose.onNodeWithText("旧段落 0").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一章").performClick()
        compose.onNodeWithContentDescription("下一章").assertIsNotEnabled()
        compose.onNodeWithText("旧段落 0").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, requests)
            state.value = state.value.copy(loading = false, error = "夹具章节失败")
        }
        compose.onNodeWithText("夹具章节失败").assertIsDisplayed()
        compose.onNodeWithText("旧段落 0").assertIsDisplayed()
        compose.onNodeWithContentDescription("重试").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(second, state.value.requested)
            state.value = OnlineNovelChapterState(loaded, second)
        }
        compose.onNodeWithText("新段落 0").assertIsDisplayed()
        compose.onNodeWithText("旧段落 0").assertDoesNotExist()
        compose.onNodeWithContentDescription("本章进度").performSemanticsAction(SemanticsActions.SetProgress) {
            assertTrue(it(.60f))
        }
        compose.onNodeWithText("新段落 0").assertDoesNotExist()
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithContentDescription("本章进度").assertDoesNotExist()
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithContentDescription("本章进度").assertIsDisplayed()
    }

    private fun body(prefix: String): String = List(60) { "${prefix}段落 $it" }.joinToString("\n\n")
}
