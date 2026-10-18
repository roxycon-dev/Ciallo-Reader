package com.example.transfer

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.data.Book
import com.example.data.Chapter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArchiveMergeDeviceTest {
    @Test fun missingOriginalMergesIntoExistingShelfBookWithNotesAndProgress() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "merge-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val instance = AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        val previous = instance.get(null)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        instance.set(null, db)
        try {
            val original = File(context.filesDir, "novel.txt").apply { writeText("同一本书的正文") }
            val book = db.bookDao().insertBookWithChapters(
                Book(title = "合并验证", filePath = original.path, totalChapters = 1, lastReadTime = 200, scrollOffset = 17),
                listOf(Chapter(bookId = 0, chapterOrder = 0, title = "第一章", content = "同一本书的正文")))
            val sql = db.openHelper.writableDatabase
            ArchiveRows.insert(sql, "highlights", JSONObject().put("bookId", book.id).put("chapterIndex", 0)
                .put("selectedText", "正文").put("note", "保留笔记").put("colorHex", "#abcdef").put("createdTime", 123L))
            val archive = File(context.cacheDir, "transfer.ciallo")
            val selection = ExportSelection(setOf(TransferCategory.BOOKS, TransferCategory.ANNOTATIONS, TransferCategory.HISTORY))
            PortableArchive.export(context, archive, selection) {}
            original.delete()
            sql.execSQL("DELETE FROM highlights")
            sql.execSQL("UPDATE books SET category='本机分类',lastReadTime=100,scrollOffset=3 WHERE id=?", arrayOf(book.id))
            ArchiveImporter.import(context, archive, ImportMode.MERGE) {}
            val merged = db.bookDao().getAllBooksSync().single()
            assertEquals(book.id, merged.id)
            assertEquals("本机分类", merged.category)
            assertEquals(17, merged.scrollOffset)
            assertEquals(book.id, ArchiveRows.all(sql, "highlights").single().getInt("bookId"))
            val returnArchive = File(context.cacheDir, "return.ciallo")
            PortableArchive.export(context, returnArchive, selection) {}
            ArchiveImporter.import(context, returnArchive, ImportMode.MERGE) {}
            assertEquals(1, db.bookDao().getBooksCount())
            assertEquals(1, ArchiveRows.all(sql, "highlights").size)
        } finally {
            instance.set(null, previous)
            db.close()
            root.deleteRecursively()
        }
    }
}
