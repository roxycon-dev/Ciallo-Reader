package com.example.discover

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.source.*
import com.example.source.impl.HtmlNovelDiscovery
import com.example.source.importer.SourceImporter
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelDiscoverTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = mutableListOf<DiscoverViewModel>()
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        DiscoverPartition.entries.forEach { p -> val s = DiscoverStore(context, p); s.prefs.edit().clear().putBoolean("cache", false).commit(); s.cache.edit().clear().commit() }
    }
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private fun model(manager: SourceManager, p: DiscoverPartition) = DiscoverViewModel(manager, DiscoverStore(context, p), p).also(models::add)
    private open class Source(override val id: String, novel: Boolean) : BookSource {
        override val name = id
        override val capabilities = SourceCapabilities(supportComic = !novel, supportEbook = novel)
        var requests = 0
        override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> { requests++; return SourceResult.Success(DiscoverPage(listOf(
            DiscoverEntry(SearchBook("same-id", id, "作品 $id", "作者", novelInfo = if (capabilities.supportEbook) NovelInfo(tags = listOf("奇幻"), status = "已完结") else null))))) }
        override suspend fun search(keyword: String) = SourceResult.Success(emptyList<SearchBook>())
        override suspend fun getDetail(bookId: String): SourceResult<SearchBook> = SourceResult.Error(SourceException.BookNotFound)
        override suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo> = SourceResult.Error(SourceException.BookNotFound)
        override suspend fun login(credential: LoginCredential) = SourceResult.Success(true)
        override suspend fun logout() = Unit
        override suspend fun isLoggedIn() = true
    }
    @Test fun plainBookSourcesAndComicSourcesNeverCrossPartitions() = runTest(dispatcher) {
        val manager = SourceManager(); val comic = Source("comic", false); val novel = Source("novel", true)
        manager.registerSource(comic); manager.registerSource(novel)
        val comics = model(manager, DiscoverPartition.COMIC); val novels = model(manager, DiscoverPartition.NOVEL)
        comics.configure("aggregate", DiscoverDimension.UPDATES, "最新", "native")
        novels.configure("aggregate", DiscoverDimension.UPDATES, "最新", "native")
        assertEquals(listOf("comic"), comics.state.value.entries.map { it.book.sourceId })
        assertEquals(listOf("novel"), novels.state.value.entries.map { it.book.sourceId })
        assertEquals(1, comic.requests); assertEquals(1, novel.requests)
        novels.saveViewport("native", 9, 23)
        assertNull(comics.viewport("native")); assertEquals(9 to 23, novels.viewport("native"))
    }
    @Test fun pendingNovelLoadsCannotReplaceComicsAndSwitchingBackReusesItsSession() = runTest(dispatcher) {
        val ready = CompletableDeferred<Unit>(); val manager = SourceManager()
        val novel = object : Source("novel", true) { override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> { ready.await(); return super.discover(query) } }
        manager.registerSource(novel); manager.registerSource(Source("comic", false))
        val comics = model(manager, DiscoverPartition.COMIC); val novels = model(manager, DiscoverPartition.NOVEL)
        comics.configure("aggregate", DiscoverDimension.UPDATES, "最新", "native")
        novels.configure("aggregate", DiscoverDimension.UPDATES, "最新", "native")
        assertTrue(novels.state.value.loading); assertEquals("comic", comics.state.value.entries.single().book.sourceId)
        ready.complete(Unit); novels.configure("aggregate", DiscoverDimension.UPDATES, "最新", "native")
        assertEquals(1, novel.requests); assertEquals("comic", comics.state.value.entries.single().book.sourceId)
    }
    @Test fun settingsBlocksAndCacheAreIndependentAndNovelMetadataSurvivesRestarts() {
        val comic = DiscoverStore(context); val novel = DiscoverStore(context, DiscoverPartition.NOVEL)
        comic.put("layout", "GRID"); novel.put("layout", "LIST"); novel.put("cache", true)
        val book = SearchBook("id", "novel", "长篇作品", "作者", format = "txt", language = "中文",
            downloadUrl = "https://catalog.test/book.txt", novelInfo = NovelInfo(status = "完结", category = "仙侠", tags = listOf("冒险"), latestChapter = "结局"))
        val work = DiscoverWork("work", listOf(DiscoverEntry(book)), 1.0)
        novel.block(work); novel.writeCache("same-key", work.variants)
        val restored = DiscoverStore(context, DiscoverPartition.NOVEL)
        assertEquals("LIST", restored.string("layout", "")); assertEquals("GRID", comic.string("layout", ""))
        assertTrue(comic.selection("blocked").isEmpty()); assertTrue(restored.selection("blocked").isNotEmpty())
        assertTrue(comic.readCache("same-key").isEmpty())
        val cached = restored.readCache("same-key").single().book
        assertEquals("txt", cached.format); assertEquals(book.downloadUrl, cached.downloadUrl)
        assertEquals(book.novelInfo, cached.novelInfo)
        assertEquals(listOf("冒险", "仙侠"), DiscoverWork("cached", listOf(DiscoverEntry(cached)), 1.0).tags)
    }
    @Test fun chapterNovelsKeepTheirOnlineCatalogueAndEbookCoversKeepReferer() = runBlocking {
        val imported = com.example.source.impl.JsonBookSource(SourceConfig("chapter-novel", "章节文字源", baseUrl = "https://catalog.test",
            search = SearchRule("/search"), htmlSearch = HtmlSearchRule("/search", "li"),
            htmlChapters = HtmlChapterRule("{id}", "a"), htmlContent = HtmlContentRule("{chapterUrl}", ".body@text")))
        assertTrue(DiscoverPartition.NOVEL.accepts(imported)); assertFalse(imported.discoverUsesNovelDetails)
        val ebooks = com.example.source.impl.Wenku8LibrarySource(context)
        assertTrue(ebooks.discoverUsesNovelDetails)
        assertEquals("https://wenku8.ywy.moe/", ebooks.getCoverHeaders("https://wenku8.ywy.moe/covers/1.jpg")["Referer"])
    }
    @Test fun nativeCataloguesKeepTheirRealOrderAndFollowOnlyPublicNextLinks() = runBlocking {
        val home = "https://catalog.test/".toHttpUrl(); val requested = mutableListOf<String>()
        val discovery = HtmlNovelDiscovery("native", home, { url -> requested += url.toString(); Jsoup.parse("""
            <a href='/new/'>最近更新</a><a href='/hot/'>排行榜</a><a href='/c/fantasy'>玄幻</a>
            <li><a href='/n/one/'><img src='/cover.jpg'></a><h3><a href='/n/one/'>第一本</a></h3><span class='author'>作者甲</span></li>
            <li><h3><a href='/n/two/'>第二本</a></h3></li><a href='/n/one/chapter/'>章节不是作品</a>
            <a href='https://other.test/n/injected/'>外站不是作品</a><a href='/page2' rel='next'>下一页</a>
        """, url.toString()) }, { url -> Regex("^/n/([a-z]+)/$").matchEntire(url.encodedPath)?.groupValues?.get(1) })
        val catalog = discovery.catalog(); assertEquals(listOf("最新"), catalog.ranges(DiscoverDimension.UPDATES))
        assertEquals(listOf("热门"), catalog.ranges(DiscoverDimension.RANKING)); assertEquals("玄幻", catalog.categories.single().label)
        val page = discovery.discover(DiscoverQuery(DiscoverDimension.RANKING, "热门"))
        assertEquals(listOf("one", "two"), page.entries.map { it.book.id }); assertEquals(listOf(1, 2), page.entries.map { it.rank })
        assertEquals("作者甲", page.entries.first().book.author); assertEquals("https://catalog.test/cover.jpg", page.entries.first().book.cover)
        assertEquals("https://catalog.test/page2", page.nextCursor)
        discovery.discover(DiscoverQuery(DiscoverDimension.RANKING, "热门", page = 2, cursor = page.nextCursor))
        assertEquals("https://catalog.test/page2", requested.last())
        assertFalse(discovery.discover(DiscoverQuery(DiscoverDimension.RANKING, "飙升")).supported && catalog.supports(DiscoverDimension.RANKING, "飙升"))
    }
    @Test fun importedStaticLegadoExplorePreservesRulesAndPageSubstitution() = runBlocking {
        val source = (SourceImporter.importFromJsonString("""{
          "bookSourceName":"静态发现", "bookSourceUrl":"https://catalog.test", "searchUrl":"/search?q={{key}}",
          "ruleSearch":{"bookList":"li","name":"h3@text","bookUrl":"a@href"},
          "ruleToc":{"chapterList":"a"},"ruleContent":{"content":".body@text"},
          "exploreUrl":"原站推荐::/recommend?page={{page}}&&最近更新::/new?page={{page}}",
          "ruleExplore":{"bookList":".book","name":"h2@text","bookUrl":"a@href","author":".author@text"}
        }""") as SourceResult.Success).data
        val feeds = (source.discoverCatalog() as SourceResult.Success).data
        assertEquals(2, feeds.feeds.size); assertEquals("updates", feeds.feeds.last().kind)
        assertEquals(".book", source.config.explore.first().rule.listSelector)
        assertTrue(source.config.explore.first().rule.url.contains("{page}"))
    }
}
