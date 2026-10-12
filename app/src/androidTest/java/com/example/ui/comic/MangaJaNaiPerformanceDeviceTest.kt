package com.example.ui.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses the bundled mobile model. Reports whole-page cold and warm time,
 * rather than presenting a small patch or a final-image cache hit as a 5-second full page.
 */
@RunWith(AndroidJUnit4::class)
class MangaJaNaiPerformanceDeviceTest {
    @Test fun benchmarkUncachedFullPageWithoutChangingTheModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val width = args.getString("mangaWidth", "1200")!!.toInt().coerceIn(48, 2800)
        val height = args.getString("mangaHeight", "1800")!!.toInt().coerceIn(48, 2800)
        val pageName = args.getString("mangaPage")
        val input = if (pageName != null) {
            val page = File(context.filesDir, pageName).canonicalFile
            require(page.toPath().startsWith(context.filesDir.canonicalFile.toPath()))
            checkNotNull(BitmapFactory.decodeFile(page.absolutePath)) { "Cannot read mangaPage" }
        } else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val ink = Paint().apply { color = Color.BLACK; strokeWidth = 2f }
            for (y in 20 until height step 21) canvas.drawLine(10f, y.toFloat(), width - 10f, y - 15f, ink)
        }
        MangaJaNai.configure(context)
        MangaJaNai.release()
        try {
            repeat(2) { run ->
                val started = System.nanoTime()
                val output = MangaJaNai.enhance(input, 1f)
                val ms = (System.nanoTime() - started) / 1_000_000
                val expected = MangaJaNai.outputSize(input.width, input.height)
                assertEquals(expected.first, output.width)
                assertEquals(expected.second, output.height)
                assertTrue("Whole-page AI exceeded five seconds: $ms ms", ms <= 5000)
                output.recycle()
                Log.i("MangaJaNaiBenchmark", "run=$run source=${pageName ?: "synthetic"} input=${input.width}x${input.height} totalMs=$ms within5Seconds=${ms <= 5000}")
            }
        } finally { input.recycle(); MangaJaNai.release() }
    }
}
