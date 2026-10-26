package com.example.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.ui.components.*
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import me.trishiraj.shadowglow.baselineRound2ShadowGlow
import me.trishiraj.shadowglow.shadowGlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1200dp-mdpi")
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
class FoldMotionGlowParityTest {
    @get:Rule val compose = createComposeRule()
    private val out get() = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "gradlew.bat").exists() }.resolve("artifacts/motion-round2/glow-parity").apply { mkdirs() }

    private fun checkFrame(tag: String, reference: Boolean, label: String) {
        val file = File(out, "$label-$reference.png")
        compose.onNodeWithTag(tag).captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        if (reference) return
        val old = requireNotNull(BitmapFactory.decodeFile(File(out, "$label-true.png").absolutePath))
        val next = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try {
            assertEquals(old.width, next.width); assertEquals(old.height, next.height)
            val a = IntArray(old.width * old.height); val b = IntArray(a.size)
            old.getPixels(a, 0, old.width, 0, 0, old.width, old.height)
            next.getPixels(b, 0, next.width, 0, 0, next.width, next.height)
            assertEquals("$label changed pixels", 0, a.indices.count { a[it] != b[it] })
        } finally { old.recycle(); next.recycle() }
    }

    @Test fun breathingAndWrappingTrailKeepExactPixelsDuringWindowChanges() {
        var reference by mutableStateOf(true)
        var mode by mutableIntStateOf(0)
        var width by mutableIntStateOf(280)
        var generation by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            Box(Modifier.size(500.dp, 290.dp).background(Color(0xffe8eeeb)).testTag("surface"), Alignment.Center) {
                key(reference, mode, generation) {
                    val color = Color(0xff417c67)
                    val glow = if (mode == 0) {
                        if (reference) Modifier.baselineRound2ShadowGlow(color, 50.dp, 48.dp, offsetY = 8.dp, spread = 12.dp,
                            enableBreathingEffect = true, breathingEffectIntensity = 14.dp, breathingDurationMillis = 1200,
                            enableGlowTrail = true, glowTrailWidth = 14.dp, glowTrailBlurRadius = 28.dp,
                            glowTrailLengthDegrees = 90f, glowTrailAlpha = .9f)
                        else Modifier.shadowGlow(color, 50.dp, 48.dp, offsetY = 8.dp, spread = 12.dp,
                            enableBreathingEffect = true, breathingEffectIntensity = 14.dp, breathingDurationMillis = 1200,
                            enableGlowTrail = true, glowTrailWidth = 14.dp, glowTrailBlurRadius = 28.dp,
                            glowTrailLengthDegrees = 90f, glowTrailAlpha = .9f)
                    } else {
                        if (reference) Modifier.baselineRound2ShadowGlow(listOf(color, Color.Blue), borderRadius = 22.dp,
                            blurRadius = 12.dp, spread = 3.dp, alpha = .63f, enableBreathingEffect = true,
                            breathingEffectIntensity = 7.dp, enableGlowTrail = true, glowTrailClockwise = false)
                        else Modifier.shadowGlow(listOf(color, Color.Blue), borderRadius = 22.dp,
                            blurRadius = 12.dp, spread = 3.dp, alpha = .63f, enableBreathingEffect = true,
                            breathingEffectIntensity = 7.dp, enableGlowTrail = true, glowTrailClockwise = false)
                    }
                    Box(Modifier.size(width.dp, 68.dp).then(glow).background(Color(0xffb5cabe)))
                }
            }
        } }
        for (variant in 0..1) for (old in listOf(true, false)) {
            compose.runOnIdle { mode = variant; reference = old; width = 280; generation++ }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
            for ((index, dt) in listOf(0L, 16L, 96L, 416L, 688L, 512L, 880L, 640L).withIndex()) {
                if (index == 3) compose.runOnIdle { width = 174 }
                if (index == 6) compose.runOnIdle { width = 336 }
                compose.mainClock.advanceTimeBy(dt); compose.waitForIdle()
                checkFrame("surface", old, "glow-$variant-$index")
            }
        }
    }

    @Test fun realMaxPrimaryButtonKeepsPressReleaseAndLoopPixels() {
        var reference by mutableStateOf(true)
        var generation by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalRenderQuality provides RenderQuality.MAX) { MaterialTheme {
            Box(Modifier.size(440.dp, 210.dp).background(Color(0xffedf0ee)).testTag("button-surface"), Alignment.Center) {
                key(reference, generation) {
                    if (reference) Round2BaselineAppActionButton("MAX 实际主按钮", {}, Modifier.width(280.dp))
                    else AppActionButton("MAX 实际主按钮", {}, Modifier.width(280.dp))
                }
            }
        } } }
        for (old in listOf(true, false)) {
            compose.runOnIdle { reference = old; generation++ }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
            for ((index, dt) in listOf(0L, 16L, 80L, 160L, 16L, 96L, 800L, 1600L).withIndex()) {
                if (index == 1) compose.onNodeWithText("MAX 实际主按钮").performTouchInput { down(center) }
                if (index == 4) compose.onNodeWithText("MAX 实际主按钮").performTouchInput { up() }
                compose.mainClock.advanceTimeBy(dt); compose.waitForIdle()
                checkFrame("button-surface", old, "max-button-$index")
            }
        }
    }
}
