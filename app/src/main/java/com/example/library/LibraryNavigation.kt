package com.example.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.util.UUID

internal const val LIBRARY_SESSION_ARGUMENT = "librarySession"
internal const val LIBRARY_SEARCH_ROUTE = "library_search/{$LIBRARY_SESSION_ARGUMENT}"
private const val SEARCH_KEYWORD = "detailSearchKeyword"
private const val SEARCH_TEXT_MODE = "detailSearchTextMode"

internal fun librarySessionArguments() = listOf(
    navArgument(LIBRARY_SESSION_ARGUMENT) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    },
)

internal fun libraryDestinationRoute(destination: String, sessionId: String?): String =
    if (sessionId == null) destination
    else "$destination?$LIBRARY_SESSION_ARGUMENT=${Uri.encode(sessionId)}"

internal fun NavController.openDetailSearch(keyword: String, textMode: Boolean) {
    val query = keyword.trim()
    if (query.isEmpty()) return
    // Always push a new entry, including repeated searches for the same tag.
    navigate("library_search/${UUID.randomUUID()}")
    currentBackStackEntry?.savedStateHandle?.apply {
        set(SEARCH_KEYWORD, query)
        set(SEARCH_TEXT_MODE, textMode)
    }
}

internal fun librarySearchViewModel(
    entry: NavBackStackEntry,
    sharedLibrary: LibraryViewModel,
): LibraryViewModel = ViewModelProvider(entry, object : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LibraryViewModel::class.java))
        val model = LibraryViewModel(sharedLibrary).apply {
            requestAggregateSearch(
                entry.savedStateHandle.get<String>(SEARCH_KEYWORD).orEmpty(),
                entry.savedStateHandle.get<Boolean>(SEARCH_TEXT_MODE) ?: false,
            )
        }
        @Suppress("UNCHECKED_CAST")
        return model as T
    }
})[LibraryViewModel::class.java]

internal fun libraryViewModelForSession(
    navController: NavController,
    sessionId: String?,
    sharedLibrary: LibraryViewModel,
): LibraryViewModel = if (sessionId == null) sharedLibrary else librarySearchViewModel(
    navController.getBackStackEntry("library_search/$sessionId"), sharedLibrary,
)
