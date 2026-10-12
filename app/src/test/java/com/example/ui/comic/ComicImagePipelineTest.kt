package com.example.ui.comic

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 图像处理管线：裁边检测 / 拆分 / 旋转 / 色调 / 黑白 / 锐化 / 增强 / 主色提取 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicImagePipelineTest {

    private fun solidBitmap(w: Int, h: Int, color: Int, content: (Int, Int) -> Int? = { _, _ -> null }): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                bmp.setPixel(x, y, content(x, y) ?: color)
            }
        }
        return bmp
    }

    @Test
    fun `white border auto crop detects content box`() {
        // 白底，中间 20%~80% 是黑色内容
        val bmp = solidBitmap(200, 300, Color.WHITE) { x, y ->
            if (x in 40..160 && y in 60..240) Color.BLACK else null
        }
        val rect = ComicImagePipeline.detectContentRect(bmp, ComicCropMode.WHITE)
        assertNotNull(rect)
        assertTrue("left should cut border", rect!![0] < 45)
        assertTrue("right keeps content", rect[2] > 155)
        assertTrue("top cuts border", rect[1] < 65)
        assertTrue("bottom keeps content", rect[3] > 235)
    }

    @Test
    fun `black border auto crop detects content box`() {
        val bmp = solidBitmap(200, 300, Color.BLACK) { x, y ->
            if (x in 40..160 && y in 60..240) Color.WHITE else null
        }
        val rect = ComicImagePipeline.detectContentRect(bmp, ComicCropMode.BLACK)
        assertNotNull(rect)
        assertTrue(rect!![0] < 45)
        assertTrue(rect[2] > 155)
    }

    @Test
    fun `all-white page returns null (no aggressive crop)`() {
        val bmp = solidBitmap(100, 100, Color.WHITE)
        assertNull(ComicImagePipeline.detectContentRect(bmp, ComicCropMode.WHITE))
    }

    @Test
    fun `tiny content rejects crop to protect page`() {
        // 只有 5% 高的内容行 → 拒绝裁剪
        val bmp = solidBitmap(200, 300, Color.WHITE) { _, y -> if (y in 148..152) Color.BLACK else null }
        assertNull(ComicImagePipeline.detectContentRect(bmp, ComicCropMode.WHITE))
    }

    @Test
    fun `manual crop rect applied in process`() {
        val bmp = solidBitmap(100, 100, Color.RED)
        val out = ComicImagePipeline.process(
            bmp,
            ComicImagePipeline.Geometry(cropMode = ComicCropMode.OFF, manualCrop = listOf(0.25f, 0.25f, 0.75f, 0.75f)),
            ComicImagePipeline.Toning()
        )
        assertEquals(50, out.width)
        assertEquals(50, out.height)
    }

    @Test
    fun `split left and right halves`() {
        val bmp = solidBitmap(200, 100, Color.GREEN)
        val left = ComicImagePipeline.process(
            bmp, ComicImagePipeline.Geometry(half = ComicSplitHalf.LEFT, splitPosition = 0.5f), ComicImagePipeline.Toning()
        )
        val right = ComicImagePipeline.process(
            bmp, ComicImagePipeline.Geometry(half = ComicSplitHalf.RIGHT, splitPosition = 0.5f), ComicImagePipeline.Toning()
        )
        assertEquals(100, left.width)
        assertEquals(100, right.width)
    }

    @Test
    fun `rotation 90 swaps dimensions`() {
        val bmp = solidBitmap(120, 60, Color.BLUE)
        val out = ComicImagePipeline.process(bmp, ComicImagePipeline.Geometry(rotationDeg = 90), ComicImagePipeline.Toning())
        assertEquals(60, out.width)
        assertEquals(120, out.height)
    }

    @Test
    fun `brightness LUT raises mean luminance`() {
        val gray = solidBitmap(64, 64, Color.rgb(100, 100, 100))
        val out = ComicImagePipeline.applyLut(
            gray,
            ComicImagePipeline.buildToneLut(ComicImagePipeline.Toning(brightness = 50))
        )
        val p = out.getPixel(10, 10)
        assertTrue("expected brighter, got ${(p shr 16) and 0xFF}", ((p shr 16) and 0xFF) > 110)
    }

    @Test
    fun `bw filter produces grayscale`() {
        val colorful = solidBitmap(32, 32, Color.rgb(200, 40, 10))
        val matrix = ComicImagePipeline.buildFilterMatrix(ComicImagePipeline.Toning(bw = true))
        val out = ComicImagePipeline.applyColorMatrix(colorful, matrix)
        val p = out.getPixel(8, 8)
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        assertTrue(abs(r - g) <= 2 && abs(g - b) <= 2)
    }

    @Test
    fun `gamma darkens midtones when above 1`() {
        val lut = ComicImagePipeline.buildToneLut(ComicImagePipeline.Toning(gamma = 2f))
        // gamma 2.0：128^0.5 ≈ 0.707 → 180
        assertTrue(lut[128] in 170..190)
    }

    @Test
    fun `shadow lift brightens dark values only`() {
        val lut = ComicImagePipeline.buildToneLut(ComicImagePipeline.Toning(shadow = 80))
        assertTrue(lut[16] > 22)
        // 高光几乎不动
        assertTrue(lut[250] in 249..255)
    }

    @Test
    fun `unsharp mask keeps size and changes pixels`() {
        // 中心亮块的边缘应被锐化改变
        val bmp = solidBitmap(64, 64, Color.GRAY) { x, y -> if (x in 24..40 && y in 24..40) Color.WHITE else null }
        val out = ComicImagePipeline.unsharpMask(bmp, 0.8f)
        assertEquals(64, out.width)
        // 白块边缘外侧的灰像素被锐化提亮（块内像素本就 255 已饱和不变）
        assertTrue(out.getPixel(23, 32) != bmp.getPixel(23, 32))
    }

    @Test
    fun `cas sharpen preserves dimensions`() {
        val bmp = solidBitmap(48, 48, Color.LTGRAY)
        val out = ComicImagePipeline.casSharpen(bmp, 0.5f)
        assertEquals(48, out.width)
        assertEquals(48, out.height)
    }

    @Test
    fun `anime4k keeps dimensions and runs`() {
        val bmp = solidBitmap(40, 40, Color.GRAY) { x, _ -> if (x == 20) Color.BLACK else null }
        val out = ComicImagePipeline.anime4kLines(bmp, 0.7f)
        assertEquals(40, out.width)
        assertEquals(40, out.height)
    }

    @Test
    fun `legacy waifu2x uses unified super resolution engine`() {
        val bmp = solidBitmap(64, 96, Color.DKGRAY)
        val out = ComicImagePipeline.process(bmp, ComicImagePipeline.Geometry(), ComicImagePipeline.Toning(enhanceMode = ComicEnhanceMode.WAIFU2X, enhanceStrength = 50))
        assertEquals(128, out.width)
        assertEquals(192, out.height)
    }

    @Test
    fun `super resolution doubles small image`() {
        val bmp = solidBitmap(60, 90, Color.GRAY)
        val out = ComicImagePipeline.superResolution(bmp, 0.5f)
        assertEquals(120, out.width)
        assertEquals(180, out.height)
    }

    @Test
    fun `dominant background returns dark muted color`() {
        // 蓝色为主的页面
        val bmp = solidBitmap(64, 64, Color.rgb(40, 80, 220)) { _, _ -> null }
        val color = ComicImagePipeline.dominantBackground(bmp)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val lum = (r * 299 + g * 587 + b * 114) / 1000
        assertTrue("background must stay dark, lum=$lum", lum in 8..80)
        assertTrue("blue should lead", b >= r)
    }

    @Test
    fun `dominant background preserves perceptible tone difference`() {
        // 第 25 条回归钉子：色调差异明显的相邻两页，沉浸式背景必须可辨识区分——
        // 旧参数（30% 色度 + 亮度 34）把任何输入压成几乎同色暗灰（色距 ~4/255），
        // 400ms 渐变在录屏逐帧下不可见（第三轮复审抓到，已调至 65% 色度 + 亮度 64）
        fun bgOf(c: Int): Int = ComicImagePipeline.dominantBackground(
            solidBitmap(32, 32, c) { _, _ -> null },
        )
        fun dist(a: Int, b: Int): Int {
            val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
            val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
            val db = (a and 0xFF) - (b and 0xFF)
            return abs(dr) + abs(dg) + abs(db)
        }
        val warm = bgOf(Color.rgb(235, 208, 176))   // 暖米纸底
        val cool = bgOf(Color.rgb(176, 205, 235))   // 冷蓝纸底
        assertTrue("tone difference crushed: warm=$warm cool=$cool", dist(warm, cool) >= 12)
        // 同时仍需保持暗于页面主体（不抢漫画）
        val lumOf = { c: Int -> (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000 }
        assertTrue("too bright: ${lumOf(warm)}", lumOf(warm) in 8..90)
        assertTrue("too bright: ${lumOf(cool)}", lumOf(cool) in 8..90)
    }

    private fun abs(v: Int) = if (v < 0) -v else v
}
