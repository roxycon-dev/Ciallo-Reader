package com.example.ui

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.components.*
import com.example.ui.theme.MyApplicationTheme
import dev.liquidglass.compose.*
import dev.liquidglass.core.GlassRenderTier
import java.io.File
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Same test APK drives both frozen and optimized target APKs; no production benchmark UI. */
@RunWith(AndroidJUnit4::class)
class FoldMotionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val dir get() = File(instrumentation.targetContext.getExternalFilesDir(null), "fold-motion").apply { mkdirs() }

    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun measureDenseControls() {
        val args = InstrumentationRegistry.getArguments()
        val variant = args.getString("variant", "optimized")
        val iteration = args.getString("iteration", "0")
        val scenario = args.getString("scenario", "mixed")
        val variants = if (scenario == "primary") List(3) { AppButtonVariant.Primary } else AppButtonVariant.entries.toList()
        var hardware = false
        var actions = 0
        val coldFrames = Collections.synchronizedList(mutableListOf<Long>())
        val coldThread = HandlerThread("fold-cold-metrics").apply { start() }
        val coldListener = Window.OnFrameMetricsAvailableListener { _, frame, _ -> coldFrames.add(frame.getMetric(FrameMetrics.TOTAL_DURATION)) }
        compose.runOnUiThread { compose.activity.window.addOnFrameMetricsAvailableListener(coldListener, Handler(coldThread.looper)) }
        val coldStart = System.nanoTime()
        compose.setContent { MyApplicationTheme(darkTheme = false) {
            CompositionLocalProvider(LocalRenderQuality provides RenderQuality.HIGH) {
                DialogLiquidGlass {
                    val view = LocalView.current
                    SideEffect { hardware = view.isHardwareAccelerated }
                    Column(Modifier.fillMaxSize().background(Color(0xffeee8e0)).padding(16.dp)) {
                        Text("固定动效验证 · 按钮 / 开关 / 滑条 / 玻璃", color = MaterialTheme.colorScheme.onSurface)
                        repeat(3) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                repeat(4) { col ->
                                    val index = row * 4 + col
                                    var checked by remember { mutableStateOf(index % 2 == 0) }
                                    Box(Modifier.testTag("switch-$index")) { AppSwitch(checked, { checked = it }) }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        repeat(variants.size) { row ->
                            AppActionButton("固定操作 $row", { actions++ }, modifier = Modifier.testTag("action-$row"), variant = variants[row], fullWidth = true)
                            Spacer(Modifier.height(8.dp))
                        }
                        var value by remember { mutableStateOf(.25f) }
                        LiquidSlider(value, { value = it }, modifier = Modifier.testTag("slider"))
                    }
                }
            }
        } }
        compose.waitForIdle()
        assertTrue("Benchmark must use HWUI", hardware)
        RuntimeShader("half4 main(float2 p) { return half4(1,0,0,1); }")
        repeat(3) { compose.onNodeWithTag("switch-$it").performClick() }
        compose.onNodeWithTag("action-0").performTouchInput { click() }
        compose.waitForIdle()
        val coldElapsed = (System.nanoTime() - coldStart) / 1e6
        compose.runOnUiThread { compose.activity.window.removeOnFrameMetricsAvailableListener(coldListener) }
        coldThread.quitSafely(); coldThread.join(3000)
        val cold = synchronized(coldFrames) { coldFrames.sorted() }
        screenshot("$variant-$iteration-before")
        val metrics = Collections.synchronizedList(mutableListOf<LongArray>())
        val thread = HandlerThread("fold-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            metrics.add(longArrayOf(frame.getMetric(FrameMetrics.TOTAL_DURATION),
                frame.getMetric(FrameMetrics.DRAW_DURATION), frame.getMetric(FrameMetrics.GPU_DURATION),
                frame.getMetric(FrameMetrics.DEADLINE)))
        }
        compose.runOnUiThread { compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        val allocatedBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0L
        val start = System.nanoTime()
        try {
            repeat(12) { index ->
                compose.onNodeWithTag("switch-${index % 12}").performTouchInput { click() }
                compose.onNodeWithTag("action-${index % variants.size}").performTouchInput {
                    down(center); advanceEventTime(48); up()
                }
                if (index % 3 == 0) compose.onNodeWithTag("slider").performTouchInput {
                    swipe(androidx.compose.ui.geometry.Offset(width * .25f, height * .62f),
                        androidx.compose.ui.geometry.Offset(width * .75f, height * .62f), 160)
                }
            }
            compose.waitForIdle()
        } finally {
            compose.runOnUiThread { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely(); thread.join(3000)
        }
        val allocatedAfter = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0L
        val snapshot = synchronized(metrics) { metrics.toList() }
        assertTrue("No frame metrics delivered", snapshot.size >= 20)
        fun percentile(index: Int, q: Double): Double {
            val sorted = snapshot.map { it[index] }.filter { it >= 0 }.sorted()
            return if (sorted.isEmpty()) -1.0 else sorted[((sorted.size - 1) * q).toInt()] / 1e6
        }
        val result = JSONObject().put("variant", variant).put("iteration", iteration.toInt())
            .put("scenario", scenario)
            .put("frames", snapshot.size).put("hardware", hardware).put("agslCompiled", true)
            .put("p50Ms", percentile(0, .5)).put("p95Ms", percentile(0, .95))
            .put("drawP95Ms", percentile(1, .95)).put("gpuP95Ms", percentile(2, .95))
            .put("deadlineRatio", snapshot.count { it[3] > 0 && it[0] > it[3] }.toDouble() / snapshot.size)
            .put("allocatedBytes", (allocatedAfter - allocatedBefore).coerceAtLeast(0))
            .put("elapsedMs", (System.nanoTime() - start) / 1e6).put("actions", actions)
            .put("coldElapsedMs", coldElapsed).put("coldFrames", cold.size)
            .put("coldP95Ms", if (cold.isEmpty()) -1.0 else cold[((cold.size - 1) * .95).toInt()] / 1e6)
            .put("rawFrames", JSONArray(snapshot.map { JSONArray(it.toList()) }))
        File(dir, "$variant-$iteration.json").writeText(result.toString(2))
        screenshot("$variant-$iteration-after")
        assertEquals(13, actions)
    }

    @Test fun dynamicBackdropAndResizingKeepRealGlassLive() {
        var source by mutableStateOf(Color.Red)
        var wide by mutableStateOf(false)
        var tier by mutableStateOf(GlassRenderTier.SHADER)
        compose.setContent {
            val state = rememberLiquidGlassProviderState()
            SideEffect { state.requestedTier = tier }
            Box(Modifier.fillMaxSize().background(Color.White)) {
                Box(Modifier.fillMaxSize().liquidGlassProvider(state, visible = false).background(source))
                Box(Modifier.size(if (wide) 280.dp else 180.dp, 80.dp).testTag("live-glass")
                    .liquidGlass(state, GlassStyle.Regular.interactive()))
            }
        }
        compose.waitForIdle()
        fun pixel(): Int {
            val image = compose.onNodeWithTag("live-glass").captureToImage()
            val pixels = image.toPixelMap()
            return pixels[image.width / 2, image.height / 2].let {
                (it.red * 255).toInt() shl 16 or ((it.green * 255).toInt() shl 8) or (it.blue * 255).toInt()
            }
        }
        val red = pixel()
        val resting = compose.onNodeWithTag("live-glass").captureToImage()
        repeat(12) { compose.onNodeWithTag("live-glass").performTouchInput { down(center); up() } }
        compose.waitForIdle()
        val released = compose.onNodeWithTag("live-glass").captureToImage()
        val a = resting.toPixelMap(); val b = released.toPixelMap()
        var changes = 0
        for (y in 0 until resting.height) for (x in 0 until resting.width) if (a[x, y] != b[x, y]) changes++
        assertEquals("A same-frame DOWN/UP must not leave a queued glass press active", 0, changes)
        compose.runOnIdle { source = Color.Blue }
        compose.waitForIdle()
        val blue = pixel()
        assertNotEquals("Recorded child source must stay live", red, blue)
        assertTrue((red shr 16 and 255) > (red and 255))
        assertTrue((blue and 255) > (blue shr 16 and 255))
        compose.runOnIdle { wide = true; source = Color.Green }
        compose.waitForIdle()
        val green = pixel()
        assertTrue((green shr 8 and 255) > (green and 255))
        for (requested in listOf(GlassRenderTier.BLUR, GlassRenderTier.SCRIM, GlassRenderTier.SHADER)) {
            compose.runOnIdle { tier = requested; source = Color.Red }
            compose.waitForIdle()
            val redInTier = pixel()
            compose.runOnIdle { source = Color.Blue }
            compose.waitForIdle()
            val blueInTier = pixel()
            assertTrue("Red source remains live on $requested", (redInTier shr 16 and 255) > (redInTier and 255))
            assertTrue("Blue source remains live on $requested", (blueInTier and 255) > (blueInTier shr 16 and 255))
        }
        screenshot("live-backdrop-resized")
    }

    @Test fun foldUnfoldAndResizeRetainTextAndInputState() {
        val text = ("折叠屏尺寸变化时保留正文和阅读位置。This is a stable reading paragraph.\n").repeat(160)
        var ready = false
        var emptyAfterReady = 0
        var measuredWidth = 0
        var checked by mutableStateOf(true)
        var latestPages: List<String> = emptyList()
        val widths = mutableListOf<Int>()
        compose.setContent { MyApplicationTheme {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val width = with(density) { (maxWidth - 32.dp).roundToPx() }.coerceAtLeast(60)
                val height = with(density) { (maxHeight - 120.dp).roundToPx() }.coerceAtLeast(120)
                val pages = rememberChapterPages(text, width, height,
                    TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontFamily = FontFamily.SansSerif), 0, false)
                SideEffect {
                    measuredWidth = width
                    if (pages.isNotEmpty()) ready = true else if (ready) emptyAfterReady++
                    latestPages = pages
                }
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    AppSwitch(checked, { checked = it }, modifier = Modifier.testTag("fold-switch"))
                    Text(pages.getOrNull(pageIndexAtOffset(pages, text.length / 2)) ?: "准备正文")
                }
            }
        } }
        compose.waitUntil(15000) { ready }
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        assertFalse(checked)
        fun shell(command: String) {
            instrumentation.uiAutomation.executeShellCommand(command).use { parcel ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel).use { it.readBytes() }
            }
        }
        try {
            for (state in listOf(1, 3, 1, 3)) {
                val before = measuredWidth
                shell("cmd device_state state $state")
                // Headless API 35 commits hinge posture but does not crop its one display.
                // Apply the profile's actual folded region explicitly, alongside the posture.
                shell("wm size ${if (state == 1) "884x2208" else "1768x2208"}")
                shell("input keyevent KEYCODE_WAKEUP")
                shell("wm dismiss-keyguard")
                compose.waitUntil(15000) { measuredWidth != before }
                compose.waitForIdle()
                widths.add(measuredWidth)
                assertFalse(checked)
                assertTrue(latestPages.isNotEmpty())
                screenshot("fold-state-$state-${widths.size}")
            }
            for (size in listOf("1080x1600", "1600x1080", "1768x2208")) {
                val before = measuredWidth
                shell("wm size $size")
                compose.waitUntil(15000) { measuredWidth != before }
                compose.waitForIdle()
                assertFalse(checked)
                assertTrue(latestPages.isNotEmpty())
                widths.add(measuredWidth)
            }
            assertEquals("No empty publication after the first ready layout", 0, emptyAfterReady)
            assertTrue(widths.toSet().size >= 3)
            File(dir, "fold-resize.json").writeText(JSONObject().put("widths", JSONArray(widths))
                .put("emptyAfterReady", emptyAfterReady).put("inputRetained", !checked).toString(2))
        } finally {
            shell("wm size reset")
            shell("cmd device_state state reset")
        }
    }
}
