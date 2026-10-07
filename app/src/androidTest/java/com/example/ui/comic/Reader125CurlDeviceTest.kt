package com.example.ui.comic

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import fi.harism.curl.CurlView
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the production Compose -> native GL integration rather than only gesture math. */
class Reader125CurlDeviceTest {
    @Test fun leftSideForwardDragAndBothEdgeTapsAnimateOnlyTheBook() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, ComicVisualProbeActivity::class.java)
            .putExtra("mode", "SINGLE").putExtra("direction", "LTR").putExtra("anim", "CURL")
            .putExtra("fit", "FIT_PAGE").putExtra("bg", "PAPER")
        val scenario = ActivityScenario.launch<ComicVisualProbeActivity>(intent)
        lateinit var view: ComicCurlView
        var foundView = false
        fun find(node: View): ComicCurlView? = when (node) {
            is ComicCurlView -> node
            is ViewGroup -> (0 until node.childCount).firstNotNullOfOrNull { find(node.getChildAt(it)) }
            else -> null
        }
        fun await(predicate: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 8_000
            while (!predicate() && SystemClock.uptimeMillis() < until) SystemClock.sleep(20)
            assertTrue("Timed out waiting for native curl", predicate())
        }
        fun copy(): Bitmap {
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val latch = CountDownLatch(1)
            var status = -1
            PixelCopy.request(view, image, { status = it; latch.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue(latch.await(4, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, status)
            return image
        }
        fun save(image: Bitmap, name: String) {
            val file = File(context.filesDir, "reader125/$name.png").apply { parentFile!!.mkdirs() }
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun send(t: Long, at: Long, action: Int, x: Float) {
            scenario.onActivity {
                val event = MotionEvent.obtain(t, at, action, x, view.height * 0.5f, 0)
                view.dispatchTouchEvent(event); event.recycle()
            }
        }
        try {
            await { scenario.onActivity { find(it.window.decorView)?.let { found -> view = found; foundView = true } }; foundView }
            await { view.width > 0 && view.pageGeometryValid }
            SystemClock.sleep(500)
            // Hide the initial reader controls using the same center-tap callback as production.
            scenario.onActivity { view.onQuickTap?.invoke(view.width * 0.5f, view.height * 0.5f) }
            SystemClock.sleep(300)
            val before = copy(); save(before, "before")
            val t = SystemClock.uptimeMillis()
            val start = view.width * 0.35f // left half: the old implementation chose the previous page here.
            // Exercise an intentional slow turn above the phone/tablet physical threshold.
            // A fixed 95dp drag is below the tablet limit (104dp) and should roll back.
            val distance = comicTurnTravel(view.width.toFloat(), view.resources.displayMetrics.density) +
                16f * view.resources.displayMetrics.density
            send(t, t, MotionEvent.ACTION_DOWN, start)
            send(t, t + 600, MotionEvent.ACTION_MOVE, start - distance)
            SystemClock.sleep(70)
            val state = CurlView::class.java.getDeclaredField("mCurlState").apply { isAccessible = true }
            assertEquals("A leftwards swipe from the left region must curl forward", 2, state.getInt(view))
            val pointer = CurlView::class.java.getDeclaredField("mPointerPos").apply { isAccessible = true }.get(view)
            val held = android.graphics.PointF(pointer.javaClass.getDeclaredField("mPos").apply { isAccessible = true }.get(pointer) as android.graphics.PointF)
            val fold = copy(); save(fold, "left-region-forward-fold")
            assertEquals("Reading backdrop must remain static", before.getPixel(8, 8), fold.getPixel(8, 8))
            send(t, t + 650, MotionEvent.ACTION_UP, start - distance)
            val source = CurlView::class.java.getDeclaredField("mAnimationSource").apply { isAccessible = true }.get(view) as android.graphics.PointF
            assertEquals("Release cannot teleport ahead of the finger", held.x, source.x, 0.001f)
            val target = CurlView::class.java.getDeclaredField("mAnimationTargetEvent").apply { isAccessible = true }
            assertEquals("Intentional slow swipe must settle forward", 1, target.getInt(view))
            await { view.currentIndex == 1 && !view.isAnimating() }
            fun tapAndCheck(fraction: Float, target: Int, name: String) {
                val down = SystemClock.uptimeMillis()
                send(down, down, MotionEvent.ACTION_DOWN, view.width * fraction)
                send(down, down + 40, MotionEvent.ACTION_UP, view.width * fraction)
                await { view.autoFlipping }
                SystemClock.sleep(90)
                assertTrue("Edge tap must have an active fold", view.autoFlipping || view.isAnimating())
                save(copy(), name + "-fold")
                await { view.currentIndex == target && !view.autoFlipping && !view.isAnimating() }
                save(copy(), name + "-after")
            }
            tapAndCheck(0.90f, 2, "right-tap-forward")
            tapAndCheck(0.10f, 1, "left-tap-backward")
        } finally { scenario.close() }
    }
}
