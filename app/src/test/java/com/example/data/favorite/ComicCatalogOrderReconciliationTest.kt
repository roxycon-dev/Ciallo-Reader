package com.example.data.favorite

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.source.ComicChapter
import com.example.source.js.JsChapterOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ComicCatalogOrderReconciliationTest {
    private fun verify(withOldCatalog: Boolean, changedIds: Boolean = false) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val source = "js_manhuaren"
        val book = "order-regression-${System.nanoTime()}"
        val old = (1..3).map { ComicChapter("$book\u0001c$it", "第${it}话") }.reversed()
        val fresh = JsChapterOrder.normalize(old.map {
            if (changedIds) it.copy(id = "${it.id}-fresh") else it
        }, "manhuaren")
        val dao = database.favoriteDao()
        val repository = FavoriteRepository(dao, { null }, scope).apply { catalogContext = context }
        try {
            val states = old.mapIndexed { index, chapter -> ChapterReadEntity(source, book, chapter.id,
                status = ChapterReadState.READING.code, pageIndex = 4, pageCount = 20,
                chapterIndex = index, updatedAt = 1234, bookmarked = true) }
            dao.upsertChapterStates(states)
            val progress = ComicProgressEntity(source, book, old.first().id, lastChapterIndex = 0,
                lastPageIndex = 4, lastPageCount = 20, lastReadAt = 1234)
            dao.upsertProgress(progress)
            if (withOldCatalog) ChapterCatalog(context).write(source, book, old)

            repository.markSeen(source, book, fresh)

            val saved = dao.chapterStatesSync(source, book).associateBy { it.chapterId }
            assertEquals(3, saved.size)
            fresh.forEachIndexed { index, chapter ->
                val expected = states.single { it.chapterId.removeSuffix("-fresh") == chapter.id.removeSuffix("-fresh") }
                    .copy(chapterId = chapter.id, chapterIndex = index)
                assertEquals(expected, saved[chapter.id])
            }
            assertEquals(progress.copy(lastChapterId = fresh.last().id, lastChapterIndex = 2,
                seenTopChapterId = fresh.last().id, seenChapterCount = 3), dao.progress(source, book))
            assertEquals(fresh, ChapterCatalog(context).read(source, book))
            // A subsequent catalogue refresh must not reverse again or delete records.
            repository.markSeen(source, book, fresh)
            assertEquals(saved, dao.chapterStatesSync(source, book).associateBy { it.chapterId })
        } finally {
            scope.cancel()
            database.close()
        }
    }

    @Test fun changedOrderReindexesSameIdsWithoutLosingBookmarksOrPageProgress() = verify(withOldCatalog = true)
    @Test fun oldProgressWithoutCatalogCanBeReindexedByCurrentIds() = verify(withOldCatalog = false)
    @Test fun changedIdsStillMigrateByChapterIdentity() = verify(withOldCatalog = true, changedIds = true)
}
