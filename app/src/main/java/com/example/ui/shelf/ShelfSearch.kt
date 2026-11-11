package com.example.ui.shelf

import com.example.i18n.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import coil.compose.AsyncImage
import com.example.data.Book
import com.example.data.favorite.FavoriteItem
import com.example.ui.LocalAppBackgroundActive
import com.example.ui.components.GlassCard
import com.example.ui.components.LiquidSegmentOption
import com.example.ui.components.LiquidSegmentedSelector
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.wenku.RetainedPaneLifecycle
import com.example.ui.wenku.WenkuFlightBridge
import com.example.ui.wenku.WenkuIcon
import com.example.ui.wenku.WenkuLoadedContentSequence
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.rasterizeWenkuCover
import com.example.ui.wenku.rememberWenkuSearchMotion
import com.example.ui.wenku.wenkuBlockPointer
import com.example.ui.wenku.wenkuPlaneLayer
import com.example.ui.wenku.wenkuRetainedLayout
import com.example.ui.wenku.wenkuReveal
import java.io.File
import kotlinx.coroutines.delay

@Stable
class ShelfSearchState internal constructor(
    private val expansion: MutableState<Boolean>,
    private val text: MutableState<String>,
    private val searchField: MutableState<ShelfSearchField>,
    internal val motion: State<Float>
) {
    internal var expanded by expansion
        private set
    internal var query by text
    internal var field by searchField
    internal var requestKeyboard by mutableStateOf(false)
    internal val mounted by derivedStateOf { expanded || motion.value > 0f }
    internal val covered by derivedStateOf { expanded && motion.value >= 1f }

    internal fun open() {
        requestKeyboard = true
        expanded = true
    }

    internal fun close() {
        requestKeyboard = false
        expanded = false
    }
}

@Composable
fun rememberShelfSearchState(): ShelfSearchState {
    val expanded = rememberSaveable { mutableStateOf(false) }
    val query = rememberSaveable { mutableStateOf("") }
    val field = rememberSaveable { mutableStateOf(ShelfSearchField.Any) }
    val motion = rememberWenkuSearchMotion(expanded.value)
    return remember(expanded, query, field, motion) { ShelfSearchState(expanded, query, field, motion) }
}

/** Back belongs to the home route, outside the retained pane's lifecycle. */
@Composable
internal fun ShelfSearchBackHandler(state: ShelfSearchState, active: Boolean) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(enabled = active && state.mounted) {
        state.close()
        focus.clearFocus()
        keyboard?.hide()
    }
}

/** The pinned header and Dock use the same clock as the two retained archive planes. */
@Composable
internal fun ShelfSearchPlane(
    state: ShelfSearchState,
    books: List<Book>,
    favorites: List<FavoriteItem>,
    inaccessibleCategories: Set<String>,
    favoritesLocked: Boolean,
    onBookClick: (Book) -> Unit,
    onFavoriteClick: (FavoriteItem) -> Unit,
    modifier: Modifier = Modifier,
    shelf: @Composable () -> Unit
) {
    Box(modifier.clipToBounds()) {
        val mounted = state.mounted
        val background = if (LocalAppBackgroundActive.current) Color.Transparent
            else MaterialTheme.colorScheme.background
        RetainedPaneLifecycle(active = !state.covered) {
            Box(
                Modifier
                    .wenkuRetainedLayout(!state.covered)
                    .fillMaxSize()
                    .then(if (mounted) Modifier
                        .clearAndSetSemantics { }
                        .wenkuBlockPointer()
                        .wenkuPlaneLayer({ state.motion.value }, incoming = false, occludeOutgoing = true)
                    else Modifier)
            ) {
                shelf()
            }
        }
        if (mounted) {
            Box(
                Modifier.fillMaxSize()
                    .wenkuPlaneLayer({ state.motion.value }, incoming = true)
                    .background(background)
                    .then(if (state.expanded) Modifier else Modifier
                        .clearAndSetSemantics { }
                        .wenkuBlockPointer())
                    .shelfSearchBottomInsets()
            ) {
                ShelfSearchResults(
                    query = state.query,
                    field = state.field,
                    onFieldChange = { state.field = it },
                    books = books,
                    favorites = favorites,
                    inaccessibleCategories = inaccessibleCategories,
                    favoritesLocked = favoritesLocked,
                    onBookClick = onBookClick,
                    onFavoriteClick = onFavoriteClick
                )
            }
        }
    }
}

@Composable
private fun Modifier.shelfSearchBottomInsets(): Modifier {
    val ime = WindowInsets.ime
    val navigation = WindowInsets.navigationBars
    // Keep insets local to the search results. Chaining consumed insets on a
    // departing pane can recurse when IME removal and modifier updates coincide.
    return layout { measurable, constraints ->
        val bottom = maxOf(ime.getBottom(this), navigation.getBottom(this)).coerceAtMost(constraints.maxHeight)
        val content = measurable.measure(constraints.offset(vertical = -bottom))
        layout(content.width, content.height + bottom) { content.placeRelative(0, 0) }
    }
}

