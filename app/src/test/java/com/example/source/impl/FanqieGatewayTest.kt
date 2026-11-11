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
        for (bad in listOf("", "<p>内容加载失败</p>", "<p>中文\uE012</p>", "<p>请登录后阅读</p>", "<p>中文\uFFFD</p>")) {
            try { gateway.parseParts(JSONObject().put("code", 0).put("content", bad)); fail("Broken body was accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
