package com.example.mangatranslate

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnlineTranslation125Test {
    @Test fun duplicateDialogueIsTranslatedOnceThenCached() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"header":{"ret_code":"succ"},"auto_translation":["你好"]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val translator = OnlineFallbackTranslator(clientOverride = client)
        assertEquals(listOf("你好", "你好"), translator.translateBatch(listOf("Hello", "Hello"), "en"))
        assertEquals(listOf("你好"), translator.translateBatch(listOf("Hello"), "en"))
        assertEquals(1, calls.get())
    }
    @Test fun failedBatchDoesNotFanOutToGoogleOrSingleRequests() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("transmart.qq.com", chain.request().url.host)
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable")
                .body("{}".toResponseBody()).build()
        }.build()
        val translator = OnlineFallbackTranslator(clientOverride = client)
        assertNull(translator.translateBatch(List(12) { "Dialogue $it" }, "en"))
        assertEquals(2, calls.get()) // One bounded retry of the batch, never N single requests.
    }
    @Test fun parserRejectsEmptyNullAndMismatchedResponses() {
        val translator = OnlineFallbackTranslator()
        assertNull(translator.parseTransmart("""{"header":{"ret_code":"succ"},"auto_translation":[null]}""", 1))
        assertNull(translator.parseTransmart("""{"header":{"ret_code":"succ"},"auto_translation":[""]}""", 1))
        assertNull(translator.parseTransmart("""{"header":{"ret_code":"succ"},"auto_translation":["好"]}""", 2))
        assertEquals(listOf("好"), translator.parseTransmart("""{"header":{"ret_code":"succ"},"auto_translation":["好"]}""", 1))
    }
}
