package com.example.mangatranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.comic.ComicReaderConfig
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real ONNX inference on dimensions that previously aliased/recycled the source bitmap. */
class TranslationRecoveryDeviceTest {
    @Test fun defaultTranslationRendersRealChineseDialogueWithoutConfiguration() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translationLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val translator = MangaPageTranslator(context, OnlineFallbackTranslator(context), LlmBubbleTranslator(context))
        val page = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        page.eraseColor(Color.WHITE)
        try {
            val originals = listOf("本気で言ってるの？", "無理しなくていい。")
            val recognized = originals.mapIndexed { index, text ->
                val y = 30f + 100f * index
                TranslatedRegion(RectF(30f, y, 330f, y + 80f), text, "", "ja", false)
            }
            val result = translator.translateRecognized(page, recognized, ComicReaderConfig().translationEngine) {}
            val dialogue = result.regions.map { it.translated }
            assertTrue(dialogue[0], dialogue[0].contains("认真"))
            assertTrue(dialogue[1], dialogue[1].contains("勉强"))
            val baked = BubblePipeline.bake(page, result.regions.map {
                BubblePipeline.Region(it.rect, it.contour, it.lineRects, it.vertical) to it.translated
            }, 1f)
            assertFalse(page.isRecycled)
            assertNotSame(page, baked)
            println("Default key-free dialogue: $dialogue")
            baked.recycle()
        } finally {
            page.recycle()
            translator.release()
        }
    }

    @Test fun androidSystemTranslationUsesInstalledCapabilitiesOrReturnsPromptly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = android.os.SystemClock.elapsedRealtime()
        val result = SystemTextTranslation(context).translate(listOf("Good morning.", "Thank you."), "en")
        val elapsed = android.os.SystemClock.elapsedRealtime() - start
        assertTrue("System translation exceeded the fallback budget", elapsed < 4_000L)
        if (result != null) assertEquals(2, result.size)
        println("System translation result available=" + (result != null) + ", elapsedMillis=" + elapsed)
    }

    @Test fun squareDetectorInputsRemainAliveAndProduceTranslatedRegions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        OrtSessions.ensureCacheRoot(context)
        TranslateModelManager.ensureDownloaded(context) { }
        assertTrue("Native OCR models must be verified", TranslateModelManager.isReady(context))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("api.mymemory.translated.net", request.url.host)
            val query = request.url.queryParameter("q")!!
            val output = if ('\n' in query) query.lines().joinToString("\n") { "${it.substringBefore(' ')} 你好，世界。" }
                else "你好，世界。"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(JSONObject().put("responseStatus", 200).put("responseData", JSONObject().put("translatedText", output))
                    .toString().toResponseBody()).build()
        }.build()
        val translator = MangaPageTranslator(context, OnlineFallbackTranslator(context, client), LlmBubbleTranslator(context))
        try {
            for (edge in listOf(960, 1472)) {
                val page = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
                page.eraseColor(Color.WHITE)
                val canvas = Canvas(page)
                val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = edge * .05f }
                canvas.drawText("HELLO WORLD", edge * .18f, edge * .40f, ink)
                canvas.drawText("THANK YOU", edge * .25f, edge * .49f, ink)
                val start = android.os.SystemClock.elapsedRealtime()
                val result = translator.translatePageOnline(page, "en")
                assertFalse("OCR must never recycle its caller's image ($edge)", page.isRecycled)
                assertTrue("Real OCR must find the fixture's text ($edge)", result.hasUsableText)
                val baked = BubblePipeline.bake(page, result.regions.map { BubblePipeline.Region(it.rect, it.contour, it.lineRects, it.vertical) to it.translated }, 1f)
                assertFalse(page.isRecycled); assertNotSame(page, baked)
                println("Native square OCR edge=$edge regions=${result.regions.size} totalMs=${android.os.SystemClock.elapsedRealtime() - start}")
                baked.recycle(); page.recycle()
            }
        } finally { translator.release(); OrtSessions.closeAll() }
    }
}
