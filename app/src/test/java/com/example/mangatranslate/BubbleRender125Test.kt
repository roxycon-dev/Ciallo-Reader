package com.example.mangatranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleRender125Test {
    @Test fun oversizedSegmentationPreservesArtworkAndBalloonOutsideTheText() {
        val base = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(base)
        canvas.drawColor(Color.rgb(212, 166, 178))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(RectF(60f, 50f, 260f, 130f), 20f, 20f, paint)
        paint.color = Color.BLACK; paint.textSize = 24f
        canvas.drawText("Hello", 84f, 94f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        canvas.drawRect(20f, 20f, 300f, 180f, paint)
        val mask = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val region = BubblePipeline.Region(RectF(0f, 0f, 320f, 200f), mask,
            listOf(RectF(80f, 65f, 170f, 97f)), false)
        val result = BubblePipeline.bake(base, listOf(region to "你好，今天一起出去散步吧"), 1.4f)
        var changed = 0
        for (y in 0 until 200) for (x in 0 until 320) {
            if (x !in 78..172 || y !in 63..99)
                assertEquals("Artwork changed at $x,$y", base.getPixel(x, y), result.getPixel(x, y))
            else if (base.getPixel(x, y) != result.getPixel(x, y)) changed++
        }
        assertTrue("Text should be replaced", changed > 100)
        base.recycle(); result.recycle()
    }

    @Test fun darkVerticalTextStaysClippedAndKeepsItsBackground() {
        val base = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
        Canvas(base).drawColor(Color.rgb(28, 30, 36))
        val region = BubblePipeline.Region(RectF(50f, 25f, 105f, 210f), null,
            listOf(RectF(62f, 40f, 91f, 195f)), true)
        val result = BubblePipeline.bake(base, listOf(region to "明天我们再出发吧"), 1.4f)
        var light = 0
        for (y in 0 until 240) for (x in 0 until 160) {
            if (x !in 58..95 || y !in 25..210) assertEquals(base.getPixel(x, y), result.getPixel(x, y))
            else if (Color.red(result.getPixel(x, y)) > 160) light++
        }
        assertTrue("Dark backgrounds need contrasting readable text", light > 50)
        base.recycle(); result.recycle()
    }
}
