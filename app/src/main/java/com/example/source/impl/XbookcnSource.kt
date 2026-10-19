package com.example.source.impl

import android.content.Context
import android.util.LruCache
import com.example.data.readImportBytes
import com.example.source.AuthenticationState
import com.example.source.ComicChapter
import com.example.source.ComicSource
import com.example.source.DownloadInfo
import com.example.source.LoginCredential
import com.example.source.NovelInfo
import com.example.source.SearchBook
import com.example.source.SourceCapabilities
import com.example.source.SourceException
import com.example.source.SourceResponse
import com.example.source.SourceResult
import com.example.source.UpdatableNovelSource
import com.example.source.executeCancellable
import com.example.source.js.JsSourceProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

/**
 * 书阁（xbookcn.app）—— po18 系中文成人小说站。
 *
 * 站点是 React Router 服务端渲染：搜索 `/search?q=`、详情 `/book/{id}`、正文
 * `/book/{id}/{n}` 都能直接拿完整 HTML，不需要 JS 运行时，也没有付费墙。
 * 作品 id 是 12 位十六进制；`/book/{id}` 会 302 到带 slug 的规范地址，
 * 因此书源只保存 id，标题改了也不影响旧记录。
 */
class XbookcnSource(
    context: Context,
    private val baseUrl: String = BASE_URL,
    client: OkHttpClient? = null
) : ComicSource, UpdatableNovelSource {

    override val id: String = SOURCE_ID
    override val name: String = "书阁"

    // 纯文字站：在线阅读与整本 EPUB 下载都可用，没有图片章节。
    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportSearch = true,
        supportDownload = true,
        supportEbook = true,
        supportOnlineText = true
    )

    private val appContext: Context = context.applicationContext

    private val http: OkHttpClient = client ?: JsSourceProxy.failoverClient(
        context.applicationContext,
        com.example.source.SharedHttpTransport.builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    )

    /** 详情与目录通常连续调用，60 秒内复用同一页 HTML。 */
    private val pageCache = LruCache<String, Pair<Long, String>>(8)

    override suspend fun getShareUrl(bookId: String): String = bookUrl(bookId)

    override suspend fun search(keyword: String): SourceResult<List<SearchBook>> = result {
        val query = keyword.trim()
        if (query.isEmpty()) return@result emptyList()
        val path = "/search?q=" + java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        XbookcnParser.books(fetchHtml(path), id)
    }

    override suspend fun getDetail(bookId: String): SourceResult<SearchBook> = result {
        val html = fetchBook(bookId)
        XbookcnParser.detail(html, bookId, id)
    }

    override suspend fun refreshNovelDetail(bookId: String): SourceResult<SearchBook> = getDetail(bookId)

    override suspend fun getChapters(bookId: String): SourceResult<List<ComicChapter>> = result {
        XbookcnParser.chapters(fetchBook(bookId), bookId)
    }

    override suspend fun getChapterText(chapterId: String): SourceResult<String> = result {
        val (bookId, number) = parseChapterId(chapterId)
        val html = fetchHtml("/book/$bookId/$number")
        XbookcnParser.chapterText(html)
    }

    override suspend fun getChapterImages(chapterId: String): SourceResult<List<String>> =
        SourceResult.Error(SourceException.ParseError("书阁是文字小说源，请使用小说阅读器"))

    override suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo> = result {
        requireBookId(bookId)
        val title = try {
            XbookcnParser.detail(fetchBook(bookId), bookId, id).title
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            bookId
        }
        val fileName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { bookId }
        DownloadInfo(
            url = "$baseUrl/book/$bookId#$EPUB_MARKER",
            fileName = "$fileName.epub",
            format = "epub",
            referer = "$baseUrl/"
        )
    }

    override suspend fun login(credential: LoginCredential): SourceResult<Boolean> =
        SourceResult.Success(true)

    override suspend fun logout() = Unit

    override suspend fun isLoggedIn(): Boolean = true

    override suspend fun getAuthenticationState(): AuthenticationState =
        AuthenticationState.NotRequired

    private suspend fun fetchBook(bookId: String): String {
        requireBookId(bookId)
        val now = System.currentTimeMillis()
        pageCache.get(bookId)?.takeIf { now - it.first < 60_000 }?.let { return it.second }
        return fetchHtml("/book/$bookId").also { pageCache.put(bookId, now to it) }
    }

    private suspend fun fetchHtml(path: String): String {
        var last: Exception? = null
        // 幂等 GET：TLS/连接抖动与 429/5xx 有界重试一次，404 与解析失败不重试。
        for (attempt in 0..1) {
            try {
                return fetchHtmlOnce(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SourceException.BookNotFound) {
                throw e
            } catch (e: Exception) {
                last = e
                val retryable = e is java.io.IOException
                if (!retryable || attempt == 1) {
                    if (e is RetryableStatus) {
                        throw SourceException.NetworkError("书阁返回 HTTP ${e.code}")
                    }
                    throw e
                }
                kotlinx.coroutines.delay(800)
            }
        }
        throw last ?: SourceException.NetworkError("书阁请求失败")
    }

    private class RetryableStatus(val code: Int) : java.io.IOException("书阁返回 HTTP $code")

    private suspend fun fetchHtmlOnce(path: String): String = withContext(Dispatchers.IO) {
        require(path.startsWith("/")) { "书源内部路径无效" }
        val url = (baseUrl + path).toHttpUrl()

        fun buildRequest(cookie: String?): Request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
            .header("Sec-Ch-Ua-Mobile", "?0")
            .header("Sec-Ch-Ua-Platform", "\"Windows\"")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Referer", "$baseUrl/")
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
            .build()

        fun read(response: okhttp3.Response): String = response.use { res ->
            if (res.code == 404) throw SourceException.BookNotFound
            if (res.code == 429 || res.code >= 500) throw RetryableStatus(res.code)
            if (!res.isSuccessful) throw SourceException.NetworkError("书阁返回 HTTP ${res.code}")
            if (res.request.url.host != url.host) {
                throw SourceException.NetworkError("书阁跳转到了站外地址")
            }
            res.body?.byteStream()?.use { it.readImportBytes(8 * 1024 * 1024).toString(Charsets.UTF_8) }
                ?: throw java.io.IOException("书阁返回空页面")
        }

        var html = read(http.newCall(buildRequest(null)).executeCancellable())
        var problem = blockedReason(html)
        if (problem != null) {
            // 真正的 Cloudflare 挑战/广告门：用真实 Chromium WebView 过一次盾，再把 Cookie 带回 OkHttp。
            val solved = runCatching {
                com.example.source.js.CfWebViewSolver.solveAndSync(appContext, url.toString(), USER_AGENT)
            }.getOrDefault(false)
            if (solved) {
                val cookie = com.example.source.js.JsCookieJar.cookieHeader(appContext, url.toString())
                if (cookie.isNotBlank()) {
                    html = read(http.newCall(buildRequest(cookie)).executeCancellable())
                    problem = blockedReason(html)
                }
            }
        }
        problem?.let { throw SourceException.ParseError(it) }
        html
    }

    /**
     * 本站正常页面也会被 Cloudflare 注入 `/cdn-cgi/challenge-platform/`，
     * 不能直接用 SourceResponse.problem 判定；先确认页面已有本站结构。
     */
    private fun blockedReason(html: String): String? {
        if (html.isBlank()) return "源站返回空响应，请稍后重试"
        val doc = Jsoup.parse(html)
        val hasContent = doc.selectFirst(
            "article.reading-text, #search-books, main header h1, section ol li a[href*='/book/']"
        ) != null
        if (hasContent) return null
        return SourceResponse.problem(html)
    }

    private suspend fun <T> result(block: suspend () -> T): SourceResult<T> =
        withContext(Dispatchers.IO) {
            try {
                SourceResult.Success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: SourceException) {
                SourceResult.Error(e)
            } catch (e: java.io.IOException) {
                SourceResult.Error(SourceException.NetworkError("书阁连接失败，请稍后重试", e))
            } catch (e: Exception) {
                SourceResult.Error(SourceException.ParseError(e.message ?: "书阁页面解析失败"))
            }
        }

    private fun bookUrl(bookId: String): String {
        requireBookId(bookId)
        return "$baseUrl/book/$bookId"
    }

    companion object {
        const val SOURCE_ID = "xbookcn"
        const val EPUB_MARKER = "ciallo-xbookcn-epub-v1"
        private const val BASE_URL = "https://xbookcn.app"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val BOOK_ID = Regex("^[0-9a-f]{6,16}$")
        private val CHAPTER_ID = Regex("^([0-9a-f]{6,16})/([0-9]{1,7})$")

        internal fun requireBookId(bookId: String) {
            if (!BOOK_ID.matches(bookId)) throw SourceException.ParseError("书阁作品标识无效")
        }

        internal fun parseChapterId(chapterId: String): Pair<String, Int> {
            val match = CHAPTER_ID.matchEntire(chapterId)
                ?: throw SourceException.ParseError("书阁章节标识无效")
            val number = match.groupValues[2].toIntOrNull()
            if (number == null || number !in 1..100_000) {
                throw SourceException.ParseError("书阁章节序号无效")
            }
            return match.groupValues[1] to number
        }
    }
}

