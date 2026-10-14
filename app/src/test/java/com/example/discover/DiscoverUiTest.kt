package com.example.discover

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.source.*
import com.example.ui.components.LocalRenderQuality
import com.example.ui.components.RenderQuality
import com.example.ui.components.AppBottomTabBar
import com.example.ui.components.AppTabItem
import com.example.ui.theme.LocalAppBottomInset
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoverUiTest {
    private var fixtures = emptyList<FixtureSource>()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    internal open class FixtureSource(override val id: String) : ComicSource {
        var discoverCalls = 0
        override val name = "示例书源 $id"
        override val capabilities = SourceCapabilities(supportComic = true)
        override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> = SourceResult.Success(DiscoverPage((1..12).map { n ->
            val cover = java.io.File(DiscoverUiTest::class.java.getResource("/novel-covers/${if (n % 2 == 0) "ixdzs-571203" else "wenku-3765"}.jpg")!!.toURI()).absolutePath
            DiscoverEntry(SearchBook("$n", id, "星海冒险 $n", "星川工作室", cover = cover, comicInfo = ComicInfo(tags = listOf("冒险", "奇幻"), status = "ongoing")),
                updatedAt = System.currentTimeMillis() - n * 60_000, latestChapter = "第${100+n}话", discussions = n * 12_000L,
                clicks = n * 300_000L, rank = n, growth = 12.5, rankChange = 2, hotComment = "这一话的伏笔终于揭晓了")
        })).also { discoverCalls++ }
        override suspend fun search(keyword: String) = SourceResult.Success(emptyList<SearchBook>())
        override suspend fun getDetail(bookId: String) = SourceResult.Success(SearchBook(bookId, id, "测试", ""))
        override suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo> = SourceResult.Error(SourceException.ParseError("测试"))
        override suspend fun login(credential: LoginCredential) = SourceResult.Success(false)
        override suspend fun logout() = Unit
        override suspend fun isLoggedIn() = false
        override suspend fun getChapters(bookId: String) = SourceResult.Success(emptyList<ComicChapter>())
        override suspend fun getChapterImages(chapterId: String) = SourceResult.Success(emptyList<String>())
    }
    private fun mount(layout: DiscoverLayout, dark: Boolean = false, fontScale: Float = 1f, dimension: DiscoverDimension = DiscoverDimension.UPDATES,
        scopedStatusFixture: Boolean = false): () -> Int {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DiscoverStore(context)
        store.prefs.edit().clear().putString("layout", layout.name).putString("last_dimension", dimension.name)
            .putString("feed_aggregate", if (dimension == DiscoverDimension.RANKING) "" else "native")
            .putBoolean("cache", false).apply()
        store.cache.edit().clear().apply()
        val manager = SourceManager()
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(context).components {
            add(coil.intercept.Interceptor { chain ->
                val bitmap = java.io.File(chain.request.data.toString()).inputStream().use {
                    requireNotNull(android.graphics.BitmapFactory.decodeStream(it))
                }
                coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(context.resources, bitmap), chain.request, coil.decode.DataSource.DISK)
            })
        }.build())
        fixtures = if (!scopedStatusFixture) listOf(FixtureSource("a"), FixtureSource("b")) else listOf(
            object : FixtureSource("a") {
                override suspend fun discoverCatalog(resolveDynamic: Boolean) = SourceResult.Success(DiscoverCatalog(categories = listOf(DiscoverNativeCategory("completed", "完结"))))
                override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> {
                    val page = (super.discover(query) as SourceResult.Success<DiscoverPage>).data
                    return SourceResult.Success(page.copy(entries = page.entries.map { it.copy(book = it.book.copy(comicInfo = it.book.comicInfo?.copy(status = "completed"))) }))
                }
            }, object : FixtureSource("b") {
                override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> {
                    val page = (super.discover(query) as SourceResult.Success<DiscoverPage>).data
                    return SourceResult.Success(page.copy(entries = page.entries.map { it.copy(book = it.book.copy(comicInfo = it.book.comicInfo?.copy(status = null))) }))
                }
            })
        runBlocking { fixtures.forEach { manager.registerSource(it) } }
        var follows = 0
        compose.setContent {
            MyApplicationTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalReduceMotion provides true,
                    LocalRenderQuality provides RenderQuality.MID, LocalAppBottomInset provides 92.dp) {
                    Surface(Modifier.fillMaxSize()) {
                        val savedTabs = rememberSaveableStateHolder()
                        var tab by remember { mutableIntStateOf(1) }
                        Box(Modifier.fillMaxSize()) {
                            savedTabs.SaveableStateProvider(tab) {
                                if (tab == 1) DiscoverScreen(manager, emptyList(), emptySet(), { _, _ -> follows++ }, {}, {}, {})
                                else Text("测试书架页面")
                            }
                            AppBottomTabBar(listOf(AppTabItem("书库", Icons.Outlined.Search), AppTabItem("发现", Icons.Outlined.Explore),
                                AppTabItem("书架", Icons.Outlined.BookmarkBorder), AppTabItem("统计", Icons.Outlined.BarChart),
                                AppTabItem("设置", Icons.Outlined.Settings)), tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("星海冒险 1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        return { follows }
    }
    @Test fun gridDeduplicatesAndFollowsWithoutOpening() {
        val follows = mount(DiscoverLayout.GRID)
        compose.onAllNodesWithText("2 个源可读", useUnmergedTree = true).onFirst().assertExists()
        compose.onAllNodesWithContentDescription("加入我喜欢的", useUnmergedTree = true).onFirst().performClick()
        assertEquals(1, follows())
        capture("discover-grid.png")
    }
    @Test fun rankingHasThreeMedalsAndFitsLargeDarkText() {
        mount(DiscoverLayout.LIST, dark = true, fontScale = 1.3f, dimension = DiscoverDimension.RANKING)
        compose.onNodeWithText("榜单").assertExists()
        val source = compose.onNodeWithText("聚合书源").fetchSemanticsNode().boundsInRoot
        val range = compose.onNodeWithText("热门").fetchSemanticsNode().boundsInRoot
        assertTrue("Range row must stay below the source row", range.top >= source.bottom)
        capture("discover-ranking-dark130.png")
    }
    @Test fun largeCardsAndLongPressActionsAreReachable() {
        mount(DiscoverLayout.LARGE)
        compose.onNodeWithText("星海冒险 1").performTouchInput { longClick() }
        compose.onNodeWithText("不感兴趣").assertExists()
        compose.onNodeWithText("分享").assertExists()
        compose.onNodeWithText("分享").assertIsDisplayed()
        capture("discover-large-menu.png")
    }
    @Test fun settingsUseOwnWindowAndExcludeRemovedOptions() {
        mount(DiscoverLayout.GRID)
        compose.onNodeWithContentDescription("发现设置").performClick()
        compose.onNodeWithText("浏览布局").assertIsDisplayed()
        compose.onNodeWithText("个性化推荐").assertIsDisplayed()
        listOf("默认书源", "默认布局", "显示成人向分类", "连续浏览休息提醒").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        capture("discover-settings.png", overlay = true)
    }
    @Test fun categoriesCommitTogetherAndRemainReadableAt130Percent() {
        mount(DiscoverLayout.GRID, fontScale = 1.3f)
        compose.onNodeWithText("分类").performClick()
        compose.onNodeWithText("找你想看的漫画").assertIsDisplayed()
        compose.onNodeWithText("搜索分类").assertIsDisplayed()
        val before = fixtures.sumOf { it.discoverCalls }
        compose.onNodeWithText("战斗").performClick()
        compose.onNodeWithText("冒险 12 · 今").performClick()
        compose.onNodeWithText("应用 2 项").assertIsDisplayed()
        assertEquals("Draft chip changes must not request sources", before, fixtures.sumOf { it.discoverCalls })
        capture("discover-categories130.png", overlay = true)
        compose.onNodeWithText("应用 2 项").performClick()
        compose.waitForIdle()
        assertEquals(setOf("battle", "adventure"), DiscoverStore(ApplicationProvider.getApplicationContext<Context>()).selection("categories"))
    }
    @Test fun settingsCacheAndBlockManagementAreReachableInDarkLargeText() {
        mount(DiscoverLayout.LIST, dark = true, fontScale = 1.3f)
        DiscoverStore(ApplicationProvider.getApplicationContext<Context>()).put("cache", true)
        compose.onNodeWithContentDescription("发现设置").performClick()
        compose.onNodeWithText("24小时").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("屏蔽管理").performScrollTo()
        compose.onNodeWithText("屏蔽管理").assertIsDisplayed()
        compose.onNodeWithText("完成").assertIsDisplayed()
        capture("discover-settings-dark130.png", overlay = true)
    }
    @Test fun headerCollapsesAndSourcePickerIsReachable() {
        mount(DiscoverLayout.GRID)
        compose.onNodeWithText("聚合书源").performClick()
        compose.onNodeWithText("选择书源").assertIsDisplayed()
        capture("discover-sources.png", overlay = true)
        compose.onNodeWithContentDescription("关闭书源窗口").performClick()
        compose.onNodeWithTag("discover_content").performTouchInput { swipeUp() }
        compose.waitForIdle()
        capture("discover-scrolled.png")
    }
    private fun capture(name: String, overlay: Boolean = false) {
        val root = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { java.io.File(it, "gradlew.bat").exists() }
        val directory = java.io.File(root, "artifacts/discover-rework-2026-10-14/screenshots").apply { mkdirs() }
        compose.waitForIdle()
        captureScreenRoboImage(java.io.File(directory, name).absolutePath)
    }
    @Test fun switchingBottomTabsRestoresTheScrolledDiscoverPage() {
        mount(DiscoverLayout.LIST)
        val calls = fixtures.sumOf { it.discoverCalls }
        compose.onNodeWithTag("discover_content").performScrollToIndex(7)
        compose.waitForIdle()
        assertEquals("Returning to Discover must reuse results", calls, fixtures.sumOf { it.discoverCalls })
        compose.onNodeWithText("星海冒险 1").assertDoesNotExist()
        compose.onNodeWithText("书架").performClick()
        compose.onNodeWithText("测试书架页面").assertExists()
        compose.onNodeWithText("发现").performClick()
        compose.waitForIdle()
        assertEquals("Returning to Discover must reuse results", calls, fixtures.sumOf { it.discoverCalls })
        compose.onNodeWithText("星海冒险 1").assertDoesNotExist()
        compose.onNodeWithTag("discover_content").performScrollToIndex(0)
        compose.onNodeWithText("星海冒险 1").assertExists()
    }
    @Test fun categoryInteriorDoesNotDismissAndFooterFitsAt130Percent() {
        mount(DiscoverLayout.GRID, fontScale = 1.3f)
        compose.onNodeWithText("分类").performClick()
        compose.onNodeWithText("找你想看的漫画").performClick()
        compose.onNodeWithText("查看全部").assertIsDisplayed()
        compose.onNodeWithText("清除").assertIsDisplayed()
        val footer = compose.onNodeWithText("查看全部").fetchSemanticsNode().boundsInRoot
        val window = generateSequence(compose.onNodeWithText("找你想看的漫画").fetchSemanticsNode()) { it.parent }.last().boundsInRoot
        assertTrue("Footer must remain within the measured dialog window", footer.bottom <= window.bottom)
        compose.onNodeWithContentDescription("关闭分类窗口").performClick()
        compose.onNodeWithText("找你想看的漫画").assertDoesNotExist()
    }
    @Test fun horizontalSwipeNeverHidesOrFavoritesAWork() {
        val follows = mount(DiscoverLayout.LIST)
        compose.onNodeWithText("星海冒险 1").performTouchInput { swipeLeft() }
        compose.onNodeWithText("星海冒险 1").assertExists()
        compose.onNodeWithText("星海冒险 1").performTouchInput { swipeRight() }
        assertEquals(0, follows())
        listOf("随机来一本", "直达未读", "读最新话", "正在刷新").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }
    @Test fun aSourcesStatusFilterCannotEmptyAnotherSourcesUnspecifiedStatusBooks() {
        mount(DiscoverLayout.LIST, scopedStatusFixture = true)
        compose.onNode(hasText("完结") and hasAnyAncestor(hasTestTag("discover_status_filters"))).performClick()
        compose.onNodeWithText("星海冒险 1").assertExists()
        compose.onNodeWithText("聚合书源").performClick()
        compose.onNodeWithText("示例书源 b").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("discover_status_filters").assertDoesNotExist()
        compose.onNodeWithText("星海冒险 1").assertExists()
    }
    @Test fun layoutPreferenceAndCategoriesSurviveStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DiscoverStore(context)
        store.put("layout", "LIST"); store.put("categories", setOf("battle", "love"))
        val restored = DiscoverStore(context)
        assertEquals("LIST", restored.string("layout", "GRID"))
        assertEquals(setOf("battle", "love"), restored.selection("categories"))
    }
    @Test fun cacheKeepsAliasesAndMissingMetrics() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DiscoverStore(context)
        store.prefs.edit().clear().putBoolean("cache", true).apply()
        val entry = DiscoverEntry(SearchBook("book", "source", "原名", "作者", comicInfo = ComicInfo(
            alternateTitles = listOf("别名"), tags = listOf("冒险"))), canonicalId = "anilist:123", rankChange = -2)
        store.writeCache("cache-test", listOf(entry))
        val restored = DiscoverStore(context).readCache("cache-test").single()
        assertEquals("source", restored.book.sourceId)
        assertEquals(listOf("别名"), restored.book.comicInfo?.alternateTitles)
        assertEquals("anilist:123", restored.canonicalId)
        assertNull(restored.clicks)
        assertNull(restored.discussions)
        assertEquals(-2, restored.rankChange)
    }
    @Test fun unblockRemovesAllCrossSourceAliasesAndNativeKeys() {
        val store = DiscoverStore(ApplicationProvider.getApplicationContext<Context>())
        store.prefs.edit().clear().apply()
        val first = DiscoverEntry(SearchBook("a", "source1", "原名", "作者"), canonicalId = "id1")
        val second = DiscoverEntry(SearchBook("b", "source2", "别名", "作者"))
        val work = DiscoverWork("source1::a", listOf(first, second), 1.0)
        store.block(work)
        assertEquals(listOf(work.key to "原名"), store.blockedWorks())
        store.unblock(work.key)
        assertTrue(store.selection("blocked").isEmpty())
        assertTrue(store.blockedWorks().isEmpty())
    }
}
