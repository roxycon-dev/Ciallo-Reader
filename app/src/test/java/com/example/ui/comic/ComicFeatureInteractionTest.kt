package com.example.ui.comic

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
class ComicFeatureInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private fun refs(): List<ComicPageRef> = List(6) { index ->
        val file = File(compose.activity.cacheDir, "feature-$index.png")
        val bmp = Bitmap.createBitmap(180, 280, Bitmap.Config.ARGB_8888).apply { eraseColor(0xfffafaf7.toInt()) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        ComicPageRef.Local("feature$index", file.absolutePath)
    }

    @Test fun allSevenFitChoicesAreReachableAndDefaultWidthIsSelected() {
        var config by mutableStateOf(ComicReaderConfig())
        val loader = ComicPageLoader(compose.activity)
        try {
            compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) {
                ComicSettingsSheet(config, ComicSettingsStore(compose.activity), false, {}, { config = it }, {}, {},
                    emptyList(), loader, 0, ComicBookState(), {}, {})
            } } }
            compose.onNodeWithText("显示").performClick()
            compose.onNodeWithText("适应宽度").assertIsSelected()
            for ((label, fit) in listOf("整页" to ComicFit.FIT_PAGE, "高度" to ComicFit.FIT_HEIGHT,
                "原始" to ComicFit.ORIGINAL, "铺满" to ComicFit.FILL, "拉伸" to ComicFit.STRETCH,
                "自定义" to ComicFit.CUSTOM)) {
                compose.onAllNodesWithText(label).onFirst().performClick()
                compose.runOnIdle { assertEquals(fit, config.fit) }
            }
        } finally { loader.shutdown() }
    }

    @Test fun switchLabelHasOneOperableSwitchSemanticsAndTogglesTheSetting() {
        var checked by mutableStateOf(true)
        compose.setContent { MaterialTheme { SwitchRow("双击放大", "切换放大档位", checked) { checked = it } } }
        compose.onNodeWithText("双击放大").assertIsToggleable().assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertFalse(checked) }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
    }

    @Test fun screenReaderProgressUsesIntegerStepsAndAUsefulLabel() {
        var interval by mutableStateOf(6f)
        compose.setContent { MaterialTheme {
            SliderRow("翻页间隔", interval, 1f..120f, steps = 118, format = { "${it.toInt()} 秒" }) { interval = it }
        } }
        compose.onNodeWithContentDescription("翻页间隔").performSemanticsAction(SemanticsActions.SetProgress) { it(34.4f) }
        compose.runOnIdle { assertEquals(34f, interval, .001f) }
    }

    @Test fun savedReadingPositionSurvivesInitialLayoutRebuild() {
        val pages = refs()
        val store = ComicSettingsStore(compose.activity)
        store.saveBookConfig("restore", ComicReaderConfig(pageAnim = ComicPageAnim.NONE, fit = ComicFit.FIT_PAGE))
        store.saveBookState("restore", ComicBookState(lastPage = 3, lastChapterSig = pages.first().id))
        var page = -1
        compose.setContent { MaterialTheme { ComicReaderCore(pages, "恢复页码", chapterTitle = null, initialPage = 0, bookKey = "restore",
            onPageChanged = { raw, _ -> page = raw }, onExit = {}) } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(3, page) }
    }

    @Test fun disablingBookOverrideRestoresGlobalWithoutOverwritingOtherBooks() {
        val store = ComicSettingsStore(compose.activity)
        val global = ComicReaderConfig(pageAnim = ComicPageAnim.NONE, fit = ComicFit.FIT_PAGE, filterGamma = .9f)
        store.saveGlobalConfig(global)
        store.saveBookConfig("override", global.copy(filterGamma = 1.8f, direction = ComicDirection.LTR))
        val pages = refs()
        compose.setContent { MaterialTheme { ComicReaderCore(pages, "独立设置", chapterTitle = null, initialPage = 0, bookKey = "override", onExit = {}) } }
        compose.onNodeWithContentDescription("阅读设置").performClick()
        compose.onNodeWithText("本漫画独立设置").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle {
            assertNull(store.loadBookConfig("override"))
            assertEquals(global, store.loadGlobalConfig())
            assertEquals(global, store.effectiveConfig("another-book").config)
        }
    }

    @Test fun presetUpdateAndDeleteConfirmationOperateOnTheActualStoredSnapshot() {
        val store = ComicSettingsStore(compose.activity)
        val saved = store.createPreset("待打磨预设", "SC", ComicReaderConfig())
        val current = ComicReaderConfig(mode = ComicMode.CONTINUOUS, filterGamma = 1.3f)
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { ComicPresetSheet(store, current, {}, {}) } } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("用当前设置更新预设"))
        compose.onNodeWithContentDescription("用当前设置更新预设").performClick()
        compose.runOnIdle { assertEquals(current, store.loadPresets().first { it.id == saved.id }.config) }
        compose.onNodeWithContentDescription("删除").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(store.loadPresets().any { it.id == saved.id }) }
    }
    @Test
    @Config(qualifiers = "zh-rCN-w780dp-h360dp-land-320dpi")
    fun cropFitsShortLandscapeAndDraggingThroughTheOppositeEdgeKeepsAValidSelection() {
        val loader = ComicPageLoader(compose.activity)
        var saved: List<Float>? = null
        try {
            compose.setContent { MaterialTheme {
                ComicCropSheet(refs().first(), loader,
                    ComicReaderConfig(manualCrop = listOf(.2f, .2f, .8f, .8f)), {},
                    { left, top, right, bottom -> saved = listOf(left, top, right, bottom) })
            } }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("裁剪选区").fetchSemanticsNodes().isNotEmpty()
            }
            val selection = compose.onNodeWithContentDescription("裁剪选区")
            val bounds = selection.fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width > 0 && bounds.height > 0)
            assertTrue(bounds.top >= root.top && bounds.bottom <= root.bottom)
            selection.performTouchInput {
                swipe(Offset(width * .8f, height * .2f), Offset(width * .05f, height * .05f), 300)
            }
            compose.onNodeWithText("保存").performClick()
            compose.runOnIdle {
                val crop = saved!!
                assertEquals(.2f, crop[0], .001f)
                assertTrue(crop[2] - crop[0] >= .04999f)
                assertTrue(crop[3] - crop[1] >= .04999f)
            }
        } finally { loader.shutdown() }
    }

}
