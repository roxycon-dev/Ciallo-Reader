package com.example.ui.components

import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.comic.ComicReaderTestActivity
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Identity display-list boundaries must not introduce a texture, clip or color rounding. */
@RunWith(AndroidJUnit4::class)
class SliderLayerParityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test fun identityLayerPreservesNativeSliderPixelsThroughRealTouchFrames() {
        val isolated = mutableStateOf(false)
        lateinit var view: LiquidSliderView
        compose.setContent {
            Box(Modifier.size(500.dp, 110.dp).testTag("slider-layer-proof").drawBehind {
                for (x in 0..(size.width / 9).toInt()) drawRect(
                    if (x % 2 == 0) Color(0xff243f52) else Color(0xffe8cba4),
                    Offset(x * 9f, 0f), Size(9f, size.height))
            }) {
                AndroidView(factory = { LiquidSliderView(it).also { v -> view = v } },
                    modifier = Modifier.size(500.dp, 110.dp).then(if (isolated.value) Modifier.graphicsLayer {} else Modifier))
            }
        }
        compose.waitForIdle()
        val callback = LiquidSliderView::class.java.getDeclaredField("frame").apply { isAccessible = true }
        fun pauseFrames() { view.removeCallbacks(callback.get(view) as Runnable) }
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "motion-resource").apply { mkdirs() }
        var frames = 0
        for (two in listOf(false, true)) for (night in listOf(false, true)) {
            compose.runOnUiThread {
                view.configure(0f..1f, 7, true, false, 0f, 0xff2c7656.toInt(), night, false, false)
                view.setValues(.25f, if (two) .78f else null)
                view.change = { _, _ -> }; pauseFrames()
            }
            for (frame in 0 until 16) {
                compose.runOnUiThread {
                    isolated.value = false
                    if (frame in 0..8) {
                        val action = if (frame == 0) MotionEvent.ACTION_DOWN else if (frame == 8) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                        val event = MotionEvent.obtain(0, frame * 16L, action,
                            view.width * (.25f + minOf(frame, 6) * .065f), view.height * .62f, 0)
                        view.onTouchEvent(event); event.recycle()
                    }
                    pauseFrames()
                    view.advancePhysics(.016f)
                    view.invalidate()
                }
                compose.waitForIdle()
                val before = compose.onNodeWithTag("slider-layer-proof").captureToImage().asAndroidBitmap()
                compose.runOnUiThread { isolated.value = true; pauseFrames(); view.invalidate() }
                compose.waitForIdle()
                val after = compose.onNodeWithTag("slider-layer-proof").captureToImage().asAndroidBitmap()
                val a = IntArray(before.width * before.height); val b = IntArray(a.size)
                before.getPixels(a, 0, before.width, 0, 0, before.width, before.height)
                after.getPixels(b, 0, after.width, 0, 0, after.width, after.height)
                if (!a.contentEquals(b)) {
                    File(out, "layer-$two-$night-$frame-original.png").outputStream().use { before.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    File(out, "layer-$two-$night-$frame-isolated.png").outputStream().use { after.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                assertArrayEquals("range=$two dark=$night frame=$frame", a, b)
                frames++
            }
        }
        compose.runOnUiThread { view.stop() }
        File(out, "slider-layer-parity.json").writeText(JSONObject().put("frames", frames).put("changedPixels", 0).toString())
    }
}
