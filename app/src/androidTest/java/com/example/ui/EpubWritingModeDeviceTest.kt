@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.example.ui

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.data.BookRepository
import com.example.data.PreferencesManager
import com.example.data.TtsManager
import com.example.data.decodeBookSpans
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EpubWritingModeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test fun importedEpubRendersVerticalColumnsAndLinkedBoldStyles() {
        val context = compose.activity.applicationContext
        val epub = File.createTempFile("source-styles-", ".epub", context.cacheDir)
        val tts = TtsManager(context)
        val prefs = PreferencesManager(context)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        var bookId = 0
        var preference = "auto"
        try {
            val paragraphs = "図書館の静かな午後に、読者が本を手に取りました。".repeat(100)
            ZipOutputStream(epub.outputStream()).use { out ->
                val fixtures = mapOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="book/book.opf"/></rootfiles></container>""",
                    "book/book.opf" to """<package version="3.0"><metadata><title>縦書きの本</title></metadata>
                        <manifest><item id="first" href="text/chapter.xhtml"/></manifest><spine><itemref idref="first"/></spine></package>""",
                    "book/style/read.css" to ".vertical { -epub-writing-mode: vertical-rl; } .bold { font-weight: 700; }",
                    "book/text/chapter.xhtml" to """<html><head><link rel="stylesheet" href="../style/read.css"/></head>
                        <body class="vertical"><h1>第一章</h1><p><span class="bold">太字の文</span> $paragraphs</p></body></html>"""
                )
                fixtures.forEach { (path, contents) ->
                    out.putNextEntry(ZipEntry(path))
                    out.write(contents.toByteArray())
                    out.closeEntry()
                }
            }
            val (book, chapters) = runBlocking {
                val repo = BookRepository(context, db.bookDao(), db)
                val saved = repo.importBookFromUri(Uri.fromFile(epub), "Fixture.epub").getOrThrow()
                saved to db.bookDao().getChaptersListForBook(saved.id)
            }
            val chapter = chapters.single()
            bookId = book.id
            preference = prefs.novelWritingMode(book.id)
            prefs.setNovelWritingMode(book.id, "auto")
            assertEquals("vertical-rl", chapter.writingMode)
            assertTrue(decodeBookSpans(chapter.formatting).any {
                chapter.content.substring(it.start, it.end) == "太字の文"
            })
            compose.setContent {
                MyApplicationTheme {
                    ReaderScreen(
                        book = book, bookTitle = book.title, chapters = chapters,
                        readerLoading = false, loadedChapterIndices = setOf(0), readerLoadError = null,
                        onRetryLoad = {}, onEnsureChapterLoaded = { _, _ -> }, onBack = {},
                        onUpdateProgress = { _, _, _, _ -> }, prefs = prefs, ttsManager = tts,
                        highlights = emptyList(), bookmarks = emptyList(), onAddBookmark = { _, _, _, _, _ -> },
                        onDeleteBookmark = {}, onAddHighlight = { _, _, _, _, _ -> }, onDeleteHighlight = {},
                        searchResults = emptyList(), isSearching = false, onSearch = {}, onRecordTime = {}
                    )
                }
            }
            val last = AtomicReference("waiting for WebView")
            try {
                compose.waitUntil(18_000) {
                    val callback = CountDownLatch(1)
                    compose.activity.runOnUiThread {
                        val view = compose.activity.window.decorView.findWebView()
                        if (view == null) callback.countDown()
                        else view.evaluateJavascript(
                            """(() => {
                                const reader = document.getElementById('reader');
                                const bold = [...reader.querySelectorAll('[data-offset]')]
                                    .find(el => el.textContent === '太字の文');
                                const range = document.createRange();
                                const glyph = i => {
                                    range.setStart(bold.firstChild, i);
                                    range.setEnd(bold.firstChild, i + 1);
                                    return range.getBoundingClientRect();
                                };
                                const first = bold && glyph(0), second = bold && glyph(1);
                                return JSON.stringify({mode:getComputedStyle(reader).writingMode,
                                    bold:!!bold && getComputedStyle(bold).fontWeight === '700',
                                    stacked:!!first && Math.abs(first.x - second.x) < 2 && second.y > first.y,
                                    width:reader.clientWidth, extent:reader.scrollWidth,
                                    ready:document.readyState, characters:document.body?.textContent?.length});
                            })()""".trimIndent()
                        ) {
                            last.set(it)
                            callback.countDown()
                        }
                    }
                    callback.await(1200, TimeUnit.MILLISECONDS)
                    val info = runCatching { org.json.JSONObject(org.json.JSONTokener(last.get()).nextValue() as String) }.getOrNull()
                    info != null && info.optString("mode") == "vertical-rl" && info.optBoolean("bold") &&
                        info.optBoolean("stacked") &&
                        info.optInt("extent") > info.optInt("width")
                }
            } catch (failure: AssertionError) {
                throw AssertionError("Vertical runtime: " + last.get(), failure)
            } catch (failure: Exception) {
                throw AssertionError("Vertical runtime: " + last.get(), failure)
            }
        } finally {
            compose.runOnIdle { tts.release() }
            if (bookId > 0) prefs.setNovelWritingMode(bookId, preference)
            db.close()
            epub.delete()
        }
    }

    private fun View.findWebView(): WebView? {
        if (this is WebView) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) getChildAt(index).findWebView()?.let { return it }
        }
        return null
    }
}