/** 站点 HTML 到模型的无网络解析，单独拆出便于回归测试。 */
internal object XbookcnParser {
    private val BOOK_HREF = Regex("""/book/([0-9a-f]{6,16})(?:[-/?#]|$)""")
    private val CHAPTER_HREF = Regex("""/book/([0-9a-f]{6,16})[^/]*/([0-9]{1,7})(?:[?#]|$)""")
    private val CHAPTER_COUNT = Regex("""([0-9]+)\s*章""")
    private val WORD_COUNT = Regex("""([0-9]+(?:\.[0-9]+)?)\s*万字""")
    private val UPDATED_AT = Regex("""([0-9]{4}-[0-9]{2}-[0-9]{2})\s*更新""")

    fun books(html: String, sourceId: String): List<SearchBook> {
        val doc = Jsoup.parse(html)
        val container = doc.selectFirst("#search-books") ?: return emptyList()
        val books = mutableListOf<SearchBook>()
        for (item in container.select("li")) {
            val link = item.selectFirst("h3 a[href^=/book/]") ?: continue
            val bookId = BOOK_HREF.find(link.attr("href"))?.groupValues?.get(1) ?: continue
            val title = link.text().trim()
            if (title.isBlank()) continue
            val paragraphs = item.select("p")
            val author = paragraphs.getOrNull(0)?.text()?.trim().orEmpty()
            val intro = paragraphs.getOrNull(1)?.text()?.trim()?.takeIf { it.isNotBlank() }
            books += SearchBook(
                id = bookId,
                sourceId = sourceId,
                title = title,
                author = author.ifBlank { "未知作者" },
                description = intro,
                format = "epub",
                language = "中文"
            )
        }
        return books.distinctBy { it.id }
    }

