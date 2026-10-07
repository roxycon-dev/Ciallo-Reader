package com.example.ui.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ComicFeatureDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private val rawPage = AtomicInteger(-1)

    private fun reader(config: ComicReaderConfig) {
        val store = ComicSettingsStore(compose.activity)
        store.saveBookConfig("feature-device", config)
        store.saveBookState("feature-device", ComicBookState())
        val pages = List(6) { index ->
            val file = File(compose.activity.cacheDir, "feature-device-$index.png")
            val image = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            canvas.drawColor(0xfff1dec2.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff334455.toInt(); textSize = 64f }
            canvas.drawRect(120f, 200f, 480f, 550f, paint)
            canvas.drawText("PAGE ${index + 1}", 100f, 700f, paint)
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            ComicPageRef.Local("feature-device-$index", file.absolutePath)
        }
        compose.setContent { MyApplicationTheme {
            ComicReaderCore(pages, "功能验证", chapterTitle = null, initialPage = 0, bookKey = "feature-device",
                onPageChanged = { raw, _ -> rawPage.set(raw) }, onExit = {})
        } }
        compose.waitUntil(8_000) { rawPage.get() == 0 }
    }

    private fun nativeCurl(): ComicCurlView {
        compose.waitForIdle()
        fun find(view: View): ComicCurlView? = when (view) {
            is ComicCurlView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        var result: ComicCurlView? = null
        compose.waitUntil(8_000) {
            compose.runOnUiThread { result = find(compose.activity.window.decorView) }
            result?.pageGeometryValid == true
        }
        return result!!
    }

    private fun touch(view: ComicCurlView, down: Long, action: Int) {
        compose.runOnUiThread {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, view.width * .5f, view.height * .5f, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    @Test fun nativeDoubleTapEntersZoomOnTheFirstGestureAndBackReturnsToCurl() {
        reader(ComicReaderConfig(fit = ComicFit.FIT_PAGE, pageAnim = ComicPageAnim.CURL))
        val view = nativeCurl()
        val first = SystemClock.uptimeMillis()
        touch(view, first, MotionEvent.ACTION_DOWN); SystemClock.sleep(40); touch(view, first, MotionEvent.ACTION_UP)
        SystemClock.sleep(80)
        val second = SystemClock.uptimeMillis()
        touch(view, second, MotionEvent.ACTION_DOWN); SystemClock.sleep(40); touch(view, second, MotionEvent.ACTION_UP)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("放大查看").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("放大查看").assertIsDisplayed()
        assertEquals(0, rawPage.get())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("放大查看").fetchSemanticsNodes().isEmpty() }
        assertTrue(view.pageGeometryValid)
    }

    @Test fun nativeLongPressZoomRestoresThePageOnRelease() {
        reader(ComicReaderConfig(fit = ComicFit.FIT_PAGE, pageAnim = ComicPageAnim.CURL))
        val view = nativeCurl()
        val down = SystemClock.uptimeMillis()
        touch(view, down, MotionEvent.ACTION_DOWN)
        SystemClock.sleep(650)
        compose.onNodeWithContentDescription("放大查看").assertIsDisplayed()
        touch(view, down, MotionEvent.ACTION_UP)
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("放大查看").fetchSemanticsNodes().isEmpty() }
        assertEquals(0, rawPage.get())
    }

    @Test fun settingsPauseAutoReadingAndVolumeKeysUntilThePanelCloses() {
        reader(ComicReaderConfig(fit = ComicFit.FIT_PAGE, pageAnim = ComicPageAnim.NONE,
            direction = ComicDirection.LTR, autoPageIntervalSec = 1f))
        compose.onNodeWithContentDescription("开始自动阅读").performClick()
        compose.onNodeWithContentDescription("阅读设置").performClick()
        compose.onNodeWithContentDescription("关闭阅读设置").assertIsDisplayed()
        val pageAtOpen = rawPage.get()
        SystemClock.sleep(1_400)
        compose.runOnUiThread { assertFalse(ComicVolumeKeyBridge.dispatch(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, true)) }
        assertEquals(pageAtOpen, rawPage.get())
        compose.onNodeWithContentDescription("关闭阅读设置").performClick()
        compose.onNodeWithContentDescription("暂停自动阅读").assertIsDisplayed()
        compose.waitUntil(5_000) { rawPage.get() > pageAtOpen }
        compose.onNodeWithContentDescription("暂停自动阅读").performClick()
    }
}
