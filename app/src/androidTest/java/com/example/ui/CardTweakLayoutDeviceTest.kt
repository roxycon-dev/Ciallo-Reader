package com.example.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.theme.MyApplicationTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CardTweakLayoutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    @Test fun labelsAndValuesStayAboveTheSliderAtLargeFontAndWhileDragging() {
        val previous = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
        try {
            var value = .5f
            compose.setContent { MyApplicationTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                    Surface(color = MaterialTheme.colorScheme.surface) { Column(Modifier.width(300.dp).padding(12.dp)) {
                        var current by remember { mutableFloatStateOf(.5f) }
                        CardTweakSlider("卡片折射与边缘高光强度", "50%", current, "小", "大", { current = it; value = it })
                    } }
                }
            } }
            val label = compose.onNodeWithText("卡片折射与边缘高光强度").fetchSemanticsNode().boundsInRoot
            val number = compose.onNodeWithText("50%").fetchSemanticsNode().boundsInRoot
            val slider = compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNode().boundsInRoot
            assertTrue(label.bottom <= slider.top); assertTrue(number.bottom <= slider.top)
            assertTrue(label.right <= number.left); assertTrue(label.height > 0)
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).performTouchInput {
                down(center.copy(x = width * .5f, y = height * .6f))
                moveTo(center.copy(x = width * .8f, y = height * .6f), 400)
            }
            compose.waitForIdle()
            assertEquals(label, compose.onNodeWithText("卡片折射与边缘高光强度").fetchSemanticsNode().boundsInRoot)
            val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "paper-1.4.18").apply { mkdirs() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(dir, "card-parameters-large-font-drag.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).performTouchInput { up() }
            assertTrue(value > .5f)
        } finally { androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(previous) }
    }
}
