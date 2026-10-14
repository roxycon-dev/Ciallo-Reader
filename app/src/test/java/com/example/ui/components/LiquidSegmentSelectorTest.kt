package com.example.ui.components

import android.graphics.RuntimeShader
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderNode
import android.graphics.HardwareRenderer
import androidx.test.core.app.ApplicationProvider
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// SDK <=34 uses Android 12 Skia in Robolectric; SDK 35 supports child-shader .eval().
@Config(sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-420dpi")
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class LiquidSegmentSelectorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val options = listOf(0 to "关", 15 to "15分", 30 to "30分", 45 to "45分", 60 to "60分")

    @Test fun nativeOpticalShaderCompiles() {
        // Compile all SDF/refraction code against native Skia, rather than silently testing fallback.
        assertNotNull(RuntimeShader(LIQUID_SEGMENT_SHADER))
    }

    @Test fun opticalTextureCachesSurviveAnimationAndNativeShaderRenders() {
        val view = LiquidSegmentView(ApplicationProvider.getApplicationContext())
        view.configure(options.map { LiquidSegmentOption(it.first, it.second) }, 30, 0xfff9efeb.toInt(),
            0xffe2602a.toInt(), 0xff9a8a8e.toInt(), 0xff8e6bbf.toInt(), false, 12f, false, true, true)
        view.layout(0, 0, 945, 126)
        fun field(name: String): Any? = LiquidSegmentView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view)
        val track = field("trackBitmap")
        val glyphs = field("glyphBitmap")
        view.physics.select(4)
        repeat(60) { view.physics.step(1f / 60) }
        view.configure(options.map { LiquidSegmentOption(it.first, it.second) }, 60, 0xfff9efeb.toInt(),
            0xffe2602a.toInt(), 0xff9a8a8e.toInt(), 0xff8e6bbf.toInt(), false, 12f, false, true, true)
        assertSame(track, field("trackBitmap"))
        assertSame(glyphs, field("glyphBitmap"))
        val optics = requireNotNull(field("optics"))
        assertNotNull(optics)
        // RuntimeShader requires a hardware recording canvas, also in Robolectric's native Skia.
        val node = RenderNode("liquid-segments-test").apply { setPosition(0, 0, 945, 378) }
        val canvas = node.beginRecording(945, 378)
        view.draw(canvas)
        view.physics.select(0, snap = true)
        view.physics.beginDrag(); view.physics.dragBy(160f, 2100f, false)
        repeat(8) { view.physics.step(1f / 60) }
        canvas.save(); canvas.translate(0f, 126f)
        view.draw(canvas)
        canvas.restore()
        view.configure(options.map { LiquidSegmentOption(it.first, it.second) }, -1, 0xfff9efeb.toInt(),
            0xffe2602a.toInt(), 0xff9a8a8e.toInt(), 0xff8e6bbf.toInt(), false, 12f, false, true, true)
        view.endDrag(cancelled = true)
        canvas.save(); canvas.translate(0f, 252f); view.draw(canvas); canvas.restore()
        node.endRecording()
        val hardwareBitmap = HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap", RenderNode::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(null, node, 945, 378) as Bitmap
        val image = requireNotNull(hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false))
        val file = java.io.File("build/outputs/roborazzi/liquid-segments-optics.png").apply { parentFile.mkdirs() }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertNotEquals(image.getPixel(110, 63), image.getPixel(830, 63))
        assertNotEquals("custom values must not show the Off preset as selected", image.getPixel(830, 20), image.getPixel(830, 272))
        assertEquals("the Off slot must retain its plain track when no preset is selected", image.getPixel(103, 20), image.getPixel(103, 272))
        assertEquals("custom values must clear the previous drag appearance", image.getPixel(500, 20), image.getPixel(500, 272))
        assertNotNull("hardware drawing must not silently fall back", field("optics"))
        view.release()
        assertNull(field("trackBitmap")); assertNull(field("glyphBitmap")); assertNull(field("optics"))
    }

    @Test fun tapKeyboardAndExternalSelectionKeepRadioSemantics() {
        var changes = 0
        var selected by mutableIntStateOf(0)
        lateinit var inputMode: androidx.compose.ui.input.InputModeManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            MaterialTheme { SegmentedPillSelector(options, selected, { selected = it; changes++ }) }
        }
        compose.onNodeWithText("关").assertIsSelected()
        compose.onNodeWithText("15分").performClick().assertIsSelected()
        compose.onNodeWithText("15分").performClick()
        assertEquals(1, changes)
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithText("15分").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("15分").assertIsFocused()
        compose.onNodeWithText("15分").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("30分").assertIsSelected()
        compose.runOnIdle { selected = 60 }
        compose.onNodeWithText("60分").assertIsSelected()
    }

    @Test fun horizontalDragCommitsOnceAndVerticalScrollDoesNotSelect() {
        var changes = 0
        var selected by mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SegmentedPillSelector(options, selected, { selected = it; changes++ }, Modifier.testTag("timer"))
                    Spacer(Modifier.height(1200.dp))
                }
            }
        }
        compose.onNodeWithTag("timer").performTouchInput { swipeRight(durationMillis = 240) }
        compose.onNodeWithText("60分").assertIsSelected()
        assertEquals(1, changes)
        compose.onNodeWithText("30分").performTouchInput { swipeUp(startY = centerY, endY = -200f, durationMillis = 240) }
        assertEquals(1, changes)
        // A cancelled drag cannot persist a setting or replace the authoritative selection.
        compose.runOnIdle { /* Let the parent scroll settle before the cancel scenario. */ }
        compose.onRoot().performTouchInput { swipeDown(durationMillis = 240) }
        compose.onNodeWithTag("timer").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(-100f, 0f)); cancel() }
        assertEquals(1, changes)
        compose.onNodeWithText("60分").assertIsSelected()
    }

    @Test fun lightAndDarkControlsRetainOptionsBadgesAndCompactWidths() {
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Column {
                    for (dark in listOf(false, true)) {
                        MyApplicationTheme(darkTheme = dark) {
                            Surface {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SegmentedPillSelector(options, 30, {})
                                    SegmentedPillSelector(listOf(-1 to "极简", 0 to "流畅", 1 to "均衡", 2 to "高", 3 to "极致"), 2, {})
                                    SegmentedPillSelector(listOf(0 to "月", 1 to "年"), 1, {}, Modifier.width(120.dp))
                                    com.example.ui.favorite.ShelfSegmentedTabs(1, {}, 12)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onAllNodesWithText("我喜欢的").assertCountEquals(2)
        compose.onAllNodesWithText("60分").assertCountEquals(2)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/liquid-segments-light-dark.png")
    }

    @Test @Config(sdk = [28]) fun olderAndroidGlassFallbackRemainsSelectable() {
        var selected by mutableIntStateOf(0)
        compose.setContent { MaterialTheme { SegmentedPillSelector(options, selected, { selected = it }) } }
        compose.onNodeWithText("45分").performClick().assertIsSelected()
    }

    @Test fun customValueKeepsAllPresetButtonsUnselectedUntilUserChoosesOne() {
        var selected by mutableIntStateOf(-1)
        compose.setContent { MaterialTheme { SegmentedPillSelector(options, selected, { selected = it }) } }
        options.forEach { (_, label) -> compose.onNodeWithText(label).assertIsNotSelected() }
        compose.onNodeWithText("15分").performClick().assertIsSelected()
    }
}
