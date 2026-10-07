package com.example.ui.comic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.data.Book
import com.example.data.Chapter
import com.example.data.PreferencesManager
import com.example.data.TtsManager
import com.example.ui.ReaderScreen
import com.example.ui.theme.MyApplicationTheme

/** Debug-only host for the actual novel reader, using generated prose and debug-app preferences. */
class ReaderPolishProbeActivity : ComponentActivity() {
    private var speech: TtsManager? = null
    @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PreferencesManager(this)
        prefs.readerTheme = intent.getIntExtra("theme", 2)
        prefs.fontSize = 18f
        prefs.lineHeight = 28f
        prefs.pageTurnMode = intent.getIntExtra("mode", 4)
        val tts = TtsManager(this).also { speech = it }
        val prose = (1..60).joinToString("\n\n") {
            "秋日的风穿过长廊，窗外的树影轻轻摇晃。她翻开书页，指尖停在一行熟悉的文字上。远处传来钟声，阳光在纸面上慢慢移动，故事在安静的午后继续。"
        }
        setContent {
            MyApplicationTheme(darkTheme = false, colorPrimaryIndex = 4, colorSecondaryIndex = 4) {
                ReaderScreen(
                    book = Book(id = 99125, title = "秋日来信", author = "阅读体验样本", filePath = "", totalChapters = 1),
                    bookTitle = "秋日来信", chapters = listOf(Chapter(bookId = 99125, chapterOrder = 0, title = "第一章 · 午后", content = prose)),
                    readerLoading = false, loadedChapterIndices = setOf(0), readerLoadError = null,
                    onRetryLoad = {}, onEnsureChapterLoaded = { _, _ -> }, onBack = { finish() },
                    onUpdateProgress = { _, _, _, _ -> }, prefs = prefs, ttsManager = tts,
                    highlights = emptyList(), bookmarks = emptyList(),
                    onAddBookmark = { _, _, _, _, _ -> }, onDeleteBookmark = {},
                    onAddHighlight = { _, _, _, _, _ -> }, onDeleteHighlight = {},
                    searchResults = emptyList(), isSearching = false, onSearch = {}, onRecordTime = {},
                )
            }
        }
    }
    override fun onDestroy() {
        speech?.release()
        super.onDestroy()
    }
}
