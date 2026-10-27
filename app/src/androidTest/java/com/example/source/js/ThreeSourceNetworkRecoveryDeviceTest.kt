package com.example.source.js

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class ThreeSourceNetworkRecoveryDeviceTest {
    @Test fun usableWnacgOriginDoesNotWaitForAnUnavailablePublisher() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val patch = JsSourceRepo::class.java.getDeclaredMethod("patchWnacgInit", String::class.java).apply { isAccessible = true }
        val original = """
            class Fixture extends ComicSource {
                key = 'wnacg_bootstrap_fixture';
                get baseUrl() { return 'https://www.wn001.cfd'; }
                async init() { if (this.loadSetting('refreshDomainsOnStart')) await this.refreshDomains(false) }
                async refreshDomains() { throw new Error('publisher unavailable'); }
            }
        """.trimIndent()
        val patched = patch.invoke(JsSourceRepo, original) as String
        assertEquals(patched, patch.invoke(JsSourceRepo, patched))
        val runtime = context.assets.open("venera/_venera_.js").bufferedReader().use { it.readText() }
        val engine = JsSourceEngine(runtime, patched, "wnacg_bootstrap_fixture", context)
        val result = JSONObject(engine.call("(async()=>{src.loadSetting=()=>true;await src.init();return src.baseUrl;})()")!!)
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals("https://www.wn001.cfd", result.getString("data"))
    }
    @Test fun imageRouteRejectsTruncatedSuccessfulBodiesBeforeReturningThem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var closed = false
        fun brokenBody() = object : ResponseBody() {
            private val data = object : Source {
                override fun read(sink: okio.Buffer, byteCount: Long): Long = throw IOException("truncated fixture")
                override fun timeout() = Timeout.NONE
                override fun close() { closed = true }
            }.buffer()
            override fun contentType(): MediaType? = null
            override fun contentLength() = 40L
            override fun source(): BufferedSource = data
        }
        val base = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).code(200).message("OK")
                .protocol(Protocol.HTTP_1_1).body(brokenBody()).build()
        }.build()
        val client = JsSourceProxy.failoverClient(context, base)
        try {
            client.newCall(Request.Builder().url("http://manga.iqtao.cn/fixture.jpg").build()).execute().use {
                fail("A truncated image must not escape route validation as a successful response")
            }
        } catch (e: IOException) {
            assertEquals("truncated fixture", e.message)
        }
        assertTrue("Failed response must release its body", closed)
        client.connectionPool.evictAll()
    }
}
