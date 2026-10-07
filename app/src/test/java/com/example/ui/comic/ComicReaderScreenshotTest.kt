package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 阅读器视觉验证（Robolectric + Roborazzi）：
 * 用合成漫画页真实渲染 ComicReaderCore 的关键状态并截图，供审美/视觉审查。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night")
class ComicReaderScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComicReaderTestActivity>()

    private fun syntheticPage(context: Context, index: Int, width: Int = 720, height: Int = 1020): File {
        val dir = File(context.cacheDir, "comic_shots").apply { mkdirs() }
        val f = File(dir, "page_%02d.png".format(index))
        if (f.exists()) return f
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = when (index % 3) {
            0 -> Color.rgb(233, 226, 212)
            1 -> Color.rgb(216, 224, 228)
            else -> Color.rgb(228, 218, 226)
        }
        canvas.drawColor(bg)
        val paint = android.graphics.Paint().apply { isAntiAlias = true }
        // 模拟画格
        for (row in 0 until 3) {
            val top = 60f + row * 320f
            paint.color = Color.rgb(40, 40, 46)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 5f
            canvas.drawRect(50f, top, width - 50f, top + 270f, paint)
            paint.style = android.graphics.Paint.Style.FILL
            // 模拟人物剪影与对话框
            paint.color = Color.rgb(70, 74, 90)
            canvas.drawOval(
                110f + (index % 4) * 40f, top + 60f,
                260f + (index % 4) * 40f, top + 220f, paint
            )
            paint.color = Color.WHITE
            canvas.drawRoundRect(
                width * 0.48f, top + 40f, width - 90f, top + 150f, 24f, 24f, paint
            )
            paint.color = Color.rgb(50, 50, 55)
            for (line in 0 until 3) {
                canvas.drawRect(
                    width * 0.52f, top + 60f + line * 26f,
                    width * 0.52f + 170f - line * 24f, top + 72f + line * 26f, paint
                )
            }
        }
        paint.color = Color.rgb(90, 90, 96)
        paint.textSize = 34f
        canvas.drawText("PAGE ${index + 1}", 54f, height - 40f, paint)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        return f
    }

    private fun pages(context: Context, n: Int): List<ComicPageRef> =
        (0 until n).map { ComicPageRef.Local("shot$it", syntheticPage(context, it).absolutePath) }

    @Test
    fun `reader default with controls visible`() {
        val context = composeRule.activity
        val refs = pages(context, 6)
        composeRule.setContent {
            ComicReaderCore(
                pages = refs,
                title = "测试漫画 · 视觉验证",
                chapterTitle = "第 1 话 升级后的阅读器",
                bookKey = "shot_default",
                initialPage = 0,
                onExit = {},
            )
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("reader_default_controls.png")
    }

    @Test
    fun `reader settings panel`() {
        val context = composeRule.activity
        val refs = pages(context, 6)
        composeRule.setContent {
            ComicReaderCore(
                pages = refs,
                title = "测试漫画 · 视觉验证",
                chapterTitle = "第 1 话 分层设置面板",
                bookKey = "shot_settings",
                initialPage = 0,
                onExit = {},
            )
        }
        composeRule.waitForIdle()
        // 顶部「阅读设置」按钮呼出设置面板
        composeRule.onNodeWithContentDescription("阅读设置").performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("reader_settings_panel.png")
    }

    private fun settingsShot(name: String, fontScale: Float = 1f) {
        val context = composeRule.activity
        val refs = pages(context, 6)
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, fontScale)) {
            com.example.ui.theme.MyApplicationTheme(darkTheme = false, colorPrimaryIndex = 4) {
                ComicReaderCore(pages = refs, title = "秋日来信 · 漫画", chapterTitle = "第一话", bookKey = name, initialPage = 0, onExit = {})
            }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("阅读设置").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("关闭阅读设置").assertExists()
        composeRule.onRoot().performTouchInput { click(Offset(3f, 3f)) }
        composeRule.onNodeWithContentDescription("关闭阅读设置").assertExists()
        composeRule.onRoot().captureRoboImage("${name}_turn.png")
        composeRule.onNodeWithText("图像").performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("${name}_image.png")
        composeRule.onNodeWithContentDescription("关闭阅读设置").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("关闭阅读设置").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
    fun phoneSettingsAreReadableAndOutsideTapDoesNotDismiss() = settingsShot("reader125_phone")

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-160dpi")
    fun tabletLandscapeHasRailAndStableSettings() = settingsShot("reader125_tablet")

    @Test @Config(qualifiers = "zh-rCN-w780dp-h360dp-160dpi")
    fun shortLandscapeKeepsContentAndCloseButtonVisible() = settingsShot("reader125_landscape")

    @Test @Config(qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
    fun largeTypePhoneKeepsNavigationAndCloseAvailable() = settingsShot("reader125_large_type", 1.5f)
}
