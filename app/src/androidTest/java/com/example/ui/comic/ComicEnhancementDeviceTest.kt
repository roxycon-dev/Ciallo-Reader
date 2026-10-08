package com.example.ui.comic

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.source.SourceResult
import com.example.source.js.JsSourceRepo
import com.example.ui.comicImageLoader
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ComicEnhancementDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun nativeGraphsMatchKotlinConvolutionAtImageBoundaries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ComicNeuralBackend.configure(context)
        val random = kotlin.random.Random(42)
        val width = 67; val height = 89
        val plane = FloatArray(width * height * 4) { if (it % 4 == 3) 1f else random.nextFloat() }
        for (model in listOf("restore", "upscale")) {
            val expected = Anime4KCnn.referenceConvolution(model, plane, width, height)
            val actual = ComicNeuralBackend.run(model, plane, width, height)
            assertNotNull("Bundled native graph must run", actual)
            assertTrue(ComicNeuralBackend.usedNative)
            val maximum = expected.indices.maxOf { kotlin.math.abs(expected[it] - actual!![it]) }
            assertTrue("$model error $maximum", maximum < 2e-5f)
        }
    }

    private fun zoom(mode: ComicEnhanceMode, double: Boolean, large: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, ComicVisualProbeActivity::class.java)
            .putExtra("mode", if (double) "DOUBLE" else "SINGLE")
            .putExtra("direction", "RTL").putExtra("anim", "CURL").putExtra("fit", "FIT_PAGE")
            .putExtra("enhance", mode.name).putExtra("pw", if (large) 1280 else 480)
            .putExtra("ph", if (large) 1890 else 660)
        val scenario = ActivityScenario.launch<ComicVisualProbeActivity>(intent)
        fun find(v: View): ComicCurlView? = when (v) {
            is ComicCurlView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        var curl: ComicCurlView? = null
        try {
            rule.waitUntil(30_000) {
                scenario.onActivity { curl = find(it.window.decorView) }
                curl?.pageGeometryValid == true
            }
            // Let neighbor loading fill the controller cache beyond its old 64 MiB budget.
            SystemClock.sleep(if (large) 12_000 else 1500)
            scenario.onActivity { curl!!.onZoomStart?.invoke("double", curl!!.width * .5f, curl!!.height * .5f) }
            rule.waitUntil(45_000) { rule.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("图片加载失败").assertDoesNotExist()
            assertTrue(rule.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty())
        } finally { scenario.close() }
    }

    @Test fun largeSuperResolutionPageZoomsAfterNeighborPreload() = zoom(ComicEnhanceMode.SUPER_RES, false, true)
    @Test fun largeDoubleSuperResolutionPagesZoomAfterNeighborPreload() = zoom(ComicEnhanceMode.SUPER_RES, true, true)
    @Test fun everyEngineSupportsNativeCurlZoom() {
        for (mode in ComicEnhanceMode.entries) zoom(mode, false, false)
    }

    /** Explicit live diagnostic: only dimensions/timings are exported, never gallery content. */
    @Test fun reportedJm1444347LoadsAndEnhancesFirstMiddleLastPages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val report = JSONObject().put("source", "jm").put("query", "1444347")
        val file = File(context.filesDir, "enhancement-jm-1444347.json")
        fun <T> value(result: SourceResult<T>): T = when (result) {
            is SourceResult.Success -> result.data
            is SourceResult.Error -> throw result.exception
        }
        val loader = ComicPageLoader(context, comicImageLoader(context))
        try {
            val source = JsSourceRepo.loadCached(context, true).firstOrNull { it.sourceKey == "jm" }
                ?: JsSourceRepo.install(context, "https://raw.githubusercontent.com/venera-app/venera-configs/main/index.json", true)
                    .first { it.sourceKey == "jm" }
            val books = withTimeout(60_000) { value(source.search("1444347")) }
            val book = books.firstOrNull { it.id == "1444347" } ?: books.first()
            report.put("resultCount", books.size).put("bookId", book.id)
            val chapters = withTimeout(60_000) { value(source.getChapters(book.id)) }
            val chapter = chapters.first()
            val pages = withTimeout(60_000) { value(source.getChapterImages(chapter.id)) }
            report.put("chapterCount", chapters.size).put("pageCount", pages.size)
            val samples = JSONArray(); report.put("samples", samples)
            for (index in listOf(0, pages.size / 2, pages.lastIndex).distinct()) {
                val url = pages[index]
                val headers = source.getChapterImageHeaders(chapter.id, listOf(url))
                val resolved = withTimeout(60_000) { source.resolveChapterImage(url) } ?: url
                val ref = ComicPageRef.Remote("jm-enhancement-${book.id}-$index", resolved,
                    headers[url].orEmpty() + source.getResolvedHeaders(resolved))
                val row = JSONObject().put("page", index + 1); samples.put(row)
                val modes = if (index == 0) ComicEnhanceMode.entries else listOf(ComicEnhanceMode.SUPER_RES)
                val engines = JSONArray(); row.put("engines", engines)
                for (mode in modes) {
                    val config = ComicReaderConfig(enhanceMode = mode)
                    val slot = ComicSlot(ref, index)
                    val key = slotCacheKey(slot, config, ComicBookState())
                    val started = SystemClock.elapsedRealtime()
                    val result = withTimeout(120_000) { loader.loadForDisplay(ref, key, ComicImagePipeline.Geometry(), toneOf(config), true) }
                    assertNull("$mode must not silently fall back", result.error)
                    assertTrue(result.bitmap.width > 0 && result.bitmap.height > 0)
                    assertSame(result.bitmap, loader.peekProcessed(key))
                    engines.put(JSONObject().put("mode", mode.name).put("width", result.bitmap.width)
                        .put("height", result.bitmap.height).put("bytes", result.bitmap.byteCount)
                        .put("ms", SystemClock.elapsedRealtime() - started))
                }
            }
            report.put("status", "passed").put("nativeCnn", ComicNeuralBackend.usedNative)
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.message)
            throw error
        } finally { file.writeText(report.toString(2)); loader.shutdown() }
    }
}
