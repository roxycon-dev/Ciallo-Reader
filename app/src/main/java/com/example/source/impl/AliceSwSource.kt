package com.example.source.impl

import android.content.Context
import com.example.discover.DiscoverCatalog
import com.example.discover.DiscoverPage
import com.example.discover.DiscoverQuery
import com.example.data.readImportBytes
import com.example.source.AuthenticationState
import com.example.source.ComicChapter
import com.example.source.ComicSource
import com.example.source.DetailLink
import com.example.source.DownloadInfo
import com.example.source.LoginCredential
import com.example.source.SearchBook
import com.example.source.SharedHttpTransport
import com.example.source.SourceCapabilities
import com.example.source.SourceException
import com.example.source.SourceResponse
import com.example.source.SourceResult
import com.example.source.UpdatableNovelSource
import com.example.source.executeCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 爱丽丝书屋（发布页 alicesw.net，线路 alicesw*.homes 等）—— 成人向免费小说创作站，
 * 2026-11 起为 Next.js 新站：搜索/详情/目录/正文全部走 /api/v1/… JSON 接口，页面作为
 * 兜底（见 [AliceSwParser] 的契约注释）。作品标识用内部 id，章节标识是 chapter_no。
 *
 * 站点同时运营多条线路，可达性随线路与网络变化，选路交给 [AliceSwEndpoints]：
 * 并行探活 → 首个真实站点线路入选 → 失败线路冷却。发布页（alicesw.net / alicesw.click /
 * alicesw.quest 等）只用于发现新域名，探活时会被明确排除。
 *
 * 该源没有整本文件可下，整本下载走 [com.example.download.AliceSwEpub]：逐章抓正文在
 * 本地打包 EPUB，任一章失败即整体作废（与书阁同一套约定）。
 */
