@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.example.source

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.data.BookRepository
import com.example.data.PreferencesManager
import com.example.data.TtsManager
import com.example.download.DownloadManager
import com.example.download.DownloadRequest
import com.example.download.DownloadStatus
import com.example.download.GuestNovelIntegrity
import com.example.library.LibraryViewModel
import com.example.source.impl.XbookcnSource
import com.example.ui.ReaderScreen
import com.example.ui.comic.ComicReaderTestActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * 书阁（xbookcn）真实链路验收：
 * 注册、搜索、详情、完整目录、正文、生产 DownloadManager → Worker → 校验 → 入库 → ReaderScreen。
 * 全部走真实网络，不使用假响应。
 */
@RunWith(AndroidJUnit4::class)
class XbookcnSourceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun sourceRegistersWithoutAdultSwitch() = runBlocking {
        // 成人源开关关闭时也必须注册
        PreferencesManager(context).showAdultSources = false
        val vm = LibraryViewModel(context as Application)
        withTimeout(90_000) { vm.sourcesReady.first { it } }
        val source = vm.sourceManager.availableSources.value.firstOrNull { it.id == XbookcnSource.SOURCE_ID }
        assertNotNull("xbookcn 未注册进书源列表", source)
        assertEquals("书阁", source!!.name)
        assertTrue(source.capabilities.supportEbook)
        assertTrue(source.capabilities.supportOnlineText)
        assertTrue(!source.capabilities.supportComic)
    }

    @Test
    fun liveSearchTitleAuthorTraditionalAndEmpty() = runBlocking {
        val source = XbookcnSource(context)
        suspend fun books(query: String): List<SearchBook> {
            val result = source.search(query)
            assertTrue("$query -> $result", result is SourceResult.Success)
            return (result as SourceResult.Success).data
        }

        val exact = books("总裁X总裁")
        assertTrue("exact search empty: ${exact.size}", exact.isNotEmpty())
        assertEquals("总裁X总裁", exact.first().title)
        assertTrue(exact.all { it.id.matches(Regex("[0-9a-f]{12}")) })

        val partial = books("总裁")
        assertTrue(partial.isNotEmpty())
        assertTrue(partial.any { it.title.contains("总裁") })

        val byAuthor = books("顾盼若浅")
        assertTrue(byAuthor.isNotEmpty())
        assertTrue("no author hit: ${byAuthor.take(5).map { it.title to it.author }}",
            byAuthor.any { it.author == "顾盼若浅" })

        // 该站按原字匹配，不做繁简折叠；繁体输入应返回繁体标题结果。
        assertTrue("traditional search empty", books("總裁").isNotEmpty())

        assertTrue(books("zzqqxx9999vv").isEmpty())
    }

    @Test
    fun catalogMatchesSourceMetadataAndOrder() = runBlocking {
        val source = XbookcnSource(context)
        for (bookId in listOf("b3068315c735", "ee886a4fb420")) {
            val detailResult = source.getDetail(bookId)
            assertTrue("$bookId detail -> $detailResult", detailResult is SourceResult.Success)
            val detail = (detailResult as SourceResult.Success).data
            val chaptersResult = source.getChapters(bookId)
            assertTrue("$bookId chapters -> $chaptersResult", chaptersResult is SourceResult.Success)
            val chapters = (chaptersResult as SourceResult.Success).data
            assertEquals("$bookId 章数与详情页不一致", detail.novelInfo?.chapterCount ?: -1, chapters.size)
            assertEquals(chapters.size, chapters.map { it.order }.distinct().size)
            assertEquals((1..chapters.size).map { it.toFloat() }, chapters.map { it.order })
            assertEquals(chapters.size, chapters.map { it.id }.distinct().size)
            val againResult = source.getChapters(bookId)
            assertTrue("$bookId chapters again -> $againResult", againResult is SourceResult.Success)
            val again = (againResult as SourceResult.Success).data
            assertEquals(chapters.map { it.id to it.title }, again.map { it.id to it.title })
            val firstResult = source.getChapterText(chapters.first().id)
            assertTrue("$bookId first text -> $firstResult", firstResult is SourceResult.Success)
            val first = (firstResult as SourceResult.Success).data
            val lastResult = source.getChapterText(chapters.last().id)
            assertTrue("$bookId last text -> $lastResult", lastResult is SourceResult.Success)
            val last = (lastResult as SourceResult.Success).data
            assertTrue(first.length > 100)
            assertTrue(last.length > 100)
            println("CATALOG $bookId chapters=${chapters.size} first=${chapters.first().title} last=${chapters.last().title} chars=${first.length}/${last.length}")
        }
    }

    @Test
    fun rejectsInvalidBookAndChapterIds() = runBlocking {
        val source = XbookcnSource(context)
        assertTrue(source.getDetail("000000000000") is SourceResult.Error)
        assertTrue(source.getChapters("000000000000") is SourceResult.Error)
        assertTrue(source.getChapterText("b3068315c735/9999") is SourceResult.Error)
        assertTrue(source.getChapterText("../../etc/passwd") is SourceResult.Error)
    }

    @Test
    fun downloadImportVerifyAndRenderInProductionReader() = runBlocking {
        val bookId = "b3068315c735"
        val source = XbookcnSource(context)
        val db = AppDatabase.getDatabase(context)
        val dao = db.bookDao()
        val taskDao = db.downloadTaskDao()
        val repository = BookRepository(context, dao, db)

        // 可重复执行：先清掉上一轮的同一本书与任务。
        val taskId = DownloadManager.taskId(XbookcnSource.SOURCE_ID, bookId)
        dao.getBookBySourceResource(XbookcnSource.SOURCE_ID, bookId)?.let { repository.deleteBook(it) }
        taskDao.deleteTaskById(taskId)

        val book = (source.getDetail(bookId) as SourceResult.Success).data
        val info = (source.getDownloadInfo(bookId) as SourceResult.Success).data
        assertEquals("epub", info.format)
        assertTrue(info.url.endsWith("#${XbookcnSource.EPUB_MARKER}"))

        val manager = DownloadManager(context)
        manager.enqueueDownload(
            DownloadRequest(
                bookId = book.id,
                title = book.title,
                author = book.author,
                sourceId = XbookcnSource.SOURCE_ID,
                downloadUrl = info.url,
                format = info.format,
                coverUrl = book.cover,
                novelSnapshot = book
            ),
            referer = info.referer,
            headers = info.headers
        ).join()

        val deadline = System.currentTimeMillis() + 180_000
        var status = DownloadStatus.PENDING
        while (System.currentTimeMillis() < deadline) {
            val task = taskDao.getTaskById(taskId) ?: error("下载任务丢失")
            status = task.status
            if (status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED) break
            kotlinx.coroutines.delay(1_000)
        }
        assertEquals("下载失败：${taskDao.getTaskById(taskId)?.errorMessage}", DownloadStatus.COMPLETED, status)

        val imported = dao.getBookBySourceResource(XbookcnSource.SOURCE_ID, bookId)
            ?: error("下载完成但未入库")
        assertEquals(book.title, imported.title)
        val chapters = dao.getChaptersListForBook(imported.id)
        // 长章会按 MAX_CHAPTER_LENGTH 拆成 "(续N)" 物理章节，逻辑章节仍应等于源目录 4 章。
        val logical = com.example.data.ChapterMerger.buildLogicalChapters(chapters)
        assertEquals("逻辑章节数", 4, logical.chapters.size)
        assertEquals(listOf("001", "010", "029", "048"), logical.chapters.map { it.title })
        assertTrue(logical.chapters.first().content.contains("顾倾"))
        assertTrue(logical.chapters.last().content.length > 200)
        println("MAP physical=${chapters.size} ${chapters.map { it.title }} logical=${logical.chapters.map { it.title }}")

        val file = File(imported.filePath.removePrefix("file://"))
        assertTrue("EPUB 文件不存在", file.isFile)
        val summary = GuestNovelIntegrity.epub(file)
        assertEquals(4, summary.titles.size)
        assertEquals(0, summary.images)
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        println("DOWNLOAD book=${imported.title} bytes=${file.length()} sha256=$sha chapters=${chapters.size}")
        // 生产阅读器：打开中间章，等待异步排版完成后截取真实窗口。
        val tts = TtsManager(context)
        try {
            val middle = imported.copy(currentChapterIndex = 1, scrollOffset = 0)
            compose.setContent {
                MaterialTheme {
                    ReaderScreen(
                        book = middle, bookTitle = middle.title, chapters = chapters,
                        readerLoading = false, loadedChapterIndices = chapters.indices.toSet(),
                        readerLoadError = null, onRetryLoad = {}, onEnsureChapterLoaded = { _, _ -> },
                        onBack = {}, onUpdateProgress = { _, _, _, _ -> },
                        prefs = PreferencesManager(context), ttsManager = tts,
                        highlights = emptyList(), bookmarks = emptyList(),
                        onAddBookmark = { _, _, _, _, _ -> }, onDeleteBookmark = {},
                        onAddHighlight = { _, _, _, _, _ -> }, onDeleteHighlight = {},
                        searchResults = emptyList(), isSearching = false, onSearch = {}, onRecordTime = {}
                    )
                }
            }
            compose.waitForIdle()
            var nonBlank = false
            compose.waitUntil(timeoutMillis = 25_000) {
                val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation.takeScreenshot() ?: return@waitUntil false
                val colors = HashSet<Int>()
                for (y in 140 until image.height - 140 step 7) {
                    for (x in 25 until image.width - 25 step 7) colors += image.getPixel(x, y)
                }
                nonBlank = colors.size > 12
                nonBlank
            }
            assertTrue(nonBlank)
            android.util.Log.i(
                "XbookcnAudit",
                "bookId=$bookId title=${imported.title} author=${imported.author} " +
                    "url=${info.url} epubBytes=${file.length()} epubSha256=$sha " +
                    "epubTitles=${summary.titles} images=${summary.images} " +
                    "physical=${chapters.size} ${chapters.map { it.title }} " +
                    "logical=${logical.chapters.size} ${logical.chapters.map { it.title }} " +
                    "firstChars=${logical.chapters.first().content.length} " +
                    "lastChars=${logical.chapters.last().content.length}"
            )
            // 保持阅读器画面 20 秒，供外部 adb screencap 取证。
            Thread.sleep(20_000)
        } finally {
            tts.release()
        }

        // 还原测试环境
        dao.getBookBySourceResource(XbookcnSource.SOURCE_ID, bookId)?.let { repository.deleteBook(it) }
        taskDao.deleteTaskById(taskId)
    }
}
