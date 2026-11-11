package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppDatabaseMigrationTest {
    @Test
    fun versionTwelveAddsTransferTablesAndIndexWithoutLosingBooks()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="m12-${System.nanoTime()}.db"
        try {
            val seed=Room.databaseBuilder(context,AppDatabase::class.java,name)
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
            seed.bookDao().insertBookWithChapters(Book(title="升级前的书",filePath="",totalChapters=1),listOf(Chapter(bookId=0,chapterOrder=0,title="第一章",content="保留正文")))
            val sql=seed.openHelper.writableDatabase
            sql.execSQL("DROP TABLE transfer_identities");sql.execSQL("DROP TABLE transfer_receipts")
            sql.execSQL("DROP INDEX index_chapters_bookId_chapterOrder");sql.version=12;seed.close()
            val upgraded=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_12_13)
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
            try {
                val book=upgraded.bookDao().getAllBooksSync().single()
                assertEquals("升级前的书",book.title)
                assertEquals("保留正文",upgraded.bookDao().getChaptersListForBook(book.id).single().content)
                upgraded.openHelper.readableDatabase.query("SELECT COUNT(*) FROM transfer_receipts").use {it.moveToFirst();assertEquals(0,it.getInt(0))}
            }finally{upgraded.close()}
        }finally{context.deleteDatabase(name)}
    }

    @Test
    fun upgradingVersion11PreservesChapterStateAndAddsBookmarkAndGodMomentTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-11-12-${System.nanoTime()}.db"
        try {
            // Start from a complete Room schema, then restore the two v11 table shapes.
            // This makes the reopened database run Room's full schema validation.
            val seed = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .build()
            val sql = seed.openHelper.writableDatabase
            sql.execSQL(
                "INSERT INTO comic_chapter_read " +
                    "(sourceId, comicId, chapterId, status, pageIndex, pageCount, chapterIndex, updatedAt, bookmarked) " +
                    "VALUES ('source', 'comic', 'chapter', 2, 5, 10, 3, 1234, 0)"
            )
            sql.execSQL("ALTER TABLE comic_chapter_read RENAME TO old_comic_chapter_read")
            sql.execSQL(
                "CREATE TABLE comic_chapter_read (" +
                    "sourceId TEXT NOT NULL, comicId TEXT NOT NULL, chapterId TEXT NOT NULL, " +
                    "status INTEGER NOT NULL, pageIndex INTEGER NOT NULL, pageCount INTEGER NOT NULL, " +
                    "chapterIndex INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(sourceId, comicId, chapterId))"
            )
            sql.execSQL(
                "INSERT INTO comic_chapter_read SELECT sourceId, comicId, chapterId, status, " +
                    "pageIndex, pageCount, chapterIndex, updatedAt FROM old_comic_chapter_read"
            )
            sql.execSQL("DROP TABLE old_comic_chapter_read")
            sql.execSQL(
                "CREATE INDEX index_comic_chapter_read_sourceId_comicId " +
                    "ON comic_chapter_read (sourceId, comicId)"
            )
            sql.execSQL("DROP TABLE god_moments")
            sql.version = 11
            seed.close()

            val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13)
                .allowMainThreadQueries()
                .build()
            try {
                val chapter = upgraded.favoriteDao().chapterStatesSync("source", "comic").single()
                assertEquals("chapter", chapter.chapterId)
                assertEquals(2, chapter.status)
                assertEquals(5, chapter.pageIndex)
                assertEquals(10, chapter.pageCount)
                assertEquals(3, chapter.chapterIndex)
                assertEquals(1234L, chapter.updatedAt)
                assertFalse(chapter.bookmarked)
                assertEquals(0, upgraded.godMomentDao().count())
            } finally {
                upgraded.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
