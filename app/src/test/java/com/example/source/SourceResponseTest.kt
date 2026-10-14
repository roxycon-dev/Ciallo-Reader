package com.example.source

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.Charset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SourceResponseTest {
    @Test fun `BOM and UTF16 network JSON decode correctly`() {
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val text = SourceResponse.text("\uFEFF{\"title\":\"测试\"}".toByteArray(charset))
            assertEquals("测试", SourceResponse.jsonObject(text).getString("title"))
        }
    }
    @Test fun `HTTP charset and HTML GBK declaration are honored`() {
        val html = "<meta charset='gbk'><h1>测试小说</h1>"
        val bytes = html.toByteArray(Charset.forName("GB18030"))
        assertEquals(html, SourceResponse.text(bytes))
        assertEquals("测试", SourceResponse.text("测试".toByteArray(Charset.forName("GB18030"))))
        assertEquals("测试", SourceResponse.text("测试".toByteArray(Charset.forName("GB18030")), mapOf("content-type" to "text/plain; charset=GB18030")))
    }
    @Test fun `legitimate replacement character is not mistaken for GBK`() {
        assertEquals("测试�", SourceResponse.text("测试�".toByteArray()))
    }
    @Test fun `HTML error and verification pages are not JSON`() {
        for (html in listOf("<html><title>Just a moment...</title></html>", "<!doctype html><h1>维护中</h1>")) {
            val error = assertThrows(SourceException.ParseError::class.java) { SourceResponse.jsonObject(html) }
            assertFalse(error.message.orEmpty().contains("<html>"))
        }
        assertTrue(SourceResponse.problem("<html><title>Just a moment...</title></html>")!!.contains("验证"))
    }
    @Test fun `empty truncated null scalars and trailing garbage cannot become empty search results`() {
        for (text in listOf("", "\uFEFF ", "null", "123", "{\"items\":", "{} trailing"))
            assertThrows(SourceException.ParseError::class.java) { SourceResponse.jsonRoot(text) }
    }
    @Test fun `valid empty arrays and normal HTML remain valid`() {
        assertEquals(0, (SourceResponse.jsonRoot("[]") as JSONArray).length())
        assertNull(SourceResponse.problem("<html><title>测试书库</title><a>登录</a></html>"))
        assertNull(SourceResponse.problem("{\"title\":\"Cloudflare\"}", true))
        val json = "{\"html\":\"<meta charset='gbk'>\",\"title\":\"测试\"}"
        assertEquals(json, SourceResponse.text(json.toByteArray()))
        assertEquals("测试", SourceResponse.text("\uFEFF测试".toByteArray(), mapOf("Content-Type" to "application/json; charset=GBK")))
    }
}
