package com.example.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.source.impl.*
import com.example.data.AppDatabase
import com.example.download.*
import com.example.ui.reader.NovelInlineImages
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Full production download/import and online text/image paths; opt-in live evidence. */
@RunWith(AndroidJUnit4::class)
class UnifiedNovelRoutesDeviceTest {
    @Test fun everyGuestNovelSource() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val foreground=androidx.test.core.app.ActivityScenario.launch(com.example.ui.comic.ComicReaderTestActivity::class.java)
        val args=InstrumentationRegistry.getArguments()
        require(args.getString("liveNovel")=="true")
        val samples=listOf(
            AutoNovelSource(context) to "青春猪头少年不会梦到平行弹簧",
            Wenku8LibrarySource(context) to "推理迷宫",
            IxdzsSource(context) to "朝花夕拾",
            XbookcnSource(context) to "金钱",
            PublicNovelSource(context,PublicNovelSite.QUANBEN) to "小王子",
            PublicNovelSource(context,PublicNovelSite.IHUABEN) to "我也不想被他钓",
            PublicNovelSource(context,PublicNovelSite.SFACG) to "我的微小说集",
            PublicNovelSource(context,PublicNovelSite.FANQIE) to "无职转生，仅此而已的人生")
        val requested=args.getString("sourceId").orEmpty()
        val report=File(context.filesDir,"unified-novel-routes.jsonl").apply { writeText("") }
        fun <T> value(result:SourceResult<T>):T=when(result) { is SourceResult.Success -> result.data;is SourceResult.Error -> throw result.exception }
        val manager=DownloadManager(context)
        val db=AppDatabase.getDatabase(context)
        for((source,query) in samples.filter { requested.isEmpty() || it.first.id in requested.split(',') }) {
            val row=JSONObject().put("source",source.id).put("query",query).put("stage","search")
            var taskId:String?=null
            try { withTimeout(3600000) {
                val effectiveQuery=args.getString("auditQuery") ?: query
                row.put("query",effectiveQuery)
                val found=value(source.search(effectiveQuery))
                check(found.isNotEmpty()) { "搜索结果为空" }
                val book=value(source.getDetail(found.first().id))
                row.put("title",book.title).put("book",book.id)
                if(source.capabilities.supportOnlineText && source is ComicSource) {
                    row.put("stage","online_text")
                    val chapters=value(source.getChapters(book.id));check(chapters.isNotEmpty())
                    listOf(0,chapters.size/2,chapters.lastIndex).distinct().forEach { check(value(source.getChapterText(chapters[it].id)).isNotBlank()) }
                    row.put("onlineText","ok")
                } else row.put("onlineText","not_offered")
                row.put("stage","download")
                val info=value(source.getDownloadInfo(book.id))
                val resumeId=args.getString("resumeTaskId")
                val previous=resumeId?.let { db.downloadTaskDao().getTaskById(it) }
                val resource=previous?.let { DownloadManager.originalBookId(it.id,it.sourceId) } ?: (book.id+"#unified-audit-"+System.nanoTime())
                val key=previous?.id ?: DownloadManager.taskId(source.id,resource);taskId=key
                row.put("resumedFromCheckpoint",previous!=null)
                if(previous==null) manager.enqueueDownload(DownloadRequest(resource,book.title,book.author,source.id,info.url,info.format,book.cover),info.referer,info.headers).join()
                else if(previous.status in setOf(DownloadStatus.PAUSED,DownloadStatus.FAILED)) {
                    manager.resumeDownload(key)
                    withTimeout(30_000) {
                        while(db.downloadTaskDao().getTaskById(key)?.status==previous.status) delay(100)
                    }
                }
                val task=withTimeout(3500000) {
                    while(true) {
                        val t=db.downloadTaskDao().getTaskById(key)
                        if(t?.status in setOf(DownloadStatus.COMPLETED,DownloadStatus.FAILED)) return@withTimeout t!!
                        delay(500)
                    }
                    error("unreachable")
                }
                check(task.status==DownloadStatus.COMPLETED) { task.errorMessage ?: "下载失败" }
                row.put("stage","offline")
                val local=db.bookDao().getBookBySourceResource(source.id,resource) ?: error("缺少本地书籍")
                val chapters=db.bookDao().getChaptersListForBook(local.id)
                check(chapters.isNotEmpty() && chapters.any { it.content.isNotBlank() })
                val images=chapters.flatMap { NovelInlineImages.TOKEN_REGEX.findAll(it.content).map { match -> match.groupValues[1] }.toList() }.distinct()
                images.forEach { spec ->
                    val bitmap=if(spec.startsWith("epzip:")) NovelInlineImages.NovelImageCache.decodeSampledFromEpub(spec)
                        else android.graphics.BitmapFactory.decodeFile(spec)
                    check(bitmap!=null && bitmap.width>0 && bitmap.height>0) { "本地插图无法解码" };bitmap.recycle()
                }
                row.put("bytes",File(task.filePath).length()).put("chapters",chapters.size).put("decodedImages",images.size)
                    .put("status","download_and_offline_ok")
            } } catch(e:Exception) {
                row.put("status","failed").put("error",e.message.orEmpty().take(300))
                taskId?.let { manager.pauseDownload(it) }
            }
            report.appendText(row.toString()+"\n")
            android.util.Log.i("UnifiedNovelAudit",row.toString())
        }
        foreground.close()
        if(args.getString("requirePass")=="true") check(report.readLines().all { JSONObject(it).optString("status")=="download_and_offline_ok" }) { report.readText() }
    }
}
