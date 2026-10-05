package com.example.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.source.keyword.KeywordKeys
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h780dp-420dpi")
class KeywordSearchPreviewTest {
    @Test fun failedProviderDetailsAndManualRetryAreVisible() {
        var retries = 0
        compose.setContent { MyApplicationTheme(darkTheme = false) { Surface {
            KeywordSearchPreview(listOf("未收录名字"), "在线查询失败，使用已有关键词", emptyMap(), emptyMap(),
                providers = listOf(com.example.source.keyword.KeywordProviderState("bangumi:characters", "failed", detail = "网络连接超时")),
                onRetry = { retries++ })
        } } }
        compose.onNodeWithText("搜索用词").performClick()
        compose.onNodeWithText("Bangumi 角色 · 网络连接超时").assertIsDisplayed()
        compose.onNodeWithText("重新查词").performClick()
        compose.runOnIdle { assertTrue(retries == 1) }
        screenshot("keyword-retry.png")
    }

    @get:Rule val compose = createComposeRule()

    private fun screenshot(name: String) {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "gradlew.bat").exists() }
        val directory = File(root, "artifacts/multilingual-search-v5").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(directory, name).absolutePath)
    }

    @Test fun failureWithNoExpansionStillShowsOriginalAndItsSubmission() {
        compose.setContent { MyApplicationTheme(darkTheme = false) { Surface {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedTextField(value = "未收录名字", onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth())
                KeywordSearchPreview(listOf("未收录名字"), "在线查询失败，使用已有关键词",
                    mapOf(KeywordKeys.query("未收录名字") to "原词"), mapOf(KeywordKeys.query("未收录名字") to setOf("eh")))
            }
        } } }
        compose.onAllNodesWithText("未收录名字").assertCountEquals(2)
        compose.onNodeWithText("搜索用词").performClick()
        compose.onNodeWithText("原词 · 已提交 1 个书源").assertIsDisplayed()
        compose.onNodeWithText("在线查询失败", substring = true).assertIsDisplayed()
        screenshot("keyword-original-only.png")
    }

    @Test fun streamingOnlineNamesBecomeVisibleAndCanBeInspected() {
        val words = mutableStateOf(listOf("雪之下雪乃"))
        val status = mutableStateOf("正在在线补充名称")
        compose.setContent { MyApplicationTheme(darkTheme = false) { Surface {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedTextField(value = "雪之下雪乃", onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth())
                KeywordSearchPreview(words.value, status.value, mapOf(
                    KeywordKeys.query("雪之下雪乃") to "原词", KeywordKeys.query("雪ノ下雪乃") to "在线",
                    KeywordKeys.query("Yukino Yukinoshita") to "在线"), mapOf(
                    KeywordKeys.query("雪之下雪乃") to setOf("eh", "other"), KeywordKeys.query("雪ノ下雪乃") to setOf("eh")))
            }
        } } }
        compose.onNodeWithText("正在在线补充名称", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            words.value = listOf("雪之下雪乃", "雪ノ下雪乃", "Yukino Yukinoshita")
            status.value = "已在线补充并缓存"
        }
        screenshot("keyword-collapsed.png")
        compose.onNodeWithText("搜索用词").performClick()
        compose.onNodeWithText("雪ノ下雪乃").assertIsDisplayed()
        compose.onNodeWithText("在线 · 已提交 1 个书源").assertIsDisplayed()
        compose.onNodeWithText("Yukino Yukinoshita").assertIsDisplayed()
        compose.onNodeWithText("在线 · 等待提交").assertIsDisplayed()
        screenshot("keyword-online-preview.png")
    }

    @Test fun expandedLongNamesUseTheResultScrollerOnNarrowScreens() {
        val words = (1..6).map { "Work $it " + "Very Long Verified Title ".repeat(6) }
        compose.setContent { MyApplicationTheme(darkTheme = false) { Surface {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp).testTag("results")) {
                keywordSearchPreviewItem(words, "已在线补充并缓存", emptyMap(), emptyMap())
            }
        } } }
        compose.onNodeWithText("搜索用词").performClick()
        compose.onNodeWithTag("results").performScrollToNode(hasText(words.last()))
        compose.onNodeWithText(words.last()).assertIsDisplayed()
        screenshot("keyword-long-names.png")
    }

    @Test fun keywordsScrollAwayInTheAggregateGridWhileSearchRemainsVisible() {
        compose.setContent { MyApplicationTheme(darkTheme = false) { Surface {
            Column(Modifier.fillMaxSize()) {
                Text("固定搜索框", Modifier.testTag("search_field"))
                LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(2), Modifier.weight(1f).testTag("results"),
                    contentPadding = PaddingValues(16.dp)) {
                    keywordSearchPreviewItem(listOf("眼镜", "glasses", "眼鏡"), "已使用本地名称", emptyMap(), emptyMap())
                    items(30, key = { "comic_$it" }, contentType = { "comic" }) { Text("漫画 $it", Modifier.height(160.dp)) }
                }
            }
        } } }
        compose.onNodeWithTag("keyword_search_preview").assertIsDisplayed()
        val card = compose.onNodeWithTag("keyword_search_preview").fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("results").fetchSemanticsNode().boundsInRoot
        assertTrue("Keyword card must span both lanes", card.width > grid.width * 0.8f)
        screenshot("keyword-grid-top.png")
        compose.onNodeWithTag("results").performScrollToIndex(20)
        compose.onNodeWithTag("keyword_search_preview").assertIsNotDisplayed()
        compose.onNodeWithTag("search_field").assertIsDisplayed()
        screenshot("keyword-grid-scrolled.png")
        compose.onNodeWithTag("results").performScrollToIndex(0)
        compose.onNodeWithTag("keyword_search_preview").assertIsDisplayed()
    }

    @Test fun keywordsScrollAwayInSingleSourceResults() {
        compose.setContent { MyApplicationTheme(darkTheme = true) { Surface {
            Column(Modifier.fillMaxSize()) {
                Text("固定搜索框", Modifier.testTag("search_field"))
                LazyColumn(Modifier.weight(1f).testTag("results"), contentPadding = PaddingValues(16.dp)) {
                    keywordSearchPreviewItem(listOf("眼镜", "glasses", "眼鏡"), "已使用本地名称", emptyMap(), emptyMap())
                    items(30, key = { "book_$it" }, contentType = { "book" }) { Text("书籍 $it", Modifier.height(160.dp)) }
                }
            }
        } } }
        compose.onNodeWithTag("keyword_search_preview").assertIsDisplayed()
        compose.onNodeWithTag("results").performScrollToIndex(20)
        compose.onNodeWithTag("keyword_search_preview").assertIsNotDisplayed()
        compose.onNodeWithTag("search_field").assertIsDisplayed()
        compose.onNodeWithTag("results").performScrollToIndex(0)
        screenshot("keyword-dark.png")
    }

    @Test fun sourceOptionsDoNotOverlapAtLargeFontAndOnlineSwitchWorks() {
        val online = mutableStateOf(true)
        compose.setContent { MyApplicationTheme(darkTheme = false) { Surface {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(320.dp)) { KeywordSearchOptions(true, {}, online.value, { online.value = it }) }
            }
        } } }
        compose.onNodeWithText("开启：扩展常用词、作品、角色和作者别名").assertIsDisplayed()
        val first = compose.onNodeWithTag("multilingual_option").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("online_keyword_option").fetchSemanticsNode().boundsInRoot
        assertTrue("Option rows must not overlap", first.bottom <= second.top)
        val description = compose.onNodeWithText("开启：扩展常用词、作品、角色和作者别名").fetchSemanticsNode().boundsInRoot
        assertTrue("Supporting text must fit the first row", description.bottom <= first.bottom)
        compose.onAllNodes(isToggleable())[1].assertIsOn().performClick().assertIsOff()
        screenshot("keyword-options-large-font.png")
    }
}
