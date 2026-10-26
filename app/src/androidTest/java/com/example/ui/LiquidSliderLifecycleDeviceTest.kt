package com.example.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.components.LiquidSliderView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A real window is required: base Robolectric Activity can report GONE while isShown is true. */
@RunWith(AndroidJUnit4::class)
class LiquidSliderLifecycleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private lateinit var parent: FrameLayout
    private lateinit var slider: LiquidSliderView
    private fun field(name: String) =
        LiquidSliderView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(slider)

    private fun mount() {
        compose.setContent {
            AndroidView(modifier = Modifier.size(411.dp, 110.dp), factory = { context ->
                parent = FrameLayout(context)
                slider = LiquidSliderView(context).apply {
                    configure(0f..1f, 0, true, false, 0f, 0xff417c67.toInt(), false, false, false)
                    setValues(.25f, null)
                }
                parent.addView(slider, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                parent
            })
        }
        compose.waitForIdle()
    }

    @Test fun hiddenParentSuspendsFramesAndShowingItResumesTheSameView() {
        mount()
        compose.runOnIdle {
            assertTrue(slider.isAttachedToWindow)
            assertTrue(slider.isShown)
            assertEquals(View.VISIBLE, slider.windowVisibility)
            slider.setValues(.85f, null)
            assertEquals(true, field("running"))
            parent.visibility = View.INVISIBLE
            assertFalse(slider.isShown)
            assertEquals(false, field("running"))
            repeat(20) { slider.setValues(.2f + it / 100f, null) }
            assertEquals("Hidden value updates must not request frames", false, field("running"))
            parent.visibility = View.VISIBLE
            assertTrue(slider.isShown)
            assertEquals("An interrupted spring must resume when shown", true, field("running"))
            slider.stop()
        }
    }

    @Test fun reattachingWithoutAResizeRestartsAnInterruptedSpring() {
        mount()
        compose.runOnIdle {
            assertTrue(slider.isAttachedToWindow)
            assertTrue(slider.isShown)
            assertEquals(View.VISIBLE, slider.windowVisibility)
            slider.setValues(.85f, null)
            assertEquals(true, field("running"))
            val width = slider.width; val height = slider.height
            parent.removeView(slider)
            assertEquals(false, field("running"))
            assertNull(field("buffer"))
            parent.addView(slider)
            assertTrue(slider.isAttachedToWindow)
            assertTrue(slider.isShown)
            assertEquals(View.VISIBLE, slider.windowVisibility)
            assertEquals(width, slider.width); assertEquals(height, slider.height)
            assertEquals(true, field("running"))
            slider.stop()
        }
    }
}
