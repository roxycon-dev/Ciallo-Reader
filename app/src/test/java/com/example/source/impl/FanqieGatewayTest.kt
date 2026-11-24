package com.example.source.impl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.download.GuestNovelEpub
import com.example.source.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FanqieGatewayTest {
    private val bookId = "7600000000000000001"
    private val chapterId = "7600000000000000002"
    private val directory get() = """{"allItemIds":["$chapterId"],"chapterListWithVolume":[[{"itemId":"$chapterId","title":"第一章","volume_name":"正文","needPay":0,"isChapterLock":false,"isPaidPublication":false,"isPaidStory":false}]]}"""
    private fun <T> value(result: SourceResult<T>): T = when (result) {
        is SourceResult.Success -> result.data
        is SourceResult.Error -> throw result.exception
    }
    @Test fun searchDetailTextAndWholeBookExportUseProductionAdapterWithoutCookies() = runBlocking {
        var contentAttempts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Cookie"))
            val url = chain.request().url
            val body = when {
                url.host == "novel.snssdk.com" -> """{"code":0,"data":{"ret_data":[{"book_id":"$bookId","genre":"0","title":"一本新书","author":"作者"}]}}"""
                url.host == "www.shuhuangdian.com" -> """{"code":200,"data":{"books":[{"id":"qd123","bookId":"123","site":"起点","bookTitle":"其他平台"}]}}"""
                url.encodedPath.contains("directory") -> """{"data":$directory}"""
                url.queryParameter("action") == "detail" -> """{"code":0,"data":{"book_infos":[{"book_id":"$bookId","book_name":"一本新书","author":"作者"}]}}"""
                else -> if (++contentAttempts == 1) """{"code":-1,"message":"获取内容失败"}"""
                    else """{"code":0,"content":"<p>中文<span>内联</span>正文。</p><p>第二段😀。</p>"}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = PublicNovelSource(context, PublicNovelSite.FANQIE, client = client)
        assertTrue(source.isNovelSource)
        assertFalse(source.isComicSource)
        assertTrue(source.capabilities.supportOnlineText)
        assertEquals(listOf(bookId), value(source.search("一本新书")).map { it.id })
        val book = value(source.getDetail(bookId))
        assertEquals(1, book.novelInfo?.chapterCount)
        val toc = value(source.getChapters(bookId))
        assertEquals("中文内联正文。\n\n第二段😀。", value(source.getChapterText(toc.single().id)))
        assertEquals(2, contentAttempts)
        assertEquals("https://fanqienovel.com/page/$bookId", source.getShareUrl(bookId))
        val output = File.createTempFile("fanqie-fixture", ".epub", context.cacheDir)
        try {
            GuestNovelEpub.write(source, value(source.getDownloadInfo(bookId)).url, output) { _, _ -> }
            ZipFile(output).use { zip ->
                assertNotNull(zip.getEntry("OEBPS/content.opf"))
                val entry = zip.entries().asSequence().first { it.name.endsWith(".xhtml") && it.name.contains("chapter") }
                val html = zip.getInputStream(entry).bufferedReader().readText()
                assertTrue(html.contains("中文内联正文。"))
                assertTrue(html.contains("第二段😀。"))
            }
        } finally { output.delete(); com.example.download.GuestNovelCheckpoint.clear(output) }
    }
    @Test fun rejectsPaidIncompleteAndDuplicateDirectoriesAndBrokenContent() {
        val gateway = FanqieGateway(OkHttpClient())
        assertEquals(1, gateway.parseChapters(JSONObject(directory.replace("\"isChapterLock\":false", "\"isChapterLock\":true"))).size)
        for (bad in listOf(directory.replace("\"needPay\":0", "\"needPay\":1"),
            directory.replace("\"isPaidStory\":false", "\"isPaidStory\":true"),
            directory.replace("\"allItemIds\":[\"$chapterId\"]", "\"allItemIds\":[]"),
            directory.replace("\"allItemIds\":[\"$chapterId\"]", "\"allItemIds\":[\"$chapterId\",\"$chapterId\"]"))) {
            try { gateway.parseChapters(JSONObject(bad)); fail("Invalid directory was accepted") }
            catch (_: IllegalArgumentException) { }
        }
        // Damaged or non-public text still aborts the chapter: mojibake, font-obfuscated glyphs,
        // an upstream "content failed" placeholder and a login/purchase stub.
        val broken = listOf(
            JSONObject().put("code", 0).put("content", "<p>内容加载失败</p>"),
            JSONObject().put("code", 0).put("content", "<p>中文\uE012</p>"),
            JSONObject().put("code", 0).put("content", "<p>请登录后阅读</p>"),
            JSONObject().put("code", 0).put("content", "<p>中文\uFFFD</p>"),
            JSONObject().put("code", 0).put("content", "<p>购买本章后可阅读</p>"),
            JSONObject().put("code", 0).put("content", "<canvas id=\"font\"></canvas>"),
            JSONObject().put("code", 0).put("content", "   ")
        )
        for (bad in broken) {
            try { gateway.parseParts(bad); fail("Broken body was accepted: $bad") }
            catch (_: SourceException) { }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun missingBodyIsAnUpstreamErrorInsteadOfAFormatComplaint() {
        val gateway = FanqieGateway(OkHttpClient())
        // No content field at all: the caller's bounded retry policy must see a retryable error,
        // not "unsupported body format".
        for (bad in listOf(
            JSONObject().put("code", 0),
            JSONObject().put("code", 0).put("content", ""),
            JSONObject().put("code", 0).put("data", JSONObject().put("message", "busy"))
        )) {
            val error = try { gateway.parseParts(bad); null } catch (e: Exception) { e }
            assertTrue("Expected a retryable upstream error for $bad, got $error", error is SourceException.NetworkError)
            assertFalse(error!!.message.orEmpty().contains("不支持的正文格式"))
        }
    }
    @Test fun illustrationsAndInlineMarkupAreParsedInsteadOfRejected() {
        val gateway = FanqieGateway(OkHttpClient())
        val html = """<div><p>第一段<strong>加粗</strong>正文。</p>
            <p><img src="//p3-novel.byteimg.com/origin/abc~tplv-resize:720:0.webp" alt="插图"></p>
            <p>插图之后的正文<br>换行继续。</p>
            <img data-src="https://p6-novel.byteimg.com/illustration.png">
            <p>末尾段落。</p></div>"""
        val parts = gateway.parseParts(JSONObject().put("code", 0).put("content", html))
        val text = parts.filterIsInstance<NovelPart.Text>().joinToString("\n") { it.value }
        val images = parts.filterIsInstance<NovelPart.Image>().map { it.url }
        assertEquals(2, images.size)
        assertTrue(images[0].startsWith("https://p3-novel.byteimg.com/origin/abc"))
        assertEquals("https://p6-novel.byteimg.com/illustration.png", images[1])
        assertTrue(text.contains("第一段加粗正文。"))
        assertTrue(text.contains("插图之后的正文"))
        assertTrue(text.contains("换行继续。"))
        assertTrue(text.contains("末尾段落。"))
        assertFalse("Illustrations must not be dropped mid-chapter", text.contains("[插图]"))
    }
    @Test fun lazyPlaceholdersAreDroppedWithoutFailingTheChapter() {
        val gateway = FanqieGateway(OkHttpClient())
        val parts = gateway.parseParts(JSONObject().put("code", 0)
            .put("content", """<p><img src="data:image/gif;base64,R0lGODlhAQABAAAAACw="></p><p>只有正文。</p>"""))
        assertEquals(listOf("只有正文。"), parts.filterIsInstance<NovelPart.Text>().map { it.value })
        assertTrue(parts.none { it is NovelPart.Image })
    }
    @Test fun nestedDataEnvelopeAndPlainTextPayloadAreAccepted() {
        val gateway = FanqieGateway(OkHttpClient())
        val nested = gateway.parseParts(JSONObject().put("code", 200)
            .put("data", JSONObject().put("content", "<p>嵌套结构正文。</p>")))
        assertEquals("嵌套结构正文。", nested.filterIsInstance<NovelPart.Text>().single().value)
        val plain = gateway.parseParts(JSONObject().put("code", 0).put("txt", "<p>纯文本字段正文。</p>"))
        assertEquals("纯文本字段正文。", plain.filterIsInstance<NovelPart.Text>().single().value)
    }
    @Test fun doubleEscapedMarkupIsDecodedInsteadOfShippedAsLiteralTags() {
        val gateway = FanqieGateway(OkHttpClient())
        val parts = gateway.parseParts(JSONObject().put("code", 0)
            .put("content", "&lt;p&gt;转义正文第一段。&lt;/p&gt;&lt;p&gt;转义正文第二段。&lt;/p&gt;"))
        assertEquals(listOf("转义正文第一段。", "转义正文第二段。"),
            parts.filterIsInstance<NovelPart.Text>().map { it.value })
    }
    @Test fun brAndDivLayoutWithoutParagraphsStillSplitsParagraphs() {
        val gateway = FanqieGateway(OkHttpClient())
        val parts = gateway.parseParts(JSONObject().put("code", 0)
            .put("content", "第一行<br>第二行<br/><div>第三行</div>"))
        assertEquals(listOf("第一行", "第二行", "第三行"), parts.filterIsInstance<NovelPart.Text>().map { it.value })
    }
}
