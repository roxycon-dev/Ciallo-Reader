package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicEnhancementZoomTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun show(loader: ComicPageLoader, spread: ComicSpread, config: ComicReaderConfig) {
        rule.setContent {
            Box(Modifier.requiredSize(400.dp, 700.dp)) {
                ComicCurlZoomOverlay(spread, loader, ComicBookState(), config, ComicZoomState(),
                    ComicGestureCallbacks(onTapZone = { _, _ -> }, onLongPress = {}, onPinchClose = {}, onEdgeBack = {}), null, {})
            }
        }
    }

    @Test fun zoomUsesTheSharedFinalCacheAcrossEveryEnhancementModeWithoutCurlCache() {
        val ref = ComicPageRef.Remote("zoom-jm-shaped", "https://example.invalid/never-download.jpg")
        val slot = ComicSlot(ref, 0)
        val config = mutableStateOf(ComicReaderConfig(enhanceMode = ComicEnhanceMode.SUPER_RES))
        val loader = ComicPageLoader(context)
        val bitmap = Bitmap.createBitmap(128, 189, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        for (mode in ComicEnhanceMode.entries) loader.putProcessedForTest(
            slotCacheKey(slot, ComicReaderConfig(enhanceMode = mode), ComicBookState()), bitmap)
        try {
            rule.setContent {
                Box(Modifier.requiredSize(400.dp, 700.dp)) {
                    ComicCurlZoomOverlay(ComicSpread(0, listOf(slot)), loader, ComicBookState(), config.value, ComicZoomState(),
                        ComicGestureCallbacks(onTapZone = { _, _ -> }, onLongPress = {}, onPinchClose = {}, onEdgeBack = {}), null, {})
                }
            }
            for (mode in ComicEnhanceMode.entries) {
                rule.runOnIdle { config.value = ComicReaderConfig(enhanceMode = mode) }
                rule.onNodeWithContentDescription("第 1 页").assertExists()
                rule.onNodeWithText("图片加载失败").assertDoesNotExist()
            }
        } finally { loader.shutdown() }
    }

    @Test fun missingSourceEndsInRetryAndCanRecoverAfterTheFileAppears() {
        val file = File(context.cacheDir, "enhance-zoom-retry.png").apply { delete() }
        val ref = ComicPageRef.Local("zoom-retry", file.absolutePath)
        val loader = ComicPageLoader(context)
        val config = ComicReaderConfig(enhanceMode = ComicEnhanceMode.SUPER_RES)
        try {
            show(loader, ComicSpread(0, listOf(ComicSlot(ref, 0))), config)
            rule.waitUntil(8000) { rule.onAllNodesWithText("点击重试").fetchSemanticsNodes().isNotEmpty() }
            val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            rule.onNodeWithText("点击重试").performClick()
            rule.waitUntil(8000) { rule.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("图片加载失败").assertDoesNotExist()
        } finally { loader.shutdown(); file.delete() }
    }

    @Test fun doubleZoomLoadsBothExactHalvesAndKeepsTheirIndependentBitmaps() {
        val ref = ComicPageRef.Local("zoom-double", "/unused")
        val slots = listOf(ComicSlot(ref, 0, ComicSplitHalf.RIGHT), ComicSlot(ref, 0, ComicSplitHalf.LEFT))
        val config = ComicReaderConfig(mode = ComicMode.DOUBLE, enhanceMode = ComicEnhanceMode.WAIFU2X)
        val loader = ComicPageLoader(context)
        slots.forEachIndexed { i, slot -> loader.putProcessedForTest(slotCacheKey(slot, config, ComicBookState()),
            Bitmap.createBitmap(128, 192, Bitmap.Config.ARGB_8888).apply { eraseColor(if (i == 0) Color.RED else Color.BLUE) }) }
        try {
            show(loader, ComicSpread(0, slots), config)
            rule.onAllNodesWithContentDescription("第 1 页").assertCountEquals(2)
            rule.onNodeWithText("加载失败·重试").assertDoesNotExist()
        } finally { loader.shutdown() }
    }

    @Test fun settingsDetailPreviewsHaveTerminalRetryAndRecoverWithoutCachingAFailure() {
        val file = File(context.cacheDir, "native-preview-retry.png").apply { delete() }
        val ref = ComicPageRef.Local("native-preview-retry", file.absolutePath)
        val loader = ComicPageLoader(context)
        try {
            rule.setContent {
                Column(Modifier.requiredSize(400.dp, 700.dp)) {
                    FilterPreview(ref, loader, ComicReaderConfig(enhanceMode = ComicEnhanceMode.SUPER_RES), 0)
                }
            }
            rule.waitUntil(8000) { rule.onAllNodesWithText("预览失败 · 重试").fetchSemanticsNodes().size == 2 }
            rule.onNodeWithContentDescription("当前效果").assertDoesNotExist()
            val bitmap = Bitmap.createBitmap(128, 192, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            rule.onAllNodesWithText("预览失败 · 重试").onFirst().performClick()
            rule.waitUntil(8000) { rule.onAllNodesWithContentDescription("当前效果").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("原图").assertExists()
            rule.onNodeWithText("原始细节").assertExists()
            rule.onAllNodesWithText("预览失败 · 重试").assertCountEquals(0)
        } finally { loader.shutdown(); file.delete() }
    }
}
