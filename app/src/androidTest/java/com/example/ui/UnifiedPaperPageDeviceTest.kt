package com.example.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.discover.*
import com.example.source.*
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.components.*
import com.example.ui.reader.ReadingSettingsPanel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnifiedPaperPageDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private var drawing = true
    @Before fun setup() { drawing = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled(); androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true); PullOutSources.latest = null; PaperWarpStats.reset() }
    @After fun cleanup() { androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(drawing) }
    private fun shot(name: String) {
        compose.onAllNodes(isRoot()).onLast().captureToImage()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "paper-1.4.17").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @Test fun fullPagesUseTheClickedBookAndReturnWithoutReplayingThePreviousPage() {
        var life: PaperLifecycle? = null; var readerLife: PaperLifecycle? = null
        var bookOrigin: androidx.compose.ui.geometry.Rect? = null
        compose.setContent { MyApplicationTheme {
            val nav = rememberNavController()
            NavHost(nav, "shelf") {
                composable("shelf") { Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(180.dp)); PullOutButton({ nav.openPaperPage("book/42"); nav.openPaperPage("book/42") }) { Text("打开图书") }
                } }
                paperComposable("book/{id}", nav, arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
                    life = LocalPaperOwner.current
                    var value by rememberSaveable { mutableIntStateOf(0) }
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
                        Text("图书 ${entry.arguments!!.getInt("id")} · 已选 $value")
                        PullOutButton({ value++; nav.openPaperPage("reader") }) { Text("开始阅读") }
                        PullOutButton({ nav.dismissPaperPage() }) { Text("返回书架") }
                    }
                }
                paperComposable("reader", nav) {
                    readerLife = LocalPaperOwner.current
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
                        Text("真实大小阅读页")
                        PullOutButton({ nav.dismissPaperPage() }) { Text("退出阅读") }
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("打开图书").performClick()
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithText("图书 42 · 已选 0").assertExists()
        compose.runOnIdle { assertTrue(life!!.softPaper); assertTrue(life!!.allowFullViewport); assertTrue(life!!.progress in .05f.. .95f) }
        compose.runOnIdle { bookOrigin = life!!.origin!!.bounds }
        shot("book-opening")
        compose.mainClock.advanceTimeBy(1600); compose.mainClock.autoAdvance = true; compose.waitForIdle()
        compose.onNodeWithText("开始阅读").performClick(); compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("退出阅读").performClick(); compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("真实大小阅读页").assertExists()
        compose.runOnIdle { assertTrue(readerLife!!.progress > 0f) }
        compose.mainClock.advanceTimeBy(1500); compose.mainClock.autoAdvance = true; compose.waitForIdle()
        compose.onNodeWithText("图书 42 · 已选 1").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1f, life!!.progress); assertTrue(life!!.flat)
            assertTrue("A restored page must still fold on its next close", life!!.softPaper)
            assertEquals("Returning from the reader must preserve the original book trigger", bookOrigin, life!!.origin!!.bounds)
        }
        compose.onNodeWithText("返回书架").performClick(); compose.waitForIdle()
        compose.onNodeWithText("打开图书").assertIsDisplayed()
        assertEquals(0, PaperWarpStats.failures); assertTrue(PaperWarpStats.frames > 0)
    }
    @Test fun returningFromAMenuNeverRecreatesReadingSettingsDuringClose() {
        var menu by mutableStateOf(false); var settings by mutableStateOf(false)
        var revision by mutableIntStateOf(0); var mounts = 0; var disposals = 0
        var life: PaperLifecycle? = null
        compose.setContent { MyApplicationTheme {
            Column(Modifier.fillMaxSize().background(Color(0xFFEEE6D8))) {
                PullOutButton({ menu = true }) { Text("右上角更多") }
                Text("阅读正文保留在原位")
                PullOutDropdownMenu(menu, { menu = false }) {
                    PullOutDropdownMenuItem(text = { Text("排版设置入口") }, onClick = paperOpen { menu = false; settings = true })
                }
            }
            if (settings) ReadingSettingsPanel(Color(0xFFEEE6D8), Color.Black, null, { settings = false }) { close ->
                life = LocalPaperOwner.current
                DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("排版设置内容 $revision")
                    repeat(6) { Text("参数 $it", Modifier.padding(8.dp)) }
                    PullOutButton(close) { Text("关闭排版") }
                }
            }
        } }
        compose.onNodeWithText("右上角更多").performClick(); compose.waitForIdle()
        compose.onNodeWithText("排版设置入口").performClick(); compose.waitForIdle()
        compose.runOnIdle { assertTrue(life!!.origin!!.frozenOpening); assertEquals(1, mounts) }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("关闭排版").performClick(); compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { revision++ }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { assertEquals(1, mounts); assertEquals(0, disposals); assertTrue(life!!.retained) }
        shot("settings-closing")
        compose.mainClock.advanceTimeBy(1500); compose.mainClock.autoAdvance = true; compose.waitForIdle()
        assertEquals(1, mounts); assertEquals(1, disposals); assertFalse(settings); assertEquals(0, PaperWarpStats.failures)
    }
    @Test fun inlinePanelsOnASettledPageHaveTheirOwnPaperClock() {
        var open by mutableStateOf(false); var life: PaperLifecycle? = null
        compose.setContent { MyApplicationTheme {
            val parent = remember { PaperLifecycle().apply { flat = true; progress = 1f } }
            CompositionLocalProvider(LocalPaperOwner provides parent, LocalPaperFullSurface provides true) {
                Column(Modifier.fillMaxSize()) {
                    PullOutButton({ open = true }) { Text("阅读器设置") }
                    val visibility = remember { MutableTransitionState(false) }; visibility.targetState = open
                    PullOutVisibility(visibility, deformContent = false) {
                        life = LocalPaperOwner.current
                        Box(Modifier.fillMaxWidth().height(240.dp).paperSurface().background(MaterialTheme.colorScheme.surface)) {
                            Text("内联面板保留玻璃与形变")
                        }
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("阅读器设置").performClick(); compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle { assertTrue(life!!.progress in .01f.. .95f); assertTrue(life!!.softPaper); assertTrue(life!!.allowFullViewport) }
        shot("inline-panel-opening")
        compose.mainClock.advanceTimeBy(1500); compose.mainClock.autoAdvance = true; compose.waitForIdle()
        compose.runOnIdle { open = false }; compose.waitForIdle()
        compose.onNodeWithText("内联面板保留玻璃与形变").assertDoesNotExist()
        assertEquals(0, PaperWarpStats.failures); assertTrue(PaperWarpStats.frames > 0)
    }

    private class Source(override val id: String, private val novel: Boolean) : BookSource {
        override val name = if (novel) "文字书源" else "图片书源"
        override val capabilities = SourceCapabilities(supportComic = !novel, supportEbook = novel)
        override suspend fun discover(query: DiscoverQuery) = SourceResult.Success(DiscoverPage((1..4).map { i ->
            DiscoverEntry(SearchBook("$i", id, (if (novel) "文字作品" else "图片作品") + i, "作者", format = if (novel) "epub" else "漫画",
                novelInfo = if (novel) NovelInfo(status = "完结", tags = listOf("冒险")) else null), nativeOrder = i, native = true)
        }))
        override suspend fun search(keyword: String) = SourceResult.Success(emptyList<SearchBook>())
        override suspend fun getDetail(bookId: String): SourceResult<SearchBook> = SourceResult.Error(SourceException.BookNotFound)
        override suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo> = SourceResult.Error(SourceException.BookNotFound)
        override suspend fun login(credential: LoginCredential) = SourceResult.Success(true)
        override suspend fun logout() = Unit
        override suspend fun isLoggedIn() = true
    }
    @Test fun discoverUsesTheWaterSelectorAndKeepsNovelAndComicResultsSeparate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DiscoverPartition.entries.forEach { p -> DiscoverStore(context, p).apply { prefs.edit().clear().putBoolean("cache", false).commit(); cache.edit().clear().commit() } }
        val manager = SourceManager(); runBlocking { manager.registerSource(Source("comic", false)); manager.registerSource(Source("novel", true)) }
        compose.setContent { MyApplicationTheme { DiscoverScreen(manager, emptyList(), emptySet(), { _, _ -> }, {}, {}, {}) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("图片作品1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("discover_partition").assertIsDisplayed(); shot("discover-comic")
        compose.onNodeWithText("小说", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("文字作品1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("图片作品1").assertDoesNotExist(); shot("discover-novel")
        compose.onNodeWithText("漫画", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("图片作品1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("文字作品1").assertDoesNotExist()
    }
}
