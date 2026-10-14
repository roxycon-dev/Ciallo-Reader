package com.example.discover

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiscoverViewModelTest {
    @Test fun mappedGenresNeverBypassADeclaredMissingMetricOrWindow() = runTest(dispatcher) {
        var calls = 0; val manager = SourceManager()
        manager.registerSource(object : DiscoverUiTest.FixtureSource("source") {
            override suspend fun discoverCatalog(resolveDynamic: Boolean) = SourceResult.Success(DiscoverCatalog(declared = true,
                categories = listOf(DiscoverNativeCategory("genre:adventure", "冒险")), capabilities = mapOf("ranking" to listOf("热门"))))
            override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> { calls++; return page(id, "book") }
        })
        model(manager).configure("source", DiscoverDimension.CLICKS, "周", standardCategories = setOf("adventure"))
        assertEquals(0, calls)
    }
    @Test fun metadataPublishesProgressivelyAndSurvivesAnotherSourceCompleting() = runTest(dispatcher) {
        val detailReady = CompletableDeferred<Unit>(); val slowReady = CompletableDeferred<Unit>()
        val manager = SourceManager()
        manager.registerSource(object : DiscoverUiTest.FixtureSource("metadata") {
            override val supportsDiscoverMetadata = true
            override suspend fun discover(query: DiscoverQuery) = page(id, "book")
            override suspend fun getDiscoverMetadata(bookId: String): SourceResult<SearchBook> {
                detailReady.await()
                return SourceResult.Success(SearchBook(bookId, id, "book", "真实作者", comicInfo = ComicInfo(
                    status = "完结", tags = listOf("爆笑"), updatedAt = "2026-10-14")))
            }
        })
        manager.registerSource(object : DiscoverUiTest.FixtureSource("slow") {
            override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> { slowReady.await(); return page(id, "other") }
        })
        val model = model(manager)
        model.configure("aggregate", DiscoverDimension.UPDATES, "最新", "native")
        assertEquals("book", model.state.value.entries.single().book.id)
        detailReady.complete(Unit)
        assertEquals("完结", model.state.value.entries.single().book.comicInfo?.status)
        slowReady.complete(Unit)
        val enriched = model.state.value.entries.first { it.book.id == "book" }
        assertEquals("真实作者", enriched.book.author)
        assertEquals("完结", enriched.book.comicInfo?.status)
        assertNotNull(enriched.updatedAt)
    }
    @Test fun switchingQueryCancelsMetadataWithoutPublishingAnOldBook() = runTest(dispatcher) {
        val wait = CompletableDeferred<Unit>(); val manager = SourceManager()
        manager.registerSource(object : DiscoverUiTest.FixtureSource("metadata") {
            override val supportsDiscoverMetadata = true
            override suspend fun discover(query: DiscoverQuery) = page(id, query.dimension.name)
            override suspend fun getDiscoverMetadata(bookId: String): SourceResult<SearchBook> { wait.await(); return SourceResult.Success(SearchBook(bookId, id, bookId, "metadata")) }
        })
        val model = model(manager)
        model.configure("metadata", DiscoverDimension.UPDATES, "最新", "native")
        model.configure("metadata", DiscoverDimension.RANKING, "热门", "native")
        wait.complete(Unit)
        assertEquals("RANKING", model.state.value.entries.single().book.id)
    }
    @Test fun slowDirectoriesDoNotPreventTheNextSourcePublishing() = runTest(dispatcher) {
        val wait = CompletableDeferred<Unit>()
        val manager = SourceManager()
        repeat(8) { n -> manager.registerSource(object : DiscoverUiTest.FixtureSource("slow$n") {
            override suspend fun discoverCatalog(resolveDynamic: Boolean): SourceResult<DiscoverCatalog> { wait.await(); return SourceResult.Success(DiscoverCatalog()) }
        }) }
        manager.registerSource(object : DiscoverUiTest.FixtureSource("fast") {
            override suspend fun discover(query: DiscoverQuery) = page(id, "visible-immediately")
        })
        val model = model(manager)
        model.configure("aggregate", DiscoverDimension.UPDATES, "最新", "native")
        assertEquals("visible-immediately", model.state.value.entries.single().book.id)
        wait.complete(Unit)
    }
    @Test fun replacingSameSourceInstanceAndRefreshEventsReuseTheSession() = runTest(dispatcher) {
        val manager = SourceManager(); var calls = 0
        fun replacement() = object : DiscoverUiTest.FixtureSource("same") {
            override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> { calls++; return page(id, "book") }
        }
        manager.registerSource(replacement())
        val model = model(manager)
        model.configure("same", DiscoverDimension.UPDATES, "最新", "native")
        manager.registerSource(replacement())
        model.configure("same", DiscoverDimension.UPDATES, "最新", "native")
        assertEquals(1, calls)
        assertFalse(model.consumeRefreshRequest(0))
        assertTrue(model.consumeRefreshRequest(1))
        assertFalse(model.consumeRefreshRequest(1))
        assertTrue(model.consumeRefreshRequest(2))
    }
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
    @Test fun diskCacheSurvivesNewSourceInstancesAndViewModels() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DiscoverStore(context)
        store.prefs.edit().clear().putBoolean("cache", true).apply()
        store.cache.edit().clear().apply()
        val manager1 = SourceManager()
        manager1.registerSource(object : DiscoverUiTest.FixtureSource("cached") {
            override suspend fun discover(query: DiscoverQuery) = page(id, "persistent-book")
        })
        val first = DiscoverViewModel(manager1, store).also(models::add)
        first.configure("cached", DiscoverDimension.CLICKS, "总")
        first.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
        val wait = CompletableDeferred<Unit>()
        val manager2 = SourceManager()
        manager2.registerSource(object : DiscoverUiTest.FixtureSource("cached") {
            override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> { wait.await(); return page(id, "fresh-book") }
        })
        val restored = DiscoverViewModel(manager2, DiscoverStore(context)).also(models::add)
        restored.configure("cached", DiscoverDimension.CLICKS, "总")
        val cached = restored.state.first { it.cached }
        assertEquals("persistent-book", cached.entries.single().book.id)
        assertTrue(cached.loading)
        wait.complete(Unit)
        restored.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals("fresh-book", restored.state.value.entries.single().book.id)
    }
    @Test fun categoryWindowAndFilteredFeedShareOneDynamicCatalogLoad() = runTest(dispatcher) {
        var dynamicCalls = 0
        val ready = CompletableDeferred<Unit>()
        val manager = SourceManager()
        manager.registerSource(object : DiscoverUiTest.FixtureSource("dynamic") {
            override suspend fun discoverCatalog(resolveDynamic: Boolean): SourceResult<DiscoverCatalog> {
                if (resolveDynamic) { dynamicCalls++; ready.await() }
                return SourceResult.Success(DiscoverCatalog(dynamicCategories = true,
                    categories = if (resolveDynamic) listOf(DiscoverNativeCategory("dynamic:stable", "冒险")) else emptyList()))
            }
        })
        val model = model(manager)
        model.configure("dynamic", DiscoverDimension.UPDATES, "最新", feedId = "native")
        model.loadCategories("dynamic"); model.loadCategories("dynamic")
        model.configure("dynamic", DiscoverDimension.UPDATES, "最新", standardCategories = setOf("adventure"))
        assertEquals(1, dynamicCalls)
        ready.complete(Unit)
        assertEquals(1, dynamicCalls)
        assertFalse(model.state.value.loading)
        assertEquals("dynamic:stable", model.state.value.catalogs.getValue("dynamic").categories.single().id)
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
    @Test fun allEnabledComicSourcesParticipateWithoutASecondAdultGate() = runTest(dispatcher) {
        val manager = SourceManager()
        manager.registerSource(DiscoverUiTest.FixtureSource("a"))
        manager.registerSource(DiscoverUiTest.FixtureSource("js_jm"))
        val model = model(manager)
        model.configure("aggregate", DiscoverDimension.CLICKS, "总")
        assertTrue(model.state.value.entries.isNotEmpty())
        assertEquals(setOf("a", "js_jm"), model.state.value.entries.map { it.book.sourceId }.toSet())
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
        assertTrue(model.state.value.notices.isEmpty())
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
    @Test fun switchingBackReusesRowsAndResumesTheNextPage() = runTest(dispatcher) {
        val calls = mutableListOf<Pair<DiscoverDimension, Int>>()
        val manager = SourceManager()
        manager.registerSource(object : DiscoverUiTest.FixtureSource("source") {
            override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> {
                calls += query.dimension to query.page
                return page(id, "${query.dimension}-${query.page}", true)
            }
        })
        val model = model(manager)
        model.configure("source", DiscoverDimension.CLICKS, "总"); model.more()
        model.configure("source", DiscoverDimension.RANKING, "热门")
        model.configure("source", DiscoverDimension.CLICKS, "总")
        assertEquals(3, calls.size)
        assertEquals(2, model.state.value.entries.size)
        model.more(); assertEquals(DiscoverDimension.CLICKS to 3, calls.last())
    }
    @Test fun recompositionAndRepeatedRefreshDoNotCancelAnActiveLoad() = runTest(dispatcher) {
        val wait = CompletableDeferred<Unit>(); var calls = 0
        val manager = SourceManager()
        manager.registerSource(object : DiscoverUiTest.FixtureSource("source") {
            override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> { calls++; wait.await(); return page(id, "book") }
        })
        val model = model(manager)
        model.configure("source", DiscoverDimension.UPDATES, "最新")
        repeat(5) { model.configure("source", DiscoverDimension.UPDATES, "最新"); model.refresh() }
        assertEquals(1, calls); wait.complete(Unit)
        assertEquals("book", model.state.value.entries.single().book.id)
    }
    @Test fun anUnsupportedWindowNeverSendsAContentRequest() = runTest(dispatcher) {
        var calls = 0
        val manager = SourceManager()
        manager.registerSource(object : DiscoverUiTest.FixtureSource("source") {
            override suspend fun discoverCatalog(resolveDynamic: Boolean) = SourceResult.Success(DiscoverCatalog(declared = true,
                capabilities = mapOf("updates" to listOf("最新"))))
            override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> { calls++; return page(id, "book") }
        })
        val model = model(manager)
        model.configure("source", DiscoverDimension.UPDATES, "今日")
        assertEquals(0, calls); assertTrue(model.state.value.errors.isEmpty()); assertTrue(model.state.value.notices.isEmpty())
        model.configure("source", DiscoverDimension.UPDATES, "最新")
        assertEquals(1, calls)
    }
    @Test fun standardCategoriesResolveToNativeSourceRoutes() = runTest(dispatcher) {
        var seen: DiscoverQuery? = null
        val manager = SourceManager()
        manager.registerSource(object : DiscoverUiTest.FixtureSource("source") {
            override suspend fun discoverCatalog(resolveDynamic: Boolean) = SourceResult.Success(DiscoverCatalog(
                categories = listOf(DiscoverNativeCategory("genre:42", "冒險", "题材"))))
            override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> { seen = query; return page(id, "book") }
        })
        model(manager).configure("source", DiscoverDimension.UPDATES, "最新", "native", standardCategories = setOf("adventure"))
        assertEquals(listOf("genre:42"), seen?.categoryIds)
    }
}
