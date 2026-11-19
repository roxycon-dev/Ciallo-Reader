@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.example.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.data.BookRepository
import com.example.data.PreferencesManager
import com.example.data.TtsManager
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class LocalNovelListeningDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test fun importedNovelOpensPlayerAndDictionaryFromReader() {
        val context = compose.activity.applicationContext
        val source = File.createTempFile("ciallo-listening-", ".txt", context.cacheDir)
        val prefs = PreferencesManager(context)
        val previousMode = prefs.pageTurnMode
        val tts = TtsManager(context)
        try {
            val body = List(16) { "The reader returned to the library for another chapter." }.joinToString("\n")
            source.writeText("第一章 图书馆\n$body\n第二章 归途\n$body")
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            val (book, chapters) = try {
                runBlocking {
                    val imported = BookRepository(context, db.bookDao(), db)
                        .importBookFromUri(Uri.fromFile(source), "茶间读书.txt").getOrThrow()
                    imported to db.bookDao().getChaptersListForBook(imported.id)
                }
            } finally {
                db.close()
            }
            prefs.pageTurnMode = 4
            compose.setContent {
                MyApplicationTheme {
                    ReaderScreen(
                        book = book, bookTitle = book.title, chapters = chapters,
                        readerLoading = false, loadedChapterIndices = chapters.indices.toSet(), readerLoadError = null,
                        onRetryLoad = {}, onEnsureChapterLoaded = { _, _ -> }, onBack = {},
                        onUpdateProgress = { _, _, _, _ -> }, prefs = prefs, ttsManager = tts,
                        highlights = emptyList(), bookmarks = emptyList(), onAddBookmark = { _, _, _, _, _ -> },
                        onDeleteBookmark = {}, onAddHighlight = { _, _, _, _, _ -> }, onDeleteHighlight = {},
                        searchResults = emptyList(), isSearching = false, onSearch = {}, onRecordTime = {}
                    )
                }
            }
            compose.onNodeWithText("The reader returned", substring = true).performTouchInput {
                longClick(Offset(width * .28f, 24f))
            }
            compose.onNodeWithText("正文选段").assertIsDisplayed()
            compose.onNodeWithText("查词").performClick()
            compose.onNodeWithText("输入单词或短语").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭").performClick()
            compose.onRoot().performTouchInput { click(center) }
            compose.onAllNodesWithContentDescription("听书模式").onFirst().performClick()
            compose.onNodeWithText("从阅读位置听起").assertIsDisplayed()
            compose.onNodeWithText("播放设置").performClick()
            compose.onNodeWithText("语速").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭").performClick()
            compose.onNodeWithText("从阅读位置听起").assertDoesNotExist()
            compose.onNodeWithContentDescription("更多").performClick()
            compose.onNodeWithText("词典查词").performClick()
            compose.onNodeWithText("从正文选词").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭").performClick()
            compose.onNodeWithText("从正文选词").assertDoesNotExist()
        } finally {
            compose.runOnIdle { tts.release() }
            prefs.pageTurnMode = previousMode
            source.delete()
        }
    }
}