    fun detail(html: String, bookId: String, sourceId: String): SearchBook {
        val doc = Jsoup.parse(html)
        val header = doc.selectFirst("main > header") ?: doc.selectFirst("header")
        val title = header?.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw SourceException.ParseError("书阁未返回作品标题，页面可能已变化")
        val author = header.select("a[href^=/author/]").firstOrNull()?.text()?.trim().orEmpty()
        val metaText = header.select("p").firstOrNull()?.text().orEmpty()
        val synopsis = header.selectFirst("p.mt-3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: header.select("p").firstOrNull {
                val text = it.text().trim()
                text.length >= 40 && !text.startsWith("标签")
            }?.text()?.trim()
        val tags = header.select("a[href*='/novels?tag=']").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        val category = header.select("a[href*='/novels?cat=']").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        val chapterCount = CHAPTER_COUNT.find(metaText)?.groupValues?.get(1)?.toIntOrNull()
        val wordCount = WORD_COUNT.find(metaText)?.groupValues?.get(1)?.let { "$it 万字" }
        val updatedAt = UPDATED_AT.find(metaText)?.groupValues?.get(1)
        val chapters = chapters(html, bookId)
        return SearchBook(
            id = bookId,
            sourceId = sourceId,
            title = title,
            author = author.ifBlank { "未知作者" },
            cover = null,
            description = synopsis,
            format = "epub",
            language = "中文",
            novelInfo = NovelInfo(
                synopsis = synopsis,
                status = null,
                category = category.joinToString("、").takeIf { it.isNotBlank() },
                latestChapter = chapters.lastOrNull()?.title,
                updatedAt = updatedAt,
                chapterCount = chapterCount ?: chapters.size.takeIf { it > 0 },
                wordCount = wordCount,
                tags = tags,
                translation = "中文",
                notice = "免登录公开正文；整本下载会逐章抓取并复核目录，任何一章失败都不会标记完成。"
            )
        )
    }

    fun chapters(html: String, bookId: String): List<ComicChapter> {
        val doc = Jsoup.parse(html)
        val section = doc.select("section").firstOrNull { section ->
            section.selectFirst("h2")?.text()?.trim()?.startsWith("目录") == true
        } ?: return emptyList()
        val chapters = mutableListOf<ComicChapter>()
        for (link in section.select("ol li a[href*=/book/]")) {
            val match = CHAPTER_HREF.find(link.attr("href")) ?: continue
            if (match.groupValues[1] != bookId) continue
            val number = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..100_000 } ?: continue
            val label = link.selectFirst("span.truncate")?.text()?.trim()
                ?.ifBlank { null }
                ?: link.select("span").lastOrNull()?.text()?.trim()?.ifBlank { null }
                ?: "第 $number 章"
            chapters += ComicChapter(
                id = "$bookId/$number",
                title = label,
                order = number.toFloat()
            )
        }
        return chapters.distinctBy { it.id }.sortedBy { it.order }
    }

    fun chapterText(html: String): String {
        val doc = Jsoup.parse(html)
        val article = doc.selectFirst("article.reading-text") ?: doc.selectFirst("article")
            ?: throw SourceException.ParseError("书阁未返回正文，页面可能已变化")
        val paragraphs = article.select("p").map { element: Element ->
            element.text().filter { it == '\n' || it == '\t' || it.code >= 32 }.trim()
        }.filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) {
            throw SourceException.ParseError("本章正文为空，可能已下架或需要登录")
        }
        val text = paragraphs.joinToString("\n\n")
        if (listOf("请登录后阅读", "請登入後閱讀", "购买本章", "購買本章", "本章未完").any(text::contains)) {
            throw SourceException.ParseError("该章节未完整公开，书源无法读取")
        }
        return text
    }
}
