package com.example.source

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.library.KeywordSearchPreview
import com.example.library.LibraryScreen
import com.example.library.LibraryUiState
import com.example.library.LibraryViewModel
import com.example.source.keyword.KeywordKeys
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class KeywordSearchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test fun originalOnlyFailureRemainsVisibleUnderSearch() {
        compose.setContent { MyApplicationTheme {
            KeywordSearchPreview(listOf("未收录名字"), "在线查询失败，使用已有关键词",
                mapOf(KeywordKeys.query("未收录名字") to "原词"), emptyMap())
        } }
        compose.onNodeWithText("搜索用词").assertIsDisplayed().performClick()
        compose.onNodeWithText("在线查询失败", substring = true).assertIsDisplayed()
        compose.onNodeWithText("原词 · 等待提交").assertIsDisplayed()
    }

    @Test fun onlineUpdateAppearsAndDetailsDistinguishSubmittedQueries() {
        val words = mutableStateOf(listOf("雪之下雪乃"))
        val status = mutableStateOf("正在在线补充名称")
        val dispatches = mutableStateOf<Map<String, Set<String>>>(emptyMap())
        compose.setContent { MyApplicationTheme {
            KeywordSearchPreview(words.value, status.value,
                mapOf(KeywordKeys.query("雪之下雪乃") to "原词", KeywordKeys.query("雪ノ下雪乃") to "在线"), dispatches.value)
        } }
        compose.onNodeWithText("正在在线补充名称", substring = true).assertIsDisplayed()
        compose.runOnUiThread {
            words.value = listOf("雪之下雪乃", "雪ノ下雪乃")
            status.value = "已在线补充并缓存"
            dispatches.value = mapOf(KeywordKeys.query("雪之下雪乃") to setOf("eh", "other"),
                KeywordKeys.query("雪ノ下雪乃") to setOf("eh"))
        }
        compose.onNodeWithText("已在线补充并缓存", substring = true).assertIsDisplayed()
        compose.onNodeWithText("搜索用词").assertIsDisplayed().performClick()
        compose.onNodeWithText("在线 · 已提交 1 个书源").assertIsDisplayed()
        compose.onNodeWithText("原词 · 已提交 2 个书源").assertIsDisplayed()
    }

    @Test fun realLibraryScreenShowsExactlyTheWordsSubmittedByItsViewModel(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        lateinit var vm: LibraryViewModel
        compose.runOnUiThread { vm = LibraryViewModel(app); store.put("keyword-device", vm) }
        compose.waitUntil(30_000) { vm.sourceManager.allSources.value.any { it.id == "js_pufei" } }
        val originals = vm.sourceManager.allSources.value.associateBy { it.id }
        val enabled = vm.sourceManager.enabledStates.value.toMap()
        val active = vm.currentSource.value?.id
        val multi = vm.prefs.multiLanguageSearch
        val online = vm.prefs.multiLanguageOnlineLookup
        val kind = vm.aggregateKind.value
        val aggregate = vm.aggregateMode.value
        val calls = CopyOnWriteArrayList<String>()
        val source = object : BookSource {
            override val id = "mangadex"
            override val name = "关键词验收书源"
            override val capabilities = originals.getValue(id).capabilities
            override suspend fun search(keyword: String): SourceResult<List<SearchBook>> {
                calls += keyword
                return SourceResult.Success(listOf(SearchBook(keyword, id, "命中：$keyword", "")))
            }
            override suspend fun getDetail(bookId: String) = SourceResult.Error(SourceException.BookNotFound)
            override suspend fun getDownloadInfo(bookId: String) = SourceResult.Error(SourceException.BookNotFound)
            override suspend fun isLoggedIn() = true
            override suspend fun login(credential: LoginCredential) = SourceResult.Success(true)
            override suspend fun logout() = Unit
        }
        try {
            originals.keys.forEach { vm.sourceManager.setSourceEnabled(it, false) }
            vm.sourceManager.registerSource(source)
            vm.sourceManager.setSourceEnabled(source.id, true)
            compose.runOnUiThread {
                vm.setMultiLanguageSearch(true); vm.setOnlineKeywordLookup(false)
                vm.setAggregateMode(true); vm.setAggregateKind("comic")
                vm.aggregateSearch("眼镜")
            }
            compose.waitUntil(30_000) { (vm.uiState.value as? LibraryUiState.AggregateResults)?.running == false }
            assertTrue(calls.containsAll(listOf("眼镜", "glasses", "眼鏡")))
            assertEquals(calls.toSet(), vm.searchKeywords.value.toSet())
            compose.setContent { MyApplicationTheme { LibraryScreen(vm, onBookImported = {}) } }
            compose.onNodeWithText("搜索用词").assertIsDisplayed().performClick()
            compose.onNodeWithText("glasses").assertIsDisplayed()
            compose.onNodeWithText("眼鏡").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { compose.activity.setContent { } }
            originals.values.forEach { vm.sourceManager.registerSource(it, enabled[it.id] ?: true) }
            enabled.forEach { (id, value) -> vm.sourceManager.setSourceEnabled(id, value) }
            active?.let { vm.sourceManager.setActiveSource(it) }
            compose.runOnUiThread {
                vm.setMultiLanguageSearch(multi); vm.setOnlineKeywordLookup(online)
                vm.setAggregateKind(kind); vm.setAggregateMode(aggregate); store.clear()
            }
        }
    }
}
