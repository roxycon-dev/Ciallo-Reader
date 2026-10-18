@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.example.source

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.PreferencesManager
import com.example.data.TtsManager
import com.example.ui.ReaderScreen
import com.example.ui.comic.ComicReaderTestActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Render the actual EPUB-imported Room chapters using the production local novel reader. */
class PublicNovelReaderDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    @Test fun renderRealDownloadedIhuabenBook() = render("ihuaben")
    @Test fun renderRealDownloadedQuanbenBook() = render("quanben")
    @Test fun renderRealDownloadedSfacgBook() = render("sfacg")
    @Test fun renderRealDownloadedNoveliaBook() = render("auto_novel")
    @Test fun renderRealDownloadedWenkuLibraryBook() = render("wenku8_library")
    @Test fun renderRealDownloadedIxdzsBook() = render("ixdzs8")

    private fun render(sourceId: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = AppDatabase.getDatabase(context)
        val (book, chapters) = runBlocking {
            val book = db.bookDao().getAllBooksSync().filter { it.sourceId == sourceId && it.comicId.orEmpty().contains("audit-") }
                .maxByOrNull { it.id } ?: error("No live downloaded $sourceId book")
            val chapters = db.bookDao().getChaptersListForBook(book.id)
            val firstBody = if (sourceId in setOf("auto_novel", "wenku8_library", "ixdzs8")) chapters.size / 2 else
                chapters.indexOfFirst { it.content.count { c -> c in '\u4e00'..'\u9fff' } > 200 && !it.title.contains("目录") }.coerceAtLeast(0)
            book.copy(currentChapterIndex = firstBody, scrollOffset = 0) to chapters
        }
        assertTrue(chapters.isNotEmpty())
        val tts = TtsManager(context)
        try {
            compose.setContent { MaterialTheme {
                ReaderScreen(book = book, bookTitle = book.title, chapters = chapters,
                    readerLoading = false, loadedChapterIndices = chapters.indices.toSet(), readerLoadError = null,
                    onRetryLoad = {}, onEnsureChapterLoaded = { _, _ -> }, onBack = {},
                    onUpdateProgress = { _, _, _, _ -> }, prefs = PreferencesManager(context), ttsManager = tts,
                    highlights = emptyList(), bookmarks = emptyList(), onAddBookmark = { _, _, _, _, _ -> },
                    onDeleteBookmark = {}, onAddHighlight = { _, _, _, _, _ -> }, onDeleteHighlight = {},
                    searchResults = emptyList(), isSearching = false, onSearch = {}, onRecordTime = {})
            } }
            compose.waitForIdle()
            val file = File(context.filesDir, "public-novel-reader-$sourceId.png")
            // The reader performs text layout asynchronously outside Compose's idle tracking.
            // Capture the Android window too, including production native drawing surfaces.
            var screenshot: android.graphics.Bitmap? = null
            compose.waitUntil(timeoutMillis = 20_000) {
                val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    ?: return@waitUntil false
                screenshot = image
                val colors = HashSet<Int>()
                for (y in 140 until image.height - 140 step 7) for (x in 25 until image.width - 25 step 7) colors += image.getPixel(x, y)
                file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                colors.size > 12
            }
            assertNotNull(screenshot)
        } finally { tts.release() }
    }
}
