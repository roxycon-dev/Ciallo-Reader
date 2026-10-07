package com.example.ui.comic

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import fi.harism.curl.CurlPage
import fi.harism.curl.CurlRenderer
import fi.harism.curl.CurlView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ComicCurlTrackingTest {
    private fun field(owner: Any, name: String): Any = CurlView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun pointer(view: ComicCurlView): PointF {
        val pointer = field(view, "mPointerPos")
        return PointF(pointer.javaClass.getDeclaredField("mPos").apply { isAccessible = true }.get(pointer) as PointF)
    }
    private fun check(width: Int, rtl: Boolean, double: Boolean, cancel: Boolean) {
        val view = ComicCurlView(ApplicationProvider.getApplicationContext<Context>())
        view.layout(0, 0, width, 1200)
        view.longPressZoomEnabled = false; view.doubleTapZoomEnabled = false
        view.swipeEnabled = true
        view.setViewMode(if (double) CurlView.SHOW_TWO_PAGES else CurlView.SHOW_ONE_PAGE)
        view.setSpreadStep(if (double) 2 else 1)
        view.setRightToLeft(rtl)
        val renderer = field(view, "mRenderer") as CurlRenderer
        for ((name, value) in listOf("mViewportWidth" to width, "mViewportHeight" to 1200))
            CurlRenderer::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(renderer, value)
        (CurlRenderer::class.java.getDeclaredField("mViewRect").apply { isAccessible = true }.get(renderer) as RectF)
            .set(-width / 1200f, 1f, width / 1200f, -1f)
        val l = 100f; val r = width - 100f; val mid = width / 2f
        renderer.setPageRectPixels(RectF(l, 100f, if (double) mid else l, 1100f), RectF(if (double) mid else l, 100f, r, 1100f))
        view.setBookTouchBounds(l, r)
        view.setPageProvider(object : CurlView.PageProvider {
            override fun getPageCount() = 10
            override fun updatePage(page: CurlPage, w: Int, h: Int, index: Int) { page.setColor(android.graphics.Color.WHITE, CurlPage.SIDE_BOTH) }
        })
        view.currentIndex = 2
        val t = SystemClock.uptimeMillis()
        val start = width * 0.35f
        val sign = if (rtl) 1f else -1f
        fun send(at: Long, action: Int, distance: Float) {
            val event = MotionEvent.obtain(t, t + at, action, start + distance * sign, 600f, 0)
            view.onTouch(view, event); event.recycle()
        }
        send(0, MotionEvent.ACTION_DOWN, 0f)
        send(100, MotionEvent.ACTION_MOVE, 50f)
        val first = pointer(view)
        send(200, MotionEvent.ACTION_MOVE, 140f)
        val held = pointer(view)
        // Renderer world scale is 2 / 1200; physical 90px must remain exactly 90px.
        assertEquals(90f * 2f / 1200f, kotlin.math.abs(held.x - first.x), 0.0001f)
        send(600, if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, 140f)
        val source = field(view, "mAnimationSource") as PointF
        assertEquals("Release must start at the held point", held.x, source.x, 0.0001f)
        assertEquals(held.y, source.y, 0.0001f)
        assertEquals(if (cancel) 2 else 1, field(view, "mAnimationTargetEvent"))
        assertTrue(view.isAnimating())
    }
    @Test fun phoneCornersTrackOneToOneAndReleaseContinuously() { for (rtl in listOf(false, true)) check(1080, rtl, false, false) }
    @Test fun tabletCornersTrackOneToOneAndStillCommitShortDrags() { for (rtl in listOf(false, true)) check(1920, rtl, false, false) }
    @Test fun tabletSpreadsTrackOneToOneInBothDirections() { for (rtl in listOf(false, true)) check(1920, rtl, true, false) }
    @Test fun cancelledDragsAlwaysReturnWithoutTeleporting() { for (rtl in listOf(false, true)) check(1920, rtl, true, true) }
}
