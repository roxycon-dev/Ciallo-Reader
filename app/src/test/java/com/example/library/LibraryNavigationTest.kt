package com.example.library

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.source.ComicChapter
import com.example.source.ComicSource
import com.example.source.DownloadInfo
import com.example.source.LoginCredential
import com.example.source.SearchBook
import com.example.source.SourceCapabilities
import com.example.source.SourceResult
import com.example.ui.comic.ComicReaderTestActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Navigation fixtures do not test TLS. Keep OkHttp's cached platform and the JVM trust manager
// on the same provider while Robolectric resets application state between test methods.
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LibraryNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private lateinit var navController: NavHostController
    private lateinit var sharedLibrary: LibraryViewModel
    private val comicSource = FixtureSource("fixture-comic", false)
    private val novelSource = FixtureSource("fixture-novel", true)

    @Before fun setUp() {
        val app = compose.activity.application
        if (runCatching { androidx.work.WorkManager.getInstance(app) }.isFailure) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        com.example.data.PreferencesManager(app).apply {
            jsSourceRepoUrl = "http://127.0.0.1:1/fixture.json"
            multiLanguageSearch = false
            multiLanguageOnlineLookup = false
        }
        compose.runOnUiThread {
            // Also verifies that the normal AndroidViewModel factory still works.
            sharedLibrary = ViewModelProvider(compose.activity,
                ViewModelProvider.AndroidViewModelFactory.getInstance(app))[LibraryViewModel::class.java]
        }
        runBlocking {
            sharedLibrary.sourceManager.initialize()
            sharedLibrary.sourceManager.registerSource(comicSource)
            sharedLibrary.sourceManager.registerSource(novelSource)
            sharedLibrary.sourceManager.setActiveSource(comicSource.id)
        }
        compose.setContent {
            navController = rememberNavController()
            NavHost(navController, startDestination = "home",
                enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
                composable("home") { Text("home") }
                composable(LIBRARY_SEARCH_ROUTE) { entry ->
                    remember(entry) { librarySearchViewModel(entry, sharedLibrary) }
                    Text("search")
                }
                for (route in listOf("comic_chapters", "comic_reader_online", "novel_reader_online")) {
                    composable("$route?$LIBRARY_SESSION_ARGUMENT={$LIBRARY_SESSION_ARGUMENT}",
                        arguments = librarySessionArguments()) { entry ->
                        val model = remember(entry) {
                            libraryViewModelForSession(navController,
                                entry.arguments?.getString(LIBRARY_SESSION_ARGUMENT), sharedLibrary)
                        }
                        val book by model.comicBook.collectAsState()
                        Text("$route:${book?.id}")
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun openInitialDetail() {
        compose.runOnIdle {
            sharedLibrary.openComic(comicSource.book("original"))
            navController.navigate("comic_chapters")
        }
        compose.onNodeWithText("comic_chapters:original").assertIsDisplayed()
    }

    @Test fun nestedAndRepeatedTagSearchesReturnThroughEveryDetailAndSearch() {
        openInitialDetail()
        val models = mutableListOf<LibraryViewModel>()
        val sessions = mutableListOf<String>()
        val entryIds = mutableSetOf<String>()
        val results = mutableListOf<LibraryUiState>()
        // Repeated tags and punctuation must still produce separate navigation entries.
        repeat(4) { depth ->
            compose.runOnIdle { navController.openDetailSearch("  校园 / 百合? #&  ", false) }
            compose.waitForIdle()
            compose.runOnIdle {
                val entry = navController.currentBackStackEntry!!
                assertTrue(entryIds.add(entry.id))
                sessions += entry.arguments!!.getString(LIBRARY_SESSION_ARGUMENT)!!
                val model = librarySearchViewModel(entry, sharedLibrary)
                assertFalse(models.any { it === model })
                assertNotSame(sharedLibrary, model)
                assertSame(sharedLibrary.sourceManager, model.sourceManager)
                assertSame(sharedLibrary.downloadManager, model.downloadManager)
                val request = model.detailSearchRequest.value!!
                assertEquals("校园 / 百合? #&", request.keyword)
                model.consumeDetailSearch(request.token)
                model.selectSource(comicSource.id)
                model.search("result-$depth")
                models += model
            }
            val model = models.last()
            try {
                compose.waitUntil(10_000) {
                    // Search completion/cooldown posts to Android's paused looper,
                    // independently of Compose's frame clock.
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                        .idleFor(java.time.Duration.ofMillis(100))
                    (model.uiState.value as? LibraryUiState.SearchResults)?.loading == false
                }
            } catch (error: Throwable) {
                throw AssertionError("Search depth=$depth source=${model.currentSource.value?.id} state=${model.uiState.value} calls=${comicSource.calls}", error)
            }
            compose.runOnIdle {
                results += model.uiState.value
                model.openComic(comicSource.book("book-$depth"))
                navController.navigate(libraryDestinationRoute("comic_chapters", sessions.last()))
            }
            compose.onNodeWithText("comic_chapters:book-$depth").assertIsDisplayed()
        }
        for (depth in models.indices.reversed()) {
            compose.runOnIdle { assertTrue(navController.popBackStack()) }
            compose.onNodeWithText("search").assertIsDisplayed()
            compose.runOnIdle {
                val restored = librarySearchViewModel(navController.currentBackStackEntry!!, sharedLibrary)
                assertSame(models[depth], restored)
                assertEquals(results[depth], restored.uiState.value)
                assertNull("Returning must not restart the initial tag search", restored.detailSearchRequest.value)
                assertTrue(navController.popBackStack())
            }
            val expected = if (depth == 0) "original" else "book-${depth - 1}"
            compose.onNodeWithText("comic_chapters:$expected").assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals("original", sharedLibrary.comicBook.value?.id) }
        compose.runOnIdle { assertTrue(navController.popBackStack()) }
        compose.onNodeWithText("home").assertIsDisplayed()
    }

    @Test fun comicReaderUsesItsSearchSessionAndReturnsToTheSameDetail() {
        assertReaderSession(textMode = false)
    }

    @Test fun novelReaderUsesItsSearchSessionAndReturnsToTheSameDetail() {
        assertReaderSession(textMode = true)
    }

    private fun assertReaderSession(textMode: Boolean) {
        openInitialDetail()
        lateinit var model: LibraryViewModel
        lateinit var session: String
        val source = if (textMode) novelSource else comicSource
        val route = if (textMode) "novel_reader_online" else "comic_reader_online"
        compose.runOnIdle { navController.openDetailSearch("冒险", textMode) }
        compose.waitForIdle()
        compose.runOnIdle {
            val entry = navController.currentBackStackEntry!!
            session = entry.arguments!!.getString(LIBRARY_SESSION_ARGUMENT)!!
            model = librarySearchViewModel(entry, sharedLibrary)
            assertEquals(if (textMode) "novel" else "comic", model.aggregateKind.value)
            model.openComic(source.book("nested"))
            navController.navigate(libraryDestinationRoute("comic_chapters", session))
        }
        compose.onNodeWithText("comic_chapters:nested").assertIsDisplayed()
        compose.waitUntil(10_000) { model.comicChapters.value.isNotEmpty() }
        compose.runOnIdle {
            val chapter = model.comicChapters.value.first()
            if (textMode) model.loadChapterText(chapter) else model.loadChapterImages(chapter)
            navController.navigate(libraryDestinationRoute(route, session))
        }
        compose.onNodeWithText("$route:nested").assertIsDisplayed()
        compose.waitUntil(10_000) {
            if (textMode) model.novelChapterText.value == "text:nested-1"
            else model.comicChapterImages.value == listOf("image:nested-1")
        }
        compose.runOnIdle {
            assertSame(model, libraryViewModelForSession(navController, session, sharedLibrary))
            assertEquals("original", sharedLibrary.comicBook.value?.id)
            assertTrue(navController.popBackStack())
        }
        compose.onNodeWithText("comic_chapters:nested").assertIsDisplayed()
        compose.runOnIdle { navController.popBackStack() }
        compose.onNodeWithText("search").assertIsDisplayed()
        compose.runOnIdle { navController.popBackStack() }
        compose.onNodeWithText("comic_chapters:original").assertIsDisplayed()
    }

    @Test fun sourceSelectionIsLocalToSearchWhilePreferencesAreShared() {
        openInitialDetail()
        compose.runOnIdle { navController.openDetailSearch("冒险", false) }
        compose.waitForIdle()
        compose.runOnIdle {
            val model = librarySearchViewModel(navController.currentBackStackEntry!!, sharedLibrary)
            model.selectSource(novelSource.id)
            assertSame(novelSource, model.currentSource.value)
            assertSame(comicSource, sharedLibrary.currentSource.value)
            model.setMultiLanguageSearch(true)
            assertTrue(sharedLibrary.multiLanguageSearch.value)
            model.setMultiLanguageSearch(false)
            assertFalse(sharedLibrary.multiLanguageSearch.value)
            navController.popBackStack()
        }
        compose.onNodeWithText("comic_chapters:original").assertIsDisplayed()
    }

    @Test fun blankTagDoesNotAddAnEmptySearchLayer() {
        openInitialDetail()
        compose.runOnIdle {
            val entry = navController.currentBackStackEntry
            navController.openDetailSearch("  ", false)
            assertSame(entry, navController.currentBackStackEntry)
        }
        compose.onNodeWithText("comic_chapters:original").assertIsDisplayed()
    }

    private class FixtureSource(override val id: String, private val textMode: Boolean) : ComicSource {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        override val name = id
        override val capabilities = SourceCapabilities(supportComic = !textMode, supportOnlineText = textMode)
        fun book(bookId: String) = SearchBook(bookId, id, bookId, "fixture")
        override suspend fun search(keyword: String): SourceResult<List<SearchBook>> {
            calls += keyword
            return SourceResult.Success(listOf(book(keyword)))
        }
        override suspend fun getDetail(bookId: String) = SourceResult.Success(book(bookId))
        override suspend fun getChapters(bookId: String) = SourceResult.Success(listOf(ComicChapter("$bookId-1", "第一话")))
        override suspend fun getChapterImages(chapterId: String) = SourceResult.Success(listOf("image:$chapterId"))
        override suspend fun getChapterText(chapterId: String) = SourceResult.Success("text:$chapterId")
        override suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo> = error("No downloads in this fixture")
        override suspend fun login(credential: LoginCredential) = SourceResult.Success(true)
        override suspend fun logout() = Unit
        override suspend fun isLoggedIn() = false
    }
}
