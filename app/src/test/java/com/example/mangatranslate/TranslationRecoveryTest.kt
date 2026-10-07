package com.example.mangatranslate

import android.content.Context
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun client(block: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (status, body) = block(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body.toResponseBody()).build()
    }.build()
    private fun configured(client: OkHttpClient): LlmBubbleTranslator = LlmBubbleTranslator(context, client).apply {
        saveConfig(LlmBubbleTranslator.LlmConfig("https://api.example.com/v1", "", "test-model", false))
    }
    private val items = listOf(LlmBubbleTranslator.Item(0, "Hello"))
    @Test fun permanentRejectionDoesNotRepeatOrLeakServerDetails() = runBlocking {
        for (status in listOf(401, 402, 403, 404, 400)) {
            val calls = AtomicInteger()
            val llm = configured(client { calls.incrementAndGet(); status to "secret-response-api-key" })
            assertNull(llm.translateBubbles(items)); assertEquals(1, calls.get())
            assertFalse(llm.lastFailure!!.retryable); assertFalse(llm.lastFailure!!.description.contains("secret"))
        }
    }
    @Test fun transientFailureRetriesOnlyOnceAndRecovers() = runBlocking {
        val calls = AtomicInteger()
        val llm = configured(client {
            if (calls.incrementAndGet() == 1) 503 to "{}"
            else 200 to """{"choices":[{"message":{"content":"{\"items\":[{\"id\":0,\"translation\":\"你好\"}]}"}}]}"""
        })
        assertEquals(mapOf(0 to "你好"), llm.translateBubbles(items)); assertEquals(2, calls.get()); assertNull(llm.lastFailure)
    }
    @Test fun malformedLlmOutputHasBoundedRetryAndUsefulReason() = runBlocking {
        val calls = AtomicInteger()
        val llm = configured(client { calls.incrementAndGet(); 200 to """{"choices":[{"message":{"content":"not json"}}]}""" })
        assertNull(llm.translateBubbles(items)); assertEquals(2, calls.get()); assertEquals("format", llm.lastFailure?.code)
    }
    @Test fun deliberatelySkippedNoiseStillPreservesTheExactId() {
        val llm = LlmBubbleTranslator(context)
        assertEquals(mapOf(0 to ""), llm.parseStrict("""{"items":[{"id":0,"translation":""}]}""", items))
        assertNull(llm.parseStrict("""{"items":[{"id":0,"translation":null}]}""", items))
        assertNull(llm.parseStrict("""{"items":[{"id":"0","translation":"好"}]}""", items))
    }
    @Test fun differentBooksDoNotShareCharacterNames() {
        val a = LlmBubbleTranslator(context, glossaryScope = "book-a")
        val b = LlmBubbleTranslator(context, glossaryScope = "book-b")
        a.parseStrict("""{"items":[{"id":0,"translation":"你好"}],"glossary_used":{"Alice":"爱丽丝"}}""", items)
        assertEquals("爱丽丝", LlmBubbleTranslator(context, glossaryScope = "book-a").glossarySnapshot()["Alice"])
        assertNull(b.glossarySnapshot()["Alice"])
    }
    @Test fun pageBatchingPreservesEveryIdAndBoundsContextSize() {
        val llm = LlmBubbleTranslator(context)
        val input = List(48) { LlmBubbleTranslator.Item(it, "x".repeat(310)) }
        val batches = llm.batches(input)
        assertEquals(input, batches.flatten())
        assertTrue(batches.all { it.size <= 20 && it.sumOf { item -> item.text.length } <= 6000 })
    }
    @Test fun ocrCacheRetainsGeometryEvenWhenNetworkTranslationFailed() {
        val region = TranslatedRegion(RectF(10f, 20f, 80f, 100f), "Hello", "", "en", false, lineRects = listOf(RectF(20f, 30f, 70f, 50f)))
        TranslationCache.write(context, "ocr-test", PageTranslation(100, 120, listOf(region)))
        assertEquals(region, TranslationCache.read(context, "ocr-test", 100, 120)?.regions?.single())
        assertNull(TranslationCache.read(context, "ocr-test", 101, 120))
    }
    @Test fun freeServiceReportsRejectionAndCanRecoverOnManualRetry() = runBlocking {
        val calls = AtomicInteger()
        val translator = OnlineFallbackTranslator(clientOverride = client {
            if (calls.incrementAndGet() == 1) 403 to "{}"
            else 200 to """{"header":{"ret_code":"succ"},"auto_translation":["你好"]}"""
        })
        assertNull(translator.translate("Hello", "en")); assertEquals("auth", translator.lastFailure?.code)
        assertEquals("你好", translator.translate("Hello", "en")); assertNull(translator.lastFailure)
    }
}
