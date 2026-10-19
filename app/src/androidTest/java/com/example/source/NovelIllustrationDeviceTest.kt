package com.example.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AppDatabase
import com.example.download.*
import com.example.source.impl.AutoNovelSource
import com.example.source.impl.Wenku8LibrarySource
import com.example.ui.reader.NovelInlineImages
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real titles reported by the user; no replacement books or copyrighted fixtures. */
@RunWith(AndroidJUnit4::class)
class NovelIllustrationDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("novelIllustrations") == "true")
    private fun <T> value(result: SourceResult<T>): T = when (result) {
        is SourceResult.Success -> result.data
        is SourceResult.Error -> throw result.exception
    }

    private suspend fun download(book: SearchBook, info: DownloadInfo): Pair<DownloadTaskEntity, List<com.example.data.Chapter>> {
        val manager = DownloadManager(context)
        val db = AppDatabase.getDatabase(context)
        val id = DownloadManager.taskId(book.sourceId, book.id)
        manager.enqueueDownload(DownloadRequest(book.id, book.title, book.author, book.sourceId,
            info.url, info.format, book.cover, novelSnapshot = book), info.referer, info.headers).join()
        val task = withTimeout(300_000) {
            while (true) {
                val current = db.downloadTaskDao().getTaskById(id)
                if (current?.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED)) return@withTimeout current!!
                delay(400)
            }
            error("unreachable")
        }
        assertEquals(task.errorMessage, DownloadStatus.COMPLETED, task.status)
        val imported = db.bookDao().getBookBySourceResource(book.sourceId, book.id) ?: error("Book not committed")
        assertEquals(book.id, imported.comicId)
        return task to db.bookDao().getChaptersListForBook(imported.id)
    }

    @Test fun wenkuYouthRomanceFreshDownloadHasReadableIllustrations() = runBlocking<Unit> {
        enabled()
        val source = Wenku8LibrarySource(context)
        val book = value(source.search("青春恋爱物语")).single { it.id == "1213" }
        assertEquals("果然我的青春恋爱喜剧搞错了", book.title)
        val (task, chapters) = download(book, value(source.getDownloadInfo(book.id)))
        val specs = chapters.flatMap { NovelInlineImages.TOKEN_REGEX.findAll(it.content).map { match -> match.groupValues[1] }.toList() }.distinct()
        assertTrue("The requested complete series must contain illustrations", specs.size > 100)
        for (spec in specs) {
            assertFalse("New imports must not persist extraction paths", spec.substringAfter('!').contains("../"))
            val image = NovelInlineImages.NovelImageCache.decodeSampledFromEpub(spec)
            assertNotNull("Unreadable illustration: " + spec.substringAfter('!'), image)
            image!!.recycle()
        }
        File(context.filesDir, "novel-illustrations-wenku.json").writeText(JSONObject().put("passed", true)
            .put("title", book.title).put("bookId", book.id).put("bytes", File(task.filePath).length())
            .put("chapters", chapters.size).put("decodedIllustrations", specs.size).toString(2))
    }

    @Test fun noveliaParallelSpringDownloadsAllChaptersWithSourceTextIntact() = runBlocking<Unit> {
        enabled()
        val source = AutoNovelSource(context)
        val title = "青春猪头少年不会梦到平行弹簧"
        val book = value(source.search(title)).single { it.id == "hameln/387502" }
        assertEquals(title, book.title)
        val toc = value(source.getChapters(book.id))
        val (task, chapters) = download(book, value(source.getDownloadInfo(book.id)))
        val summary = GuestNovelIntegrity.epub(File(task.filePath))
        assertEquals(toc.map { it.title.filterNot(Char::isWhitespace) }, summary.titles.map { it.filterNot(Char::isWhitespace) })
        assertTrue(chapters.size >= toc.size)
        assertTrue(chapters.all { it.content.isNotBlank() })
        assertEquals(1, summary.replacementCharacters)
        assertEquals(1, chapters.sumOf { it.content.count { c -> c == '\uFFFD' } })
        File(context.filesDir, "novel-illustrations-novelia.json").writeText(JSONObject().put("passed", true)
            .put("title", book.title).put("bookId", book.id).put("bytes", File(task.filePath).length())
            .put("sourceChapters", toc.size).put("importedChapters", chapters.size)
            .put("preservedSourceMissingCharacters", summary.replacementCharacters).toString(2))
    }

    @Test fun existingZlibraryBooksDecodeStoredLegacyReferencesWithoutReimport() = runBlocking<Unit> {
        enabled()
        val db = AppDatabase.getDatabase(context)
        var count = 0
        for (book in db.bookDao().getAllBooksSync().filter { it.sourceId == "zlibrary" }) {
            val chapters = db.bookDao().getChaptersListForBook(book.id)
            val legacy = chapters.flatMap { NovelInlineImages.TOKEN_REGEX.findAll(it.content).map { it.groupValues[1] }.toList() }
                .filter { it.startsWith("epzip:") && it.substringAfter('!').startsWith("../") }.distinct()
            for (spec in legacy) {
                val image = NovelInlineImages.NovelImageCache.decodeSampledFromEpub(spec)
                assertNotNull("Stored legacy reference cannot decode", image)
                image!!.recycle(); count++
            }
            assertEquals("Reading images must preserve progress and annotations", book, db.bookDao().getBookById(book.id))
            assertEquals(chapters, db.bookDao().getChaptersListForBook(book.id))
        }
        assertTrue("Must exercise already stored Android alias references", count > 0)
        File(context.filesDir, "novel-illustrations-legacy.json").writeText(JSONObject().put("passed", true)
            .put("decodedLegacyIllustrations", count).put("chaptersAndProgressUnchanged", true).toString(2))
    }
}
