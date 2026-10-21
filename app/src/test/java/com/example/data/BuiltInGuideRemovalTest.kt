package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The built-in guide book is retired: it is neither seeded nor kept on existing installs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BuiltInGuideRemovalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun freshInstallNoLongerSeedsTheGuide() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            BookRepository(context, db.bookDao(), db).checkAndSeedDefaultBooks()
            assertNull(db.bookDao().getBookByFilePath(GUIDE_FILE_PATH))
            assertEquals(0, db.bookDao().getBooksCount())
        } finally { db.close() }
    }

    @Test fun previouslySeededGuideIsRemovedWithItsChapters() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.bookDao()
        val bookId = dao.insertBook(
            Book(
                title = "《Ciallo Reader使用指南》",
                author = "Ciallo Reader团队",
                filePath = GUIDE_FILE_PATH,
                totalChapters = 2,
                contentType = "NOVEL",
            )
        ).toInt()
        dao.insertChapters(
            listOf(
                Chapter(bookId = bookId, chapterOrder = 0, title = "第一章：欢迎使用 Ciallo Reader", content = "正文"),
                Chapter(bookId = bookId, chapterOrder = 1, title = "第二章：书架管理与图书导入", content = "正文"),
            )
        )
        try {
            BookRepository(context, dao, db).checkAndSeedDefaultBooks()
            assertNull(dao.getBookByFilePath(GUIDE_FILE_PATH))
            assertTrue(dao.getChaptersListForBook(bookId).isEmpty())
        } finally { db.close() }
    }

    private companion object {
        const val GUIDE_FILE_PATH = "ciallo_guide_novel"
    }
}
