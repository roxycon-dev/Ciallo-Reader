package com.example.ui.components

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class LiquidSliderLifecycleTest {
    private fun LiquidSliderView.field(name: String) =
        LiquidSliderView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

    @Test fun sizeAndDensityRefreshTheExactOriginalGeometry() {
        val context = RuntimeEnvironment.getApplication()
        val view = LiquidSliderView(context)
        for ((w, h) in listOf(411 to 110, 800 to 220, 47 to 71)) {
            view.layout(0, 0, w, h)
            val d = context.resources.displayMetrics.density
            assertEquals(w / d, view.field("widthDp"))
            assertEquals(kotlin.math.min(26f, w / d / 3f), view.field("inset"))
            assertEquals((w / d - kotlin.math.min(26f, w / d / 3f) * 2).coerceAtLeast(1f), view.field("trackWidth"))
            assertEquals(kotlin.math.min(68f, h / d - 25f), view.field("cy"))
        }
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-xhdpi")
        view.dispatchConfigurationChanged(context.resources.configuration)
        val d = context.resources.displayMetrics.density
        assertEquals(2f, d, 0f)
        assertEquals(view.width / d, view.field("widthDp"))
        assertEquals(kotlin.math.min(26f, view.width / d / 3f), view.field("inset"))
        assertEquals(kotlin.math.min(68f, view.height / d - 25f), view.field("cy"))
    }
}
