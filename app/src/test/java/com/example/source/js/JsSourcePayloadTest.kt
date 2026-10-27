package com.example.source.js

import com.example.source.SourceException
import com.example.source.parser.JsonPathResolver
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JsSourcePayloadTest {
    @Test fun `one unreadable cached script does not hide other sources`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = java.io.File(context.filesDir, "js_sources").apply { mkdirs() }
        java.io.File(dir, "broken.js").mkdirs()
        java.io.File(dir, "healthy.js").writeText("// EASYREADER_RAW_SOURCE_V28\nclass Fixture extends ComicSource {}\n" + " ".repeat(1024))
        java.io.File(dir, "index.json").writeText("""[{"key":"broken","fileName":"broken.js"},{"key":"healthy","fileName":"healthy.js"}]""")
        val loaded = JsSourceRepo.loadCached(context, true)
        assertTrue(loaded.any { it.sourceKey == "healthy" })
        assertFalse(loaded.any { it.sourceKey == "broken" })
    }
    @Test fun `copy source bootstrap avoids background network jobs in old and new scripts`() {
        val patch = JsSourceRepo::class.java.getDeclaredMethod("patchCopyInit", String::class.java).apply { isAccessible = true }
        val script = "    init() {\r\n this.refreshSearchApi();\r\n this.refreshAppApi()\r\n    }\r\ncallback() { this.refreshAppApi(); }"
        val result = patch.invoke(JsSourceRepo, script) as String
        assertFalse(result.contains("this.refreshSearchApi()"))
        assertTrue(result.contains("callback() { this.refreshAppApi(); }"))
        assertEquals(2, Regex("void 0").findAll(result).count())
        assertEquals(result, patch.invoke(JsSourceRepo, result))
    }
    @Test fun `JM upgrades old patched caches without depending on method indentation`() {
        val patch = JsSourceRepo::class.java.getDeclaredMethod("patchScript", String::class.java, String::class.java).apply { isAccessible = true }
        val source = """
            class JM extends ComicSource {
                static fallbackServers = ["dead.invalid"];
                static apiDomains = ["dead.invalid"];
            async get(url) {
                const __cialloApiFailover = true;
                throw new Error('connection closed');
            }
                async post(url, body) { return 'unchanged'; }
            }
        """.trimIndent()
        for (input in listOf(source, source.replace("\n", "\r\n"))) {
            val repaired = patch.invoke(JsSourceRepo, "jm", input) as String
            assertTrue(repaired.contains("__cialloApiFailoverV2"))
            assertTrue(repaired.contains("jm_read_route"))
            assertTrue(repaired.contains("www.cdnhjk.net"))
            assertFalse(repaired.contains("dead.invalid"))
            assertTrue(repaired.contains("async post(url, body) { return 'unchanged'; }"))
            assertEquals(repaired, patch.invoke(JsSourceRepo, "jm", repaired))
        }
    }
    @Test fun `Hitomi browse repair is idempotent and retains dates and unknown pagination`() {
        val patch = JsSourceRepo::class.java.getDeclaredMethod("patchScript", String::class.java, String::class.java).apply { isAccessible = true }
        val source = """
            title: "hitomi.la",
            let itemCount = 0;
            resp.headers["content-range"];
            maxPage: Math.ceil(result.count / 25),
            language: n.language,
            ...n.series,
        """.trimIndent()
        val repaired = patch.invoke(JsSourceRepo, "hitomi", source) as String
        assertTrue(repaired.contains("title: \"最新更新\""))
        assertTrue(repaired.contains("key.toLowerCase() === 'content-range'"))
        assertTrue(repaired.contains("updatedAt: n.posted_time.getTime()"))
        assertTrue(repaired.contains("maxPage: Number.isFinite(result.count)"))
        assertEquals(repaired, patch.invoke(JsSourceRepo, "hitomi", repaired))
    }
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
