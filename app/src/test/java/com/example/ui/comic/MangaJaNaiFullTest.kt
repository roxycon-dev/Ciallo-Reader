package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MangaJaNaiFullTest {
    @Test fun fullModePreservesLightSettingAndKeepsCachesSeparate() {
        val light = ComicReaderConfig(enhanceMode = ComicEnhanceMode.MANGAJANAI)
        val full = ComicReaderConfig(enhanceMode = ComicEnhanceMode.MANGAJANAI_FULL)
        assertEquals(ComicEnhanceMode.MANGAJANAI, ComicReaderConfig.fromJson(light.toJson()).enhanceMode)
        assertEquals(ComicEnhanceMode.MANGAJANAI_FULL, ComicReaderConfig.fromJson(full.toJson()).enhanceMode)
        assertNotEquals(light.imagePipelineFingerprint(), full.imagePipelineFingerprint())
        assertTrue(ComicEnhanceMode.visibleModes.containsAll(listOf(light.enhanceMode, full.enhanceMode)))
        assertTrue(ComicProcessedDiskCache.eligible(full.enhanceMode))
    }
    @Test fun fullAccuracyGateRejectsQuantizationSizedAndNonfiniteErrors() {
        assertTrue(MangaJaNaiFullRuntime.acceptableError(0.000013f, 0.000001))
        assertFalse(MangaJaNaiFullRuntime.acceptableError(0.002f, 0.00001))
        assertFalse(MangaJaNaiFullRuntime.acceptableError(0.001f, 0.0002))
        assertFalse(MangaJaNaiFullRuntime.acceptableError(Float.NaN, 0.0))
        assertFalse(MangaJaNaiFullRuntime.acceptableError(0f, Double.NaN))
    }
    @Test fun fullAndLargerGpuTilesUseExactlyClampedOriginalPixels() {
        val source = Bitmap.createBitmap(83, 97, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) for (x in 0 until source.width)
            source.setPixel(x, y, -0x1000000 or (x shl 16) or (y shl 8) or ((x + y) and 255))
        try {
            for (tile in listOf(48, 128, 240)) for ((x, y) in listOf(0 to 0, 48 to 48)) {
                val side = tile + 32; val pixels = IntArray(side * side)
                readMangaJaNaiTile(source, x, y, pixels, tile, 16)
                for (row in 0 until side) for (col in 0 until side)
                    assertEquals(source.getPixel((x + col - 16).coerceIn(0, 82), (y + row - 16).coerceIn(0, 96)), pixels[row * side + col])
            }
        } finally { source.recycle() }
    }
    @Test fun corruptAndTraversalModelPacksNeverReplaceInstalledWeights() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.cacheDir, "full136-pack").apply { mkdirs() }
        val installed = File(directory, "full.onnx").apply { writeText("keep existing model") }
        for (entryName in listOf("../escape.bin", "full.onnx", "full.param")) {
            val archive = File(app.cacheDir, "full136-bad.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(entryName)); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
            }
            try { MangaJaNaiFull.unpack(archive, directory); fail("Invalid pack accepted") }
            catch (_: java.io.IOException) { }
            assertEquals("keep existing model", installed.readText())
            assertFalse(File(app.cacheDir, "escape.bin").exists())
            assertTrue(directory.listFiles()!!.none { it.name.endsWith(".part") })
        }
    }
}
