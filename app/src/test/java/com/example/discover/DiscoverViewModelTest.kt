package com.example.discover

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiscoverViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = mutableListOf<DiscoverViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private fun model(manager: SourceManager): DiscoverViewModel {
        val store = DiscoverStore(ApplicationProvider.getApplicationContext<Context>())
        store.prefs.edit().clear().putBoolean("cache", false).apply()
        return DiscoverViewModel(manager, store).also(models::add)
    }
    private fun page(source: String, id: String, more: Boolean = false) = SourceResult.Success(DiscoverPage(
        listOf(DiscoverEntry(SearchBook(id, source, id, "作者"), clicks = 42)), hasMore = more))
    @Test fun fastSourcePublishesBeforeSlowSourceFinishes() = runTest(dispatcher) {
        val wait = CompletableDeferred<Unit>()
        val manager = SourceManager()
        manager.registerSource(object : DiscoverUiTest.FixtureSource("slow") {
            override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> { wait.await(); return page(id, "slow-book") }
        })
        manager.registerSource(object : DiscoverUiTest.FixtureSource("fast") {
            override suspend fun discover(query: DiscoverQuery) = page(id, "fast-book")
        })
        val model = model(manager)
        model.configure("aggregate", DiscoverDimension.CLICKS, "总")
        assertTrue(model.state.value.loading)
        assertEquals("fast-book", model.state.value.entries.single().book.id)
        wait.complete(Unit)
        assertFalse(model.state.value.loading)
        assertEquals(2, model.state.value.entries.size)
    }
    @Test fun switchingDimensionCancelsObsoleteResults() = runTest(dispatcher) {
        val wait = CompletableDeferred<Unit>()
        val manager = SourceManager()
        manager.registerSource(object : DiscoverUiTest.FixtureSource("source") {
            override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> {
                if (query.dimension == DiscoverDimension.CLICKS) wait.await()
                return page(id, query.dimension.name)
            }
        })
        val model = model(manager)
        model.configure("aggregate", DiscoverDimension.CLICKS, "总")
        model.configure("aggregate", DiscoverDimension.RANKING, "热门")
        wait.complete(Unit)
        assertEquals("RANKING", model.state.value.entries.single().book.id)
    }
    @Test fun failedPageDoesNotLoopOrSkipItsCursor() = runTest(dispatcher) {
        val calls = mutableListOf<Int>()
        var fail = true
        val manager = SourceManager()
        manager.registerSource(object : DiscoverUiTest.FixtureSource("source") {
            override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> {
                calls += query.page
                if (query.page == 2 && fail) return SourceResult.Error(SourceException.NetworkError("断网"))
                return page(id, "page-${query.page}", query.page == 1)
            }
        })
        val model = model(manager)
        model.configure("aggregate", DiscoverDimension.CLICKS, "总")
        model.more(); model.more()
        assertEquals(listOf(1,2), calls)
        assertEquals(1, model.state.value.entries.size)
        fail = false; model.retry()
        assertEquals(listOf(1,2,2), calls)
        assertEquals(2, model.state.value.entries.size)
        assertTrue(model.state.value.errors.isEmpty())
    }
    @Test fun disabledSourceCannotParticipateInAggregate() = runTest(dispatcher) {
        val manager = SourceManager()
        manager.registerSource(DiscoverUiTest.FixtureSource("a"))
        manager.registerSource(DiscoverUiTest.FixtureSource("b"), defaultEnabled = false)
        val model = model(manager)
        model.configure("aggregate", DiscoverDimension.CLICKS, "总")
        assertTrue(model.state.value.entries.all { it.book.sourceId == "a" })
    }
    @Test fun adultSourceWithMissingTagsIsHiddenByDefault() = runTest(dispatcher) {
        val manager = SourceManager()
        manager.registerSource(DiscoverUiTest.FixtureSource("a"))
        manager.registerSource(DiscoverUiTest.FixtureSource("js_jm"))
        val model = model(manager)
        model.configure("aggregate", DiscoverDimension.CLICKS, "总")
        assertTrue(model.state.value.entries.isNotEmpty())
        assertTrue(model.state.value.entries.all { it.book.sourceId == "a" })
    }
    @Test fun refreshFailureKeepsVisibleRowsEvenWhenDiskCacheIsOff() = runTest(dispatcher) {
        var fail = false
        val manager = SourceManager()
        manager.registerSource(object : DiscoverUiTest.FixtureSource("source") {
            override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> =
                if (fail) SourceResult.Error(SourceException.NetworkError("断网")) else page(id, "visible")
        })
        val model = model(manager)
        model.configure("aggregate", DiscoverDimension.CLICKS, "总")
        fail = true; model.refresh()
        assertEquals("visible", model.state.value.entries.single().book.id)
        assertTrue(model.state.value.errors.isNotEmpty())
    }
    @Test fun repeatedPagesStopWithoutAnInfiniteFetchLoop() = runTest(dispatcher) {
        var calls = 0
        val manager = SourceManager()
        manager.registerSource(object : DiscoverUiTest.FixtureSource("source") {
            override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> { calls++; return page(id, "same", true) }
        })
        val model = model(manager)
        model.configure("source", DiscoverDimension.CLICKS, "总")
        repeat(6) { model.more() }
        assertEquals(3, calls); assertTrue(model.state.value.more.isEmpty())
        assertTrue(model.state.value.notices.values.single().contains("重复页"))
    }
    @Test fun cursorAndNativeSelectionSurviveRetryAndRefresh() = runTest(dispatcher) {
        val queries = mutableListOf<DiscoverQuery>()
        var fail = true
        val manager = SourceManager()
        manager.registerSource(object : DiscoverUiTest.FixtureSource("source") {
            override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> {
                queries += query
                if (query.page == 2 && fail) return SourceResult.Error(SourceException.NetworkError("离线"))
                return SourceResult.Success(DiscoverPage(listOf(DiscoverEntry(SearchBook("${query.page}", id, "作品${query.page}", ""))),
                    hasMore = query.page == 1, nextCursor = if (query.page == 1) "cursor1" else null))
            }
        })
        val model = model(manager)
        model.configure("source", DiscoverDimension.UPDATES, "今日", "native")
        model.more(); fail = false; model.retry()
        assertEquals("cursor1", queries[1].cursor); assertEquals(queries[1], queries[2])
        model.refresh(); assertEquals("native", queries.last().feedId); assertNull(queries.last().cursor)
    }
}