class AliceSwSource(
    context: Context,
    private val endpoints: AliceSwEndpoints = AliceSwEndpoints(context),
    client: OkHttpClient? = null
) : ComicSource, UpdatableNovelSource {

    override val id: String = SOURCE_ID
    override val name: String = "爱丽丝书屋"

    // 纯文字站：在线阅读与整本 EPUB 打包都可用，没有图片章节，不参与漫画分类。
    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportSearch = true,
        supportDownload = true,
        supportEbook = true,
        supportOnlineText = true
    )

    private val http: OkHttpClient = client ?: SharedHttpTransport.builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val catalogMutex = Mutex()

    @Volatile
    private var catalogCache: Pair<String, DiscoverCatalog>? = null

    override suspend fun search(keyword: String): SourceResult<List<SearchBook>> = result {
        val query = keyword.trim()
        if (query.isEmpty()) return@result emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val base = baseUrlOrThrow()
        val json = runCatching { JSONObject(fetch("$base/api/v1/search?q=$encoded&page_size=20")) }.getOrNull()
        if (json != null && json.optJSONObject("data") != null) {
            return@result AliceSwParser.searchFromApi(json, id, base)
        }
        // 接口不可用（改版/线路只挂了旧版页面）时退回搜索页 HTML
        AliceSwParser.booksFromHtml(fetch("/search?q=$encoded"), id, base)
    }

    override suspend fun getDetail(bookId: String): SourceResult<SearchBook> = result {
        val id = requireBookId(bookId)
        val base = baseUrlOrThrow()
        val json = fetch("$base/api/v1/books/$id")
        AliceSwParser.detailFromApi(JSONObject(json), this@AliceSwSource.id, base)
    }

    override suspend fun refreshNovelDetail(bookId: String): SourceResult<SearchBook> = getDetail(bookId)

    override suspend fun discoverCatalog(resolveDynamic: Boolean): SourceResult<DiscoverCatalog> = result {
        val base = baseUrlOrThrow()
        catalogFor(base, force = true)
    }

    override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> = result {
        val base = baseUrlOrThrow()
        val catalog = catalogFor(base, force = false)
        val target = AliceSwDiscover.route(base, query) ?: return@result AliceSwDiscover.notice()
        val raw = fetch(absoluteUrl(base, target.url))
        if (target.json) {
            val json = runCatching { JSONObject(raw) }.getOrNull()
                ?: throw SourceException.ParseError("爱丽丝书屋接口返回了网页而非数据")
            val data = json.optJSONObject("data")
                ?: throw SourceException.ParseError("爱丽丝书屋接口返回缺少数据")
            val entries = AliceSwDiscover.entriesFromApi(data, id, base, target.ranked, query.offset)
            val next = AliceSwDiscover.nextApiUrl(target.url, data)
            DiscoverPage(
                entries = entries,
                hasMore = next != null,
                nextCursor = next,
                catalog = catalog,
                native = true,
                notice = if (entries.isEmpty()) "源站没有返回这个栏目的作品" else null
            )
        } else {
            val entries = AliceSwDiscover.entriesFromHtml(raw, id, base, target.ranked, query.offset)
            val next = AliceSwDiscover.nextPageHtml(raw, target.url)
            DiscoverPage(
                entries = entries,
                hasMore = next != null,
                nextCursor = next,
                catalog = catalog,
                native = true,
                notice = if (entries.isEmpty()) "源站没有返回这个栏目的作品" else null
            )
        }
    }

    /** 分类目录来自 /api/v1/categories；10 分钟内复用，避免每次切栏目都重拉。 */
    private suspend fun catalogFor(base: String, force: Boolean): DiscoverCatalog = catalogMutex.withLock {
        val cached = catalogCache
        if (!force && cached != null && cached.first == base &&
            System.currentTimeMillis() - catalogFetchedAt < CATALOG_TTL_MS
        ) return@withLock cached.second
        val categoriesJson = runCatching { fetch("$base/api/v1/categories") }.getOrNull()
        val catalog = AliceSwDiscover.catalog(categoriesJson)
        catalogCache = base to catalog
        catalogFetchedAt = System.currentTimeMillis()
        catalog
    }

    override suspend fun getChapters(bookId: String): SourceResult<List<ComicChapter>> = result {
        val id = requireBookId(bookId)
        val base = baseUrlOrThrow()
        val json = runCatching { JSONObject(fetch("$base/api/v1/books/$id/chapters")) }.getOrNull()
        if (json != null && json.optJSONArray("data") != null) {
            return@result AliceSwParser.chaptersFromApi(json, this@AliceSwSource.id)
        }
        throw SourceException.ParseError("爱丽丝书屋目录接口不可用，请稍后重试")
    }

    override suspend fun getChapterText(chapterId: String): SourceResult<String> = result {
        val id = requireChapterId(chapterId)
        val base = baseUrlOrThrow()
        val json = runCatching { JSONObject(fetch("$base/api/v1/chapters/$id")) }.getOrNull()
        if (json != null && json.optJSONObject("data") != null) {
            return@result AliceSwParser.chapterTextFromApi(json)
        }
        // 接口不可用时退回服务端渲染正文页（/read/{chapter_no}）
        AliceSwParser.chapterTextFromHtml(fetch("/read/$id"))
    }

    override suspend fun getChapterImages(chapterId: String): SourceResult<List<String>> =
        SourceResult.Error(SourceException.ParseError("爱丽丝书屋是文字小说源，请使用小说阅读器"))

    /** 站点没有整本文件：返回带标记的地址，由下载器逐章抓取后在本地打包 EPUB。 */
    override suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo> = result {
        val id = requireBookId(bookId)
        val base = baseUrlOrThrow()
        val title = when (val detail = getDetail(id)) {
            is SourceResult.Success -> detail.data.title
            is SourceResult.Error -> id
        }
        val fileName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { id }
        DownloadInfo(
            url = "$base/ciallo-epub/$id#$EPUB_MARKER",
            fileName = "$fileName.epub",
            format = "epub",
            referer = "$base/"
        )
    }

    override suspend fun getShareUrl(bookId: String): String? {
        if (!AliceSwParser.isBookId(bookId)) return null
        val base = endpoints.currentBase() ?: runCatching { endpoints.baseUrl() }.getOrNull() ?: return null
        val bookNo = AliceSwParser.bookNoFor(bookId) ?: runCatching {
            (getDetail(bookId) as? SourceResult.Success)?.let { AliceSwParser.bookNoFor(bookId) }
        }.getOrNull() ?: return null
        return DetailLink.valid("$base/books/$bookNo")
    }

    override suspend fun login(credential: LoginCredential): SourceResult<Boolean> =
        SourceResult.Success(true)

    override suspend fun logout() = Unit

    override suspend fun isLoggedIn(): Boolean = true

    override suspend fun getAuthenticationState(): AuthenticationState =
        AuthenticationState.NotRequired

    private suspend fun baseUrlOrThrow(): String =
        endpoints.baseUrl() ?: throw SourceException.NetworkError("爱丽丝书屋当前所有线路都无法连接，请稍后重试")

    /**
     * 带线路切换的页面抓取：失败一次就换下一条线路重试一次。
     * 404 与解析失败不重试——换线路不会让不存在的章节变成存在。
     */
    private suspend fun fetch(target: String): String {
        var last: Exception? = null
        for (attempt in 0..1) {
            val base = endpoints.baseUrl(forceRefresh = attempt > 0)
                ?: throw SourceException.NetworkError("爱丽丝书屋当前所有线路都无法连接，请稍后重试")
            try {
                return request(absoluteUrl(base, target), base)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SourceException.BookNotFound) {
                throw e
            } catch (e: SourceException.ParseError) {
                throw e
            } catch (e: Exception) {
                last = e
                endpoints.reportFailure(base)
            }
        }
        throw SourceException.NetworkError("爱丽丝书屋连接失败，请稍后重试", last)
    }

    /** 续页游标可能来自已失效的旧线路：只保留路径与查询参数，拉到当前线路上。 */
    private fun absoluteUrl(base: String, target: String): String {
        if (!target.startsWith("http")) return base + target
        val here = runCatching { base.toHttpUrl() }.getOrNull() ?: return target
        val wanted = runCatching { target.toHttpUrl() }.getOrNull() ?: return target
        if (wanted.host == here.host) return target
        return base + wanted.encodedPath + (wanted.encodedQuery?.let { "?$it" } ?: "")
    }

    private suspend fun request(url: String, base: String): String = withContext(Dispatchers.IO) {
        val target = url.toHttpUrl()
        val request = Request.Builder()
            .url(target)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json,text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", "$base/")
            .get()
            .build()
        http.newCall(request).executeCancellable().use { response ->
            if (response.code == 404) throw SourceException.BookNotFound
            if (!response.isSuccessful) {
                throw SourceException.NetworkError("爱丽丝书屋返回 HTTP ${response.code}")
            }
            if (response.request.url.host != target.host) {
                throw SourceException.NetworkError("爱丽丝书屋跳转到了站外地址")
            }
            val bytes = response.body?.byteStream()?.use { it.readImportBytes(8 * 1024 * 1024) }
                ?: throw java.io.IOException("爱丽丝书屋返回空响应")
            val body = bytes.toString(Charsets.UTF_8)
            SourceResponse.problem(body)?.let { throw SourceException.ParseError(it) }
            body
        }
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
                SourceResult.Error(SourceException.NetworkError("爱丽丝书屋连接失败，请稍后重试", e))
            } catch (e: Exception) {
                SourceResult.Error(SourceException.ParseError(e.message ?: "爱丽丝书屋页面解析失败"))
            }
        }

    private fun requireBookId(bookId: String): String {
        if (!AliceSwParser.isBookId(bookId)) throw SourceException.ParseError("爱丽丝书屋作品标识无效")
        return bookId
    }

    private fun requireChapterId(chapterId: String): String {
        if (!chapterId.matches(Regex("""\d{1,15}"""))) {
            throw SourceException.ParseError("爱丽丝书屋章节标识无效")
        }
        return chapterId
    }

    @Volatile
    private var catalogFetchedAt: Long = 0L

    companion object {
        const val SOURCE_ID = "alicesw"
        const val EPUB_MARKER = "ciallo-alicesw-epub-v1"
        private const val CATALOG_TTL_MS = 10 * 60 * 1000L

        internal const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
