@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.example.source

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.ui.ReaderScreen
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.reader.NovelInlineImages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Show actual imported image chapters through the production reader, not a test Image. */
class NovelIllustrationReaderDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    @Test fun wenkuYouthRomanceIllustrationAppearsInReader() = render("wenku8_library", "果然我的青春恋爱喜剧搞错了")
    @Test fun zlibraryBunnyGirlIllustrationAppearsInReader() = render("zlibrary", "青春猪头少年不会梦到兔女郎学姐")

    private fun render(sourceId: String, title: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("novelIllustrations") == "true")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = AppDatabase.getDatabase(context)
        val (book, chapters) = runBlocking {
            val resourceId = if (sourceId == "wenku8_library") "1213" else
                org.json.JSONObject(File(context.filesDir, "zlibrary-download-audit.json").readText()).also {
                    assertEquals(title, it.getString("title"))
                }.getString("bookId")
            val saved = db.bookDao().getBookBySourceResource(sourceId, resourceId) ?: error("Requested book not imported")
            val chapters = db.bookDao().getChaptersListForBook(saved.id)
            val index = chapters.indexOfFirst {
                val match = NovelInlineImages.TOKEN_REGEX.find(it.content)
                val entry = match?.groupValues?.get(1)?.substringAfter('!').orEmpty()
                match != null && match.range.first < 80 &&
                    !entry.contains("cover", ignoreCase = true) && !entry.contains("logo", ignoreCase = true) &&
                    !it.title.contains("封面") && !it.title.contains("版权")
            }
            assertTrue("Actual book needs an image page", index >= 0)
            saved.copy(currentChapterIndex = index, scrollOffset = 0) to chapters
        }
        val prefs = PreferencesManager(context)
        val previousMode = prefs.pageTurnMode
        val previousBrightness = prefs.readerBrightness
        prefs.pageTurnMode = 4
        prefs.readerBrightness = 1f
        val tts = TtsManager(context)
        try {
            compose.setContent { MaterialTheme {
                ReaderScreen(book = book, bookTitle = book.title, chapters = chapters,
                    readerLoading = false, loadedChapterIndices = chapters.indices.toSet(), readerLoadError = null,
                    onRetryLoad = {}, onEnsureChapterLoaded = { _, _ -> }, onBack = {},
                    onUpdateProgress = { _, _, _, _ -> }, prefs = prefs, ttsManager = tts,
                    highlights = emptyList(), bookmarks = emptyList(), onAddBookmark = { _, _, _, _, _ -> },
                    onDeleteBookmark = {}, onAddHighlight = { _, _, _, _, _ -> }, onDeleteHighlight = {},
                    searchResults = emptyList(), isSearching = false, onSearch = {}, onRecordTime = {})
            } }
            compose.waitForIdle()
            // Let the Android activity/window transition finish before pixel checks.
            Thread.sleep(750)
            compose.waitUntil(30_000) {
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return@waitUntil false
                try {
                    val colors = HashSet<Int>()
                    var colored = 0
                    for (y in image.height / 5 until image.height * 4 / 5 step 4) {
                        for (x in image.width / 6 until image.width * 5 / 6 step 4) {
                            val pixel = image.getPixel(x, y)
                            val r = android.graphics.Color.red(pixel)
                            val g = android.graphics.Color.green(pixel)
                            val b = android.graphics.Color.blue(pixel)
                            colors += pixel
                            if (maxOf(r, g, b) - minOf(r, g, b) > 35) colored++
                        }
                    }
                    val visible = colors.size > 200 && colored > 300
                    if (visible) File(context.filesDir, "novel-illustration-reader-$sourceId.png").outputStream().use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    visible
                } finally { image.recycle() }
            }
        } finally { tts.release(); prefs.pageTurnMode = previousMode; prefs.readerBrightness = previousBrightness }
    }
}
