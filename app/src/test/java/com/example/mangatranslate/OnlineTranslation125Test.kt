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
            assertEquals("api.mymemory.translated.net", chain.request().url.host)
            assertEquals("Hello", chain.request().url.queryParameter("q"))
            assertEquals("en|zh-CN", chain.request().url.queryParameter("langpair"))
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"responseStatus":200,"responseData":{"translatedText":"你好"}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val translator = OnlineFallbackTranslator(clientOverride = client)
        assertEquals(listOf("你好", "你好"), translator.translateBatch(listOf("Hello", "Hello"), "en"))
        assertEquals(listOf("你好"), translator.translateBatch(listOf("Hello"), "en"))
        assertEquals(1, calls.get())
    }
    @Test fun failedBatchTriesOneRequestPerProvider() = runBlocking {
        val hosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            hosts.add(chain.request().url.host)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable")
                .body("{}".toResponseBody()).build()
        }.build()
        val translator = OnlineFallbackTranslator(clientOverride = client)
        assertNull(translator.translateBatch(List(12) { "Dialogue $it" }, "en"))
        assertEquals(listOf("api.mymemory.translated.net", "transmart.qq.com"), hosts)
    }
    @Test fun partialNumberedResponseKeepsBubbleOrderAndBackfillsOnlyMissingText() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val response = if (req.url.host == "api.mymemory.translated.net") {
                assertEquals("⟦1⟧ Come in.\n⟦2⟧ Wait.\n⟦3⟧ See you.", req.url.queryParameter("q"))
                """{"responseStatus":200,"responseData":{"translatedText":"⟦3⟧ 再见。\n⟦1⟧ 进来吧。"}}"""
            } else {
                val payload = okio.Buffer()
                req.body!!.writeTo(payload)
                val texts = org.json.JSONObject(payload.readUtf8()).getJSONObject("source").getJSONArray("text_list")
                assertEquals(1, texts.length())
                assertEquals("Wait.", texts.getString(0))
                """{"header":{"ret_code":"succ"},"auto_translation":["等等。"]}"""
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody()).build()
        }.build()
        val translator = OnlineFallbackTranslator(clientOverride = client)
        assertEquals(listOf("进来吧。", "等等。", "再见。"),
            translator.translateBatch(listOf("Come in.", "Wait.", "See you."), "en"))
    }
    @Test fun quotaMessageIsNeverRenderedAsDialogueAndBackupStillWorks() = runBlocking {
        val primaryCalls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val response = if (req.url.host == "api.mymemory.translated.net") {
                primaryCalls.incrementAndGet()
                """{"quotaFinished":true,"responseStatus":429,"responseData":{"translatedText":"超过每日限制"}}"""
            } else """{"header":{"ret_code":"succ"},"auto_translation":["请进。"]}"""
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody()).build()
        }.build()
        val translator = OnlineFallbackTranslator(clientOverride = client)
        assertEquals("请进。", translator.translate("Come in.", "en"))
        assertEquals("请进。", translator.translate("Please come in.", "en"))
        assertEquals(1, primaryCalls.get())
    }
    @Test fun parserRejectsEmptyNullAndMismatchedResponses() {
        val translator = OnlineFallbackTranslator()
        assertNull(translator.parseTransmart("""{"header":{"ret_code":"succ"},"auto_translation":[null]}""", 1))
        assertNull(translator.parseTransmart("""{"header":{"ret_code":"succ"},"auto_translation":[""]}""", 1))
        assertNull(translator.parseTransmart("""{"header":{"ret_code":"succ"},"auto_translation":["好"]}""", 2))
        assertEquals(listOf("好"), translator.parseTransmart("""{"header":{"ret_code":"succ"},"auto_translation":["好"]}""", 1))
    }
}
