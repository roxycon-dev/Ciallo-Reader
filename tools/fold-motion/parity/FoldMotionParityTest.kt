package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.*
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.swapnil.squishyswitch.presentation.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1200dp-mdpi")
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
class FoldMotionParityTest {
    @get:Rule val compose = createComposeRule()
    private val out get() = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "gradlew.bat").exists() }.resolve("artifacts/fold-motion/parity").apply { mkdirs() }

    private fun compare(a: Bitmap, b: Bitmap, label: String) {
        assertEquals(label, a.width, b.width); assertEquals(label, a.height, b.height)
        val x = IntArray(a.width * a.height); val y = IntArray(x.size)
        a.getPixels(x, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(y, 0, b.width, 0, 0, b.width, b.height)
        val changed = x.indices.count { x[it] != y[it] }
        if (changed != 0) {
            File(out, "$label-old.png").outputStream().use { a.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(out, "$label-new.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertEquals("$label changed pixels", 0, changed)
    }

    @Test fun paperSpringKeepsExactTrajectoriesAcrossReversalsAndFrameRates() {
        val random = kotlin.random.Random(42)
        val old = BaselinePaperSpring(); val next = PaperSpring()
        repeat(10000) { frame ->
            val opening = frame % 71 < 37
            val lightweight = frame % 127 < 40
            val target = if (opening) .7f else 0f
            val dt = random.nextFloat() * .05f
            assertEquals(old.advance(target, opening, dt, lightweight), next.advance(target, opening, dt, lightweight))
            assertEquals(old.position, next.position, 0f)
            assertEquals(old.velocity, next.velocity, 0f)
        }
    }

    @Test fun sliderCpuTenAlternatingPairs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val old = BaselineLiquidSliderView(context)
        val next = LiquidSliderView(context)
        old.configure(0f..1f, 7, true, false, .05f, 0xff417c67.toInt(), false, false, false)
        next.configure(0f..1f, 7, true, false, .05f, 0xff417c67.toInt(), false, false, false)
        old.layout(0, 0, 411, 110); next.layout(0, 0, 411, 110)
        old.setValues(.2f, .8f); next.setValues(.2f, .8f)
        val bitmap = Bitmap.createBitmap(411, 110, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val cpuTime = Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime")
        fun now() = cpuTime.invoke(bean) as Long
        fun sample(baseline: Boolean): Long {
            val start = now()
            repeat(1600) { frame ->
                val value = if (frame % 40 < 20) .15f else .6f
                if (baseline) { old.setValues(value, .9f); old.advancePhysics(1f / 120); old.draw(canvas) }
                else { next.setValues(value, .9f); next.advancePhysics(1f / 120); next.draw(canvas) }
            }
            return now() - start
        }
        repeat(2) { sample(true); sample(false) }
        val before = mutableListOf<Long>(); val after = mutableListOf<Long>()
        repeat(10) { pair ->
            if (pair % 2 == 0) { before.add(sample(true)); after.add(sample(false)) }
            else { after.add(sample(false)); before.add(sample(true)) }
        }
        File(out, "slider-cpu.json").writeText("{\"framesPerSample\":1600,\"baselineNs\":${before},\"optimizedNs\":${after}}")
        old.stop(); next.stop(); bitmap.recycle()
    }

    @Test fun sliderEveryPhysicsFrameMatchesFrozenRenderer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var frames = 0
        for (ranged in listOf(false, true)) for (dark in listOf(false, true)) for (reverse in listOf(false, true)) {
            val old = BaselineLiquidSliderView(context)
            val next = LiquidSliderView(context)
            old.configure(0f..1f, 7, true, reverse, .05f, 0xff417c67.toInt(), dark, false, false)
            next.configure(0f..1f, 7, true, reverse, .05f, 0xff417c67.toInt(), dark, false, false)
            old.layout(0, 0, 411, 110); next.layout(0, 0, 411, 110)
            old.setValues(.2f, if (ranged) .8f else null); next.setValues(.2f, if (ranged) .8f else null)
            val a = Bitmap.createBitmap(411, 110, Bitmap.Config.ARGB_8888)
            val b = Bitmap.createBitmap(411, 110, Bitmap.Config.ARGB_8888)
            val ca = Canvas(a); val cb = Canvas(b)
            repeat(72) { frame ->
                val action = when (frame) { 10 -> MotionEvent.ACTION_DOWN; 18 -> MotionEvent.ACTION_MOVE; 22 -> MotionEvent.ACTION_UP; else -> null }
                if (action != null) {
                    for (view in listOf(old, next)) {
                        val event = MotionEvent.obtain(1000L, 1000L + frame * 16, action,
                            if (frame == 10) 140f else 260f, 68f, 0)
                        view.onTouchEvent(event); event.recycle()
                    }
                }
                if (frame == 2 || frame == 28) {
                    val v = if (frame == 2) .6f else .15f
                    old.setValues(v, if (ranged) .9f else null); next.setValues(v, if (ranged) .9f else null)
                }
                val dt = if (frame % 3 == 0) 1f / 60 else 1f / 120
                assertEquals(old.advancePhysics(dt), next.advancePhysics(dt))
                a.eraseColor(android.graphics.Color.TRANSPARENT); b.eraseColor(android.graphics.Color.TRANSPARENT)
                old.draw(ca); next.draw(cb)
                compare(a, b, "slider-$ranged-$dark-$reverse-$frame")
                frames++
            }
            old.stop(); next.stop(); a.recycle(); b.recycle()
        }
        File(out, "slider-frames.txt").writeText("$frames exact frames")
    }

    @Test fun switchColorSquishAndReversalsMatchFrozenFrames() {
        var checked by mutableStateOf(false)
        var baseline by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            Box(Modifier.size(100.dp, 80.dp).background(Color.White).testTag("surface")) {
                key(baseline) {
                    if (baseline) BaselineSquishyToggleSwitch(Color(0xff417c67), checked, { checked = it })
                    else SquishyToggleSwitch(Color(0xff417c67), checked, { checked = it })
                }
            }
        } }
        val original = mutableListOf<Bitmap>()
        for (useBaseline in listOf(true, false)) {
            compose.runOnIdle { checked = false; baseline = useBaseline }
            compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
            var frame = 0
            repeat(3) {
                compose.runOnIdle { checked = !checked }
                for (dt in listOf(16L, 32L, 64L, 80L, 160L)) {
                    compose.mainClock.advanceTimeBy(dt); compose.waitForIdle()
                    val file = File(out, "switch-$useBaseline-$frame.png")
                    compose.onNodeWithTag("surface").captureRoboImage(file.absolutePath,
                        roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                    val shot = requireNotNull(android.graphics.BitmapFactory.decodeFile(file.absolutePath))
                    if (useBaseline) original.add(shot) else {
                        compare(original[frame], shot, "switch-$frame"); shot.recycle()
                    }
                    frame++
                }
            }
        }
        original.forEach { it.recycle() }
    }

    @Test fun aButtonBaselineFrames() = buttonFrames(true)
    @Test fun bButtonOptimizedFrames() = buttonFrames(false)

    private fun buttonFrames(baseline: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent { MyApplicationTheme(darkTheme = false) {
            CompositionLocalProvider(LocalRenderQuality provides RenderQuality.HIGH) {
                Column {
                    AppButtonVariant.entries.forEach { variant ->
                        Box(Modifier.size(280.dp, 100.dp).background(Color.White).testTag("surface-${variant.name}")) {
                            if (baseline) BaselineAppActionButton("同一按钮 ${variant.name}", {}, variant = variant)
                            else AppActionButton("同一按钮 ${variant.name}", {}, variant = variant)
                        }
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        for (variant in AppButtonVariant.entries) {
            compose.onNodeWithText("同一按钮 ${variant.name}").performTouchInput { down(center) }
            for ((index, dt) in listOf(16L, 48L, 100L, 300L, 16L, 48L, 100L, 300L).withIndex()) {
                if (index == 4) compose.onNodeWithText("同一按钮 ${variant.name}").performTouchInput { up() }
                compose.mainClock.advanceTimeBy(dt); compose.waitForIdle()
                val file = File(out, "button-$baseline-${variant.name}-$index.png")
                compose.onNodeWithTag("surface-${variant.name}").captureRoboImage(file.absolutePath, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                val shot = requireNotNull(android.graphics.BitmapFactory.decodeFile(file.absolutePath))
                if (!baseline) {
                    val original = requireNotNull(android.graphics.BitmapFactory.decodeFile(File(out, "button-true-${variant.name}-$index.png").absolutePath))
                    compare(original, shot, "button-${variant.name}-$index"); original.recycle()
                }
                shot.recycle()
            }
            compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        }
    }
}
