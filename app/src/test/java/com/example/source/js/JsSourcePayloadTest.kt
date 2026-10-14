package com.example.source.js

import com.example.source.SourceException
import com.example.source.parser.JsonPathResolver
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JsSourcePayloadTest {
    @Test fun `legacy cached Comick search uses JSON for LF and CRLF scripts`() {
        val repo = JsSourceRepo
        val patch = JsSourceRepo::class.java.getDeclaredMethod("patchDiscoverSource", String::class.java, String::class.java).apply { isAccessible = true }
        val source = """
            class Comick extends ComicSource {
                search = {
                    load: async () => {
                        let url = 'https://comick.art/search?q=one';
                        let document = new HtmlDocument(res.body)
                        let jsonData = JSON.parse(document.getElementById('sv-data').text);
                    }
                }
                /// single comic related
            }
        """.trimIndent()
        for (script in listOf(source, source.replace("\n", "\r\n"))) {
            val fixed = patch.invoke(repo, "comick", script) as String
            assertTrue(fixed.contains("https://comick.art/api/search?q=one"))
            assertTrue(fixed.contains("let jsonData = JSON.parse(res.body);"))
            assertFalse(fixed.contains("document.getElementById('sv-data').text"))
            assertEquals(fixed, patch.invoke(repo, "comick", fixed))
        }
    }
    @Test fun `HTML verification failures retain actionable network reason`() {
        val raw = "源站返回验证或拦截页面，请在书源管理完成站点验证或稍后重试\nat JSON.parse (source_responses.js:1)"
        assertTrue(jsSourceFailure(raw) is SourceException.NetworkError)
        assertTrue(jsSourceFailure(raw).message!!.contains("验证"))
        assertSame(SourceException.LoginRequired, jsSourceFailure("Login expired"))
        assertTrue(jsSourceFailure("接口数据结构异常，预期为 JSON 对象") is SourceException.ParseError)
    }
    @Test fun `missing or changed search envelope is an error`() {
        for (data in listOf(null, JSONObject("{}"), JSONObject("{\"comics\":null}"), JSONObject("{\"comics\":{}}")))
            assertThrows(SourceException.ParseError::class.java) { JsSourcePayload.requiredArray(data, "comics") }
        assertEquals(0, JsSourcePayload.requiredArray(JSONObject("{\"comics\":[]}"), "comics").length())
    }
    @Test fun `gallery may omit chapters but malformed directory is rejected`() {
        assertEquals(0, JsSourcePayload.chapters(JSONObject("{}" )).length())
        assertEquals(0, JsSourcePayload.chapters(JSONObject("{\"chapters\":null}" )).length())
        assertThrows(SourceException.ParseError::class.java) { JsSourcePayload.chapters(JSONObject("{\"chapters\":\"wrong\"}")) }
    }
    @Test fun `JSON null and objects cannot turn into fake titles or ids`() {
        assertEquals("", JsSourcePayload.string(JSONObject("{\"title\":null}"), "title"))
        assertEquals("", JsSourcePayload.string(JSONObject("{\"title\":{\"name\":\"wrong\"}}"), "title"))
        assertEquals("364074", JsSourcePayload.string(JSONObject("{\"id\":364074}"), "id"))
    }
    @Test fun `invalid pages are rejected rather than silently dropping pages`() {
        for (text in listOf("{}", "{\"images\":null}", "{\"images\":[\"a\",null]}", "{\"images\":[{\"url\":\"a\"}]}"))
            assertThrows(SourceException.ParseError::class.java) { JsSourcePayload.images(JSONObject(text)) }
        assertEquals(listOf("a", "b"), JsSourcePayload.images(JSONObject("{\"images\":[\"a\",\"b\"]}")))
    }
    @Test fun `custom JSON source distinguishes missing path from valid empty list`() {
        assertThrows(SourceException.ParseError::class.java) { JsonPathResolver.resolveArray("{\"error\":\"bad\"}", "data.books", required = true) }
        assertEquals(emptyList<JSONObject>(), JsonPathResolver.resolveArray("{\"data\":{\"books\":[]}}", "data.books", required = true))
        assertThrows(SourceException.ParseError::class.java) { JsonPathResolver.resolveArray("{\"items\":[null,123]}", "items", required = true) }
    }
}
