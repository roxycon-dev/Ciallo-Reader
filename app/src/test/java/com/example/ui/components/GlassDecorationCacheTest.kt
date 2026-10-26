package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.kashif_e.backdrop.backdrops.emptyBackdrop
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1200dp-mdpi")
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
class GlassDecorationCacheTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(): Bitmap {
        val file = File.createTempFile("decoration-parity-", ".png")
        try {
            compose.onNodeWithTag("surface").captureRoboImage(file.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            return requireNotNull(BitmapFactory.decodeFile(file.path))
        } finally { file.delete() }
    }

    private fun samePixels(a: Bitmap, b: Bitmap) {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val before = IntArray(a.width * a.height)
        val after = IntArray(before.size)
        a.getPixels(before, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(after, 0, b.width, 0, 0, b.width, b.height)
        assertEquals("changed pixels", 0, before.indices.count { before[it] != after[it] })
        a.recycle(); b.recycle()
    }

    @Test fun cachedDecorationsKeepPixelsThroughSizeAndPaletteChanges() {
        var cached by mutableStateOf(false)
        var width by mutableStateOf(280.dp)
        var height by mutableStateOf(120.dp)
        var ink by mutableStateOf(Color(0xff456789))
        compose.setContent { MaterialTheme {
            val shape = RoundedCornerShape(22.dp)
            val colors = listOf(ink, Color.White, ink, Color.Cyan, ink)
            val decorations = if (cached) Modifier.radialGlassScrim()
                .iridescentBorder(shape, colors).crystalInnerBevel(shape)
            else Modifier.legacyScrim().legacyBorder(shape, colors).legacyBevel(shape)
            Box(Modifier.size(width, height).testTag("surface").background(Color(0xffc6baac)).then(decorations))
        } }
        // Reuse the same node across resize and theme changes to exercise invalidation.
        repeat(8) { frame ->
            compose.runOnIdle {
                cached = false
                width = if (frame % 2 == 0) 280.dp else 410.dp
                height = if (frame % 3 == 0) 90.dp else 180.dp
                ink = if (frame < 4) Color(0xff456789) else Color(0xff8c3f6e)
            }
            val before = capture()
            compose.runOnIdle { cached = true }
            samePixels(before, capture())
        }
    }

    @Test @Config(sdk = [30]) fun olderAndroidFrostedFallbackKeepsPixelsAfterResize() {
        var cached by mutableStateOf(false)
        var width by mutableStateOf(280.dp)
        var height by mutableStateOf(120.dp)
        compose.setContent { MaterialTheme {
            val shape = RoundedCornerShape(18.dp)
            val surface = Color(0x448899aa)
            val decoration = if (cached) Modifier.liquidGlass(emptyBackdrop(), shape, surface)
                else Modifier.legacyFrosted(shape, surface)
            Box(Modifier.size(width, height).testTag("surface").background(Color(0xff6b7d91)).then(decoration))
        } }
        repeat(4) { frame ->
            compose.runOnIdle { cached = false; width = (220 + frame * 50).dp; height = (100 + frame * 30).dp }
            val before = capture()
            compose.runOnIdle { cached = true }
            samePixels(before, capture())
        }
    }
}

// Frozen drawing operations from 10d1f206, before moving brushes/geometry into draw caches.
private fun Modifier.legacyScrim() = drawBehind {
    drawRect(Brush.radialGradient(listOf(Color.Black.copy(alpha = .25f), Color.Black.copy(alpha = PullOutConfig.SCRIM)),
        center = Offset(size.width * .5f, size.height * .78f), radius = size.maxDimension * .95f))
}

private fun Modifier.legacyBorder(shape: Shape, colors: List<Color>) = drawBehind {
    val brush = Brush.sweepGradient(colors.map { it.copy(alpha = .22f) }, center = Offset(size.width / 2f, size.height / 2f))
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rounded -> drawRoundRect(brush, cornerRadius = outline.roundRect.topLeftCornerRadius, style = Stroke(2.dp.toPx()))
        is Outline.Rectangle -> drawRect(brush, style = Stroke(2.dp.toPx()))
        else -> Unit
    }
}

private fun Modifier.legacyBevel(shape: Shape) = drawBehind {
    val brush = Brush.linearGradient(listOf(Color.White.copy(alpha = .35f), Color.White.copy(alpha = .12f),
        Color.Transparent, Color.Black.copy(alpha = .08f)), start = Offset.Zero, end = Offset(size.width, size.height))
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rounded -> drawRoundRect(brush, cornerRadius = outline.roundRect.topLeftCornerRadius, style = Stroke(1.dp.toPx()))
        is Outline.Rectangle -> drawRect(brush, style = Stroke(1.dp.toPx()))
        else -> Unit
    }
}

private fun Modifier.legacyFrosted(shape: Shape, surface: Color) = background(surface.copy(alpha = (surface.alpha + .20f).coerceAtMost(1f)), shape)
    .drawWithContent {
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .12f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = .05f))))
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .14f), Color.Transparent), startY = 0f, endY = size.height * .22f),
            topLeft = Offset.Zero, size = Size(size.width, size.height * .22f))
        drawContent()
    }.filmGrain(.05f)
