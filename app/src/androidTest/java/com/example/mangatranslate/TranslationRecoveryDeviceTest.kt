package com.example.mangatranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real ONNX inference on dimensions that previously aliased/recycled the source bitmap. */
class TranslationRecoveryDeviceTest {
    @Test fun squareDetectorInputsRemainAliveAndProduceTranslatedRegions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        OrtSessions.ensureCacheRoot(context)
        TranslateModelManager.ensureDownloaded(context) { }
        assertTrue("Native OCR models must be verified", TranslateModelManager.isReady(context))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
            val source = JSONObject(buffer.readUtf8()).getJSONObject("source").getJSONArray("text_list")
            val output = JSONArray(); repeat(source.length()) { output.put("你好，世界。") }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(JSONObject().put("header", JSONObject().put("ret_code", "succ")).put("auto_translation", output).toString().toResponseBody()).build()
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
