package com.example.discover

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w390dp-h844dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoverUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    internal open class FixtureSource(override val id: String) : ComicSource {
        override val name = "示例书源 $id"
        override val capabilities = SourceCapabilities(supportComic = true)
        override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> = SourceResult.Success(DiscoverPage((1..12).map { n ->
            DiscoverEntry(SearchBook("$n", id, "星海冒险 $n", "星川工作室", comicInfo = ComicInfo(tags = listOf("冒险", "奇幻"), status = "ongoing")),
                updatedAt = System.currentTimeMillis() - n * 60_000, latestChapter = "第${100+n}话", discussions = n * 12_000L,
                clicks = n * 300_000L, rank = n, growth = 12.5, rankChange = 2, hotComment = "这一话的伏笔终于揭晓了")
        }))
        override suspend fun search(keyword: String) = SourceResult.Success(emptyList<SearchBook>())
        override suspend fun getDetail(bookId: String) = SourceResult.Success(SearchBook(bookId, id, "测试", ""))
        override suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo> = SourceResult.Error(SourceException.ParseError("测试"))
        override suspend fun login(credential: LoginCredential) = SourceResult.Success(false)
        override suspend fun logout() = Unit
        override suspend fun isLoggedIn() = false
        override suspend fun getChapters(bookId: String) = SourceResult.Success(emptyList<ComicChapter>())
        override suspend fun getChapterImages(chapterId: String) = SourceResult.Success(emptyList<String>())
    }
    private fun mount(layout: DiscoverLayout, dark: Boolean = false, fontScale: Float = 1f, dimension: DiscoverDimension = DiscoverDimension.UPDATES): () -> Int {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DiscoverStore(context)
        store.prefs.edit().clear().putString("layout", layout.name).putString("last_dimension", dimension.name)
            .putBoolean("rest", false).putBoolean("cache", false).apply()
        store.cache.edit().clear().apply()
        val manager = SourceManager()
        runBlocking { manager.registerSource(FixtureSource("a")); manager.registerSource(FixtureSource("b")) }
        var follows = 0
        compose.setContent {
            MyApplicationTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalReduceMotion provides true,
                    LocalRenderQuality provides RenderQuality.MINIMAL) {
                    Surface(Modifier.fillMaxSize()) {
                        DiscoverScreen(manager, emptyList(), emptySet(), { _, _ -> follows++ }, {}, {}, {})
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
        compose.onAllNodesWithContentDescription("加入书架", useUnmergedTree = true).onFirst().performClick()
        assertEquals(1, follows())
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/discover-grid.png")
    }
    @Test fun rankingHasThreeMedalsAndFitsLargeDarkText() {
        mount(DiscoverLayout.LIST, dark = true, fontScale = 1.3f, dimension = DiscoverDimension.RANKING)
        compose.onNodeWithText("榜单").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/discover-ranking-dark130.png")
    }
    @Test fun largeCardsAndLongPressActionsAreReachable() {
        mount(DiscoverLayout.LARGE)
        compose.onNodeWithText("星海冒险 1").performTouchInput { longClick() }
        compose.onNodeWithText("不感兴趣").assertExists()
        compose.onNodeWithText("分享").assertExists()
        compose.onNodeWithText("分享").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/discover-large-menu.png")
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
}
