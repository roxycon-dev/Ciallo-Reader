package com.example.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.source.impl.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NovelRepairDeviceTest {
    private fun <T> value(result: SourceResult<T>) = when (result) {
        is SourceResult.Success -> result.data
        is SourceResult.Error -> throw result.exception
    }
    @Test fun publicNovelSearchDetailsAndTextAudit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val samples: List<Pair<BookSource, String>> = listOf(
            AutoNovelSource(context) to "Re0",
            Wenku8LibrarySource(context) to "Re",
            IxdzsSource(context) to "小王子",
            PublicNovelSource(context, PublicNovelSite.QUANBEN) to "小王子",
            PublicNovelSource(context, PublicNovelSite.IHUABEN) to "我也不想被他钓",
            PublicNovelSource(context, PublicNovelSite.SFACG) to "我的微小说集",
            PublicNovelSource(context, PublicNovelSite.FANQIE) to "无职转生，仅此而已的人生")
        val reports = JSONArray()
        for ((source, query) in samples) {
            val report = JSONObject().put("source", source.id).put("query", query)
            try { withTimeout(150_000) {
                val books = value(source.search(query))
                require(books.isNotEmpty()) { "搜索未返回作品" }
                val book = if (source.id == "fanqie") books.first { it.title == query } else books.first()
                val detail = value(source.getDetail(book.id))
                report.put("searchCount", books.size).put("book", detail.id).put("title", detail.title)
                val download = value(source.getDownloadInfo(book.id))
                require(download.format in setOf("txt", "epub"))
                report.put("downloadFormat", download.format)
                if (source is ComicSource) {
                    val toc = value(source.getChapters(book.id))
                    require(toc.isNotEmpty())
                    report.put("chapters", toc.size)
                    val indexes = if (source.id == "fanqie") listOf(0, toc.size / 2, toc.lastIndex).distinct() else listOf(toc.size / 2)
                    val chapters = JSONArray()
                    for (index in indexes) {
                        val body = value(source.getChapterText(toc[index].id))
                        require(body.isNotBlank() && !body.contains('\uFFFD'))
                        chapters.put(JSONObject().put("index", index).put("title", toc[index].title).put("characters", body.length))
                    }
                    report.put("samples", chapters)
                }
                report.put("passed", true)
            } } catch (e: Exception) { report.put("passed", false).put("error", e.message) }
            reports.put(report)
            File(context.filesDir, "novel-repair-audit.json").writeText(reports.toString(2))
        }
        val failed = (0 until reports.length()).map { reports.getJSONObject(it) }.filter { !it.optBoolean("passed") }
        assertTrue(failed.joinToString { it.toString() }, failed.isEmpty())
    }
}
