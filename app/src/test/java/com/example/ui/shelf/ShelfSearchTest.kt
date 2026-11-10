package com.example.ui.shelf

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.Book
import com.example.data.favorite.FavoriteEntity
import com.example.data.favorite.FavoriteItem
import com.example.ui.components.LocalRenderQuality
import com.example.ui.components.RenderQuality
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.wenku.WenkuFlightBridge
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h780dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShelfSearchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchRetainsShelfAndFiltersEveryScopeByAccess() {
        val publicBook = Book(id = 1, title = "Reader One", author = "Alma", filePath = "")
        val privateBook = Book(id = 2, title = "Private Reader", author = "Alma", filePath = "", category = "Private")
        val favorite = FavoriteItem(FavoriteEntity("source", "favorite", "Favorite Reader", "Alma"))
        val favoritesLocked = mutableStateOf(true)
        var mounts = 0
        var openedBook: Book? = null
        var openedFavorite: FavoriteItem? = null
        lateinit var search: ShelfSearchState
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            MyApplicationTheme {
                CompositionLocalProvider(LocalRenderQuality provides RenderQuality.MID) {
                    search = rememberShelfSearchState()
                    back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                    ShelfSearchPlane(
                        state = search,
                        books = listOf(publicBook, privateBook),
                        favorites = listOf(favorite),
                        inaccessibleCategories = setOf("Private"),
                        favoritesLocked = favoritesLocked.value,
                        onBookClick = { openedBook = it },
                        onFavoriteClick = { openedFavorite = it }
                    ) {
                        DisposableEffect(Unit) {
                            mounts++
                            onDispose { }
                        }
                        Text("Shelf viewport")
                    }
                    ShelfSearchBackHandler(search, active = true)
                }
            }
        }

        compose.onNodeWithText("Shelf viewport").assertIsDisplayed()
        compose.runOnIdle {
            search.field = ShelfSearchField.Author
            search.query = "alma"
            search.open()
        }
        compose.onNodeWithTag("shelf_search_plane").assertIsDisplayed()
        compose.onNodeWithText("Shelf viewport").assertDoesNotExist()
        compose.onNodeWithText(privateBook.title).assertDoesNotExist()
        compose.onNodeWithText(favorite.favorite.title).assertDoesNotExist()
        compose.onNodeWithText(publicBook.title).performClick()
        compose.runOnIdle {
            assertEquals(publicBook, openedBook)
            assertEquals(publicBook.id.toString(), WenkuFlightBridge.request?.bookKey)
            WenkuFlightBridge.cancel()
            favoritesLocked.value = false
        }
        compose.onNodeWithText(favorite.favorite.title).performClick()
        compose.runOnIdle {
            assertEquals(favorite, openedFavorite)
            back.onBackPressed()
        }
        compose.onNodeWithTag("shelf_search_plane").assertDoesNotExist()
        compose.onNodeWithText("Shelf viewport").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, mounts)
            assertEquals("alma", search.query)
            assertEquals(ShelfSearchField.Author, search.field)
        }
    }
}