internal enum class ShelfSearchField(val title: String) {
    Any("全部"), Title("书名"), Author("作者");

    fun matches(query: String, title: String, author: String): Boolean =
        (this != Author && title.contains(query, ignoreCase = true)) ||
            (this != Title && author.contains(query, ignoreCase = true))
}

private val shelfSearchOptions = ShelfSearchField.entries.map { LiquidSegmentOption(it.ordinal, it.title) }

@Composable
private fun ShelfSearchResults(
    query: String,
    field: ShelfSearchField,
    onFieldChange: (ShelfSearchField) -> Unit,
    books: List<Book>,
    favorites: List<FavoriteItem>,
    inaccessibleCategories: Set<String>,
    favoritesLocked: Boolean,
    onBookClick: (Book) -> Unit,
    onFavoriteClick: (FavoriteItem) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var searchTerm by remember { mutableStateOf(query.trim()) }
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(250)
        searchTerm = query.trim()
    }
    val localResults = remember(books, inaccessibleCategories, field, searchTerm) {
        books.filter { it.category !in inaccessibleCategories && field.matches(searchTerm, it.title, it.author) }
    }
    val favoriteResults = remember(favorites, favoritesLocked, field, searchTerm) {
        if (favoritesLocked) emptyList()
        else favorites.filter { field.matches(searchTerm, it.favorite.title, it.favorite.author) }
    }
    val list = rememberLazyListState()
    var resultKey by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(field, searchTerm) {
        val next = "${field.ordinal}:${searchTerm}"
        if (resultKey.isNotEmpty() && resultKey != next) list.scrollToItem(0)
        resultKey = next
    }
    Column(Modifier.fillMaxSize().testTag("shelf_search_plane")) {
        LiquidSegmentedSelector(
            options = shelfSearchOptions,
            selected = field.ordinal,
            onSelect = {
                focusManager.clearFocus()
                onFieldChange(ShelfSearchField.entries[it])
            },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        WenkuLoadedContentSequence(contentKey = field to searchTerm) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("shelf_search_results"),
                state = list,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (localResults.isEmpty() && favoriteResults.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (searchTerm.isEmpty()) "暂无可搜索的书籍" else tr("没有找到匹配的书籍"),
                            Modifier.fillMaxWidth().padding(top = 20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (localResults.isNotEmpty()) {
                    item(key = "local_title") { SearchSectionTitle("我的书架", localResults.size) }
                    itemsIndexed(localResults, key = { _, book -> "book-${book.id}" }) { index, book ->
                        SearchBookRow(
                            book.title, book.author, book.coverUri, index,
                            flightKey = book.id.toString().takeUnless { book.isComic }
                        ) { onBookClick(book) }
                    }
                }
                if (favoriteResults.isNotEmpty()) {
                    item(key = "favorite_title") { SearchSectionTitle("我喜欢的", favoriteResults.size) }
                    itemsIndexed(favoriteResults, key = { _, item -> "favorite-${item.key}" }) { index, item ->
                        val favorite = item.favorite
                        SearchBookRow(
                            favorite.title, favorite.author, favorite.localThumbPath ?: favorite.coverUrl,
                            index + localResults.size
                        ) { onFavoriteClick(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionTitle(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).wenkuReveal(0)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text("${count} 项", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchBookRow(
    title: String,
    author: String,
    cover: String?,
    order: Int,
    flightKey: String? = null,
    onClick: () -> Unit
) {
    val coverModel = remember(cover) {
        when {
            cover.isNullOrBlank() -> null
            cover.startsWith('/') || (cover.length > 1 && cover[1] == ':') -> File(cover)
            else -> cover
        }
    }
    val fly = flightKey != null && !LocalReduceMotion.current
    val source = remember(flightKey, coverModel) { SearchCoverSource() }
    GlassCard(Modifier.fillMaxWidth().wenkuReveal(order + 1), onClick = {
        if (flightKey != null && fly) {
            source.bounds?.let { bounds ->
                WenkuFlightBridge.begin(flightKey, bounds, source.image, radius = 6.dp)
            }
        }
        onClick()
    }) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(width = 42.dp, height = 56.dp)
                    .then(if (fly) Modifier.onGloballyPositioned {
                        source.bounds = it.boundsInRoot()
                    } else Modifier)
                    .clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                WenkuIcon(WenkuSymbol.Book, animated = false, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (coverModel != null) {
                    AsyncImage(
                        coverModel, null, Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onSuccess = { state ->
                            if (fly) {
                                source.image = runCatching { rasterizeWenkuCover(state.result.drawable, 224) }.getOrNull()
                            }
                        }
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (author.isNotBlank()) {
                    Text(author, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private class SearchCoverSource {
    var bounds: Rect? = null
    var image: ImageBitmap? = null
}
