package com.example.discover

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.source.*
import com.example.data.favorite.favoriteKey
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DiscoverDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private class FixtureSource : ComicSource {
        override val id = "fixture"
        override val name = "协议示例源"
        override val capabilities = SourceCapabilities(supportComic = true)
        override suspend fun discoverCatalog(resolveDynamic: Boolean) = SourceResult.Success(DiscoverCatalog(
            feeds = listOf(DiscoverFeed("explore:0", "原站精选")), categories = listOf(DiscoverNativeCategory("category:0:0", "冒险", "题材"))))
        override suspend fun discover(query: DiscoverQuery) = SourceResult.Success(DiscoverPage((1..24).map { n ->
            DiscoverEntry(SearchBook("$n", id, "星海冒险 $n", "星川工作室", comicId = "$n",
                comicInfo = ComicInfo(tags = listOf("冒险", "奇幻"), status = "ongoing")),
                updatedAt = System.currentTimeMillis() - n * 60_000, latestChapter = "第${100+n}话", discussions = n * 12000L,
                clicks = n * 300000L, rank = n, nativeOrder = n, native = query.feedId != null || query.categoryId != null,
                origin = if (query.categoryId != null) "冒险目录" else "原站精选") }, native = query.feedId != null || query.categoryId != null))
        override suspend fun search(keyword: String) = SourceResult.Success(emptyList<SearchBook>())
        override suspend fun getDetail(bookId: String) = SourceResult.Success(SearchBook(bookId, id, "测试", ""))
        override suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo> = SourceResult.Error(SourceException.ParseError("fixture"))
        override suspend fun login(credential: LoginCredential) = SourceResult.Success(false)
        override suspend fun logout() = Unit
        override suspend fun isLoggedIn() = false
        override suspend fun getChapters(bookId: String) = SourceResult.Success(emptyList<ComicChapter>())
        override suspend fun getChapterImages(chapterId: String) = SourceResult.Success(emptyList<String>())
    }
    private fun mount(layout: DiscoverLayout = DiscoverLayout.GRID, dark: Boolean = false, scale: Float = 1f,
        dimension: DiscoverDimension = DiscoverDimension.UPDATES) {
        val store = DiscoverStore(ApplicationProvider.getApplicationContext())
        store.prefs.edit().clear().putString("layout", layout.name).putString("last_dimension", dimension.name)
            .putBoolean("rest", false).putBoolean("cache", false).apply()
        val manager = SourceManager()
        runBlocking { manager.registerSource(FixtureSource()) }
        compose.setContent { MyApplicationTheme(darkTheme = dark) {
            val density = LocalDensity.current
            var followed by remember { mutableStateOf(emptySet<String>()) }
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale), LocalReduceMotion provides true) {
                Surface(Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.systemBars)) { DiscoverScreen(manager, emptyList(), followed,
                    { b, next -> followed = if (next) followed + favoriteKey(b.sourceId, b.id) else followed - favoriteKey(b.sourceId, b.id) }, {}, {}, {}) }
            }
        } }
        compose.waitUntil(20000) { compose.onAllNodesWithContentDescription("加入书架", true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null), "discover-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun gridFollowAndLayoutSwitchWorkOnDevice() {
        mount()
        compose.onAllNodesWithContentDescription("加入书架", true).onFirst().performClick()
        compose.onAllNodesWithContentDescription("已追，取消追漫", true).onFirst().assertExists()
        screenshot("device-grid")
        compose.onNodeWithContentDescription("切换布局，当前双列网格").performClick()
        compose.onNodeWithContentDescription("切换布局，当前大图").assertExists()
    }
    @Test fun rankingDark130AndMenuRemainReachable() {
        mount(DiscoverLayout.LIST, true, 1.3f, DiscoverDimension.RANKING)
        compose.onNodeWithText("星海冒险 1").assertIsDisplayed()
        screenshot("device-ranking-dark130")
        compose.onNodeWithText("星海冒险 1").performTouchInput { longClick() }
        compose.onNodeWithText("分享").assertIsDisplayed()
        compose.onNodeWithText("不感兴趣").assertIsDisplayed()
        screenshot("device-menu-dark130")
    }
    @Test fun nativeCategoriesRetainSourceProvenance() {
        mount()
        compose.onNode(hasText("协议示例源") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Checkbox)).performClick()
        compose.onNodeWithText("原站推荐").performClick()
        compose.onAllNodesWithText("原站精选", true).onFirst().assertExists()
        compose.onNodeWithText("分类").performClick()
        compose.onNodeWithText("原站入口").performScrollTo().assertIsDisplayed()
        screenshot("device-native-categories")
        compose.onNode(hasText("冒险") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Checkbox)).performScrollTo().performClick()
        compose.onNodeWithText("分类").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("冒险目录", true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("device-native-results")
    }
}
