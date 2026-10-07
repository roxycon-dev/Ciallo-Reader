package com.example.data.favorite

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.source.*
import com.example.source.impl.MockBookSource
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FavoriteSourceHealthTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    @After fun cleanup() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    private fun source(result: suspend () -> SourceResult<List<ComicChapter>>) = object : ComicSource, BookSource by MockBookSource(context) {
        override val id = "js_copy_manga"
        override suspend fun getChapters(bookId: String) = result()
        override suspend fun getChapterImages(chapterId: String) = SourceResult.Success(emptyList<String>())
    }
    private suspend fun checked() = withTimeout(5_000) {
        while (db.favoriteDao().favorite("js_copy_manga", "baihetianxin")?.lastCheckedAt == 0L) delay(10)
    }
    private suspend fun insert(alive: Boolean = false) {
        db.favoriteDao().insertFavorite(FavoriteEntity("js_copy_manga", "baihetianxin", "百合甜心", sourceAlive = alive))
        db.favoriteDao().insertFavorite(FavoriteEntity("js_manhuaren", "other", "百合甜心", sourceAlive = true, lastCheckedAt = System.currentTimeMillis()))
    }
    @Test fun transientFailureRepairsOldWarningWithoutChangingTheOtherSource() = runBlocking {
        insert()
        val repo = FavoriteRepository(db.favoriteDao(), { source { SourceResult.Error(SourceException.NetworkError("timeout")) } }, scope)
        assertEquals(1, repo.checkUpdates())
        checked()
        assertTrue(db.favoriteDao().favorite("js_copy_manga", "baihetianxin")!!.sourceAlive)
        assertTrue(db.favoriteDao().favorite("js_manhuaren", "other")!!.sourceAlive)
    }
    @Test fun removedSourceIsStillMarkedUnavailable() = runBlocking {
        insert(true)
        FavoriteRepository(db.favoriteDao(), { null }, scope).checkUpdates()
        checked()
        assertFalse(db.favoriteDao().favorite("js_copy_manga", "baihetianxin")!!.sourceAlive)
    }
    @Test fun healthUpdateDoesNotResurrectRemovedFavoritesOrOverwriteCategories() = runBlocking {
        insert()
        val dao = db.favoriteDao()
        dao.moveFavoritesToCategory(listOf("js_copy_manga::baihetianxin"), "新分类")
        dao.updateSourceHealth("js_copy_manga", "baihetianxin", true)
        assertEquals("新分类", dao.favorite("js_copy_manga", "baihetianxin")!!.categoryName)
        dao.deleteFavorite("js_copy_manga", "baihetianxin")
        dao.recordSourceCheck("js_copy_manga", "baihetianxin", false, 1234)
        assertNull(dao.favorite("js_copy_manga", "baihetianxin"))
    }
    @Test fun cancellationDoesNotWriteFalseWarnings() = runBlocking {
        insert(true)
        FavoriteRepository(db.favoriteDao(), { source { throw CancellationException("cancelled") } }, scope).checkUpdates()
        delay(150)
        assertTrue(db.favoriteDao().favorite("js_copy_manga", "baihetianxin")!!.sourceAlive)
        assertEquals(0, db.favoriteDao().favorite("js_copy_manga", "baihetianxin")!!.lastCheckedAt)
    }
}
