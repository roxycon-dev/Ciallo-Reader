package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryPerformanceQueryTest {
    @Test fun weeklyIndexPreservesDatesOrderDuplicatesAndSecondTotals() {
        val dates = listOf("2026-10-21", "2026-10-22", "2026-10-23")
        val records = (0..10_000).map { index ->
            ReadingRecord(id = index, bookId = null, bookTitle = "作品${index % 5}",
                dateStr = if (index % 3 == 0) "2026-09-01" else dates[index % dates.size], durationSeconds = index.toLong())
        }
        val indexed = com.example.ui.components.readingRecordsForDates(records, dates)
        dates.forEach { date ->
            assertEquals(records.filter { it.dateStr == date }, indexed[date].orEmpty())
        }
        assertFalse("Unshown history must not be retained in weekly index", indexed.containsKey("2026-09-01"))
    }

    @Test fun discoveryTitleProjectionMatchesFullHistoryAndUpdatesAfterDelete() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.bookDao()
            dao.insertReadingRecord(ReadingRecord(id = 1, bookId = null, bookTitle = "同名作品", dateStr = "2026-10-22", durationSeconds = 30))
            dao.insertReadingRecord(ReadingRecord(id = 2, bookId = null, bookTitle = "同名作品", dateStr = "2026-10-23", durationSeconds = 90))
            dao.insertReadingRecord(ReadingRecord(id = 3, bookId = null, bookTitle = "另一本", dateStr = "2026-10-23", durationSeconds = 60))
            assertEquals(dao.getAllReadingRecordsFlow().first().map { it.bookTitle }.toSet(), dao.getReadingTitles().first().toSet())
            dao.deleteReadingRecord(3)
            assertEquals(setOf("同名作品"), dao.getReadingTitles().first().toSet())
        } finally { db.close() }
    }

    @Test fun aggregateMatchesOriginalBookGroupingIncludingSourceIsolation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.bookDao()
            val books = listOf(
                Book(title = "a", filePath = "a", contentType = "COMIC", sourceId = "s", comicId = "c"),
                Book(title = "b", filePath = "b", contentType = "COMIC", sourceId = "s", comicId = "c"),
                Book(title = "c", filePath = "c", contentType = "COMIC", sourceId = "other", comicId = "c"),
                Book(title = "n", filePath = "n", sourceId = "s", comicId = "c"),
                Book(title = "local", filePath = "local", contentType = "COMIC"),
                Book(title = "blank", filePath = "blank", contentType = "COMIC", sourceId = "\u3000", comicId = "c"),
            )
            books.forEach { dao.insertBook(it) }
            val expected = books.filter { it.isComic && !it.sourceId.isNullOrBlank() && !it.comicId.isNullOrBlank() }
                .groupingBy { it.sourceId to it.comicId }.eachCount()
            fun List<DownloadedComicCount>.counts() = filter { !it.sourceId.isNullOrBlank() && !it.comicId.isNullOrBlank() }
                .associate { (it.sourceId to it.comicId) to it.chapterCount }
            assertEquals(expected, dao.getDownloadedComicCounts().first().counts())
            val before = dao.getDownloadedComicCounts().first()
            val first = dao.getAllBooksSync().first()
            dao.updateProgress(first.id, 3, 987, true, 12345)
            assertEquals(before, dao.getDownloadedComicCounts().first())
            dao.deleteBook(first)
            assertNotEquals(before, dao.getDownloadedComicCounts().first())
        } finally { db.close() }
    }

    @Test fun startupCandidatesMatchOldPerBookPredicateAtExactLengthBoundary() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.bookDao()
            for ((i, length) in listOf(0, 100, 101, 150).withIndex()) {
                val id = dao.insertBook(Book(title = "book$i", filePath = "$i", contentType = if (i == 3) "COMIC" else "NOVEL")).toInt()
                dao.insertChapters(listOf(Chapter(bookId = id, chapterOrder = 0, title = "chapter", content = "字".repeat(length))))
            }
            val expected = dao.getAllBooksSync().filter { !it.isComic && dao.hasOversizedChapter(it.id, 100) }.map { it.id }.toSet()
            assertEquals(expected, dao.getBooksWithOversizedChapters(100).map { it.id }.toSet())
            assertEquals(1, expected.size)
        } finally { db.close() }
    }
}
