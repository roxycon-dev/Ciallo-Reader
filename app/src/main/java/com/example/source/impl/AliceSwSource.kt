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
import java.util.concurrent.TimeUnit

/**
 * 爱丽丝书屋（alicesw.com）—— 成人向免费小说创作站，章节式在线正文，作品页免登录。
 *
 * 站点同时运营多条线路，不同线路在墙内可达性不一样（有的域名 TLS 握手直接被重置），
 * 所以选路交给 [AliceSwEndpoints]：并行探活 → 首个真实站点线路入选 → 失败线路冷却。
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
        AliceSwParser.books(fetch("/search?q=" + encoded), id)
    }

    override suspend fun getDetail(bookId: String): SourceResult<SearchBook> = result {
        val id = requireBookId(bookId)
        AliceSwParser.detail(fetch("/novel/$id.html"), id, this@AliceSwSource.id)
    }

    override suspend fun refreshNovelDetail(bookId: String): SourceResult<SearchBook> = getDetail(bookId)

    override suspend fun discoverCatalog(resolveDynamic: Boolean): SourceResult<DiscoverCatalog> = result {
        catalogFor(baseUrlOrThrow(), force = true)
    }

    override suspend fun discover(query: DiscoverQuery): SourceResult<DiscoverPage> = result {
        val base = baseUrlOrThrow()
        val catalog = catalogFor(base, force = false)
        val target = AliceSwDiscover.route(base, catalog, query) ?: return@result AliceSwDiscover.notice()
        val html = fetch(target)
        val ranked = AliceSwDiscover.isRanked(query)
        val entries = AliceSwDiscover.entries(html, id, ranked, query.offset)
        val next = if (ranked) null else AliceSwDiscover.nextPage(html, target)
        DiscoverPage(
            entries = entries,
            hasMore = next != null,
            nextCursor = next,
            catalog = catalog,
            native = true,
            notice = if (entries.isEmpty()) "源站没有返回这个栏目的作品" else null
        )
    }

    /** 分类/排行入口来自首页导航；10 分钟内复用，避免每次切栏目都重拉首页。 */
    private suspend fun catalogFor(base: String, force: Boolean): DiscoverCatalog = catalogMutex.withLock {
        val cached = catalogCache
        if (!force && cached != null && cached.first == base &&
            System.currentTimeMillis() - catalogFetchedAt < CATALOG_TTL_MS
        ) return@withLock cached.second
        val catalog = AliceSwDiscover.catalog(fetch("/"), base)
        catalogCache = base to catalog
        catalogFetchedAt = System.currentTimeMillis()
        catalog
    }

    override suspend fun getChapters(bookId: String): SourceResult<List<ComicChapter>> = result {
        val id = requireBookId(bookId)
        AliceSwParser.chapters(fetch("/other/chapters/id/$id.html"), this@AliceSwSource.id)
    }

    override suspend fun getChapterText(chapterId: String): SourceResult<String> = result {
        val (bookKey, hash) = AliceSwParser.parseChapterId(chapterId)
        AliceSwParser.chapterText(fetch("/book/$bookKey/$hash.html"))
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
            url = "$base/novel/$id.html#$EPUB_MARKER",
            fileName = "$fileName.epub",
            format = "epub",
            referer = "$base/"
        )
    }

    override suspend fun getShareUrl(bookId: String): String? {
        if (!AliceSwParser.isBookId(bookId)) return null
        val base = endpoints.currentBase() ?: runCatching { endpoints.baseUrl() }.getOrNull() ?: return null
        return DetailLink.valid("$base/novel/$bookId.html")
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
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
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
                ?: throw java.io.IOException("爱丽丝书屋返回空页面")
            val html = bytes.toString(Charsets.UTF_8)
            SourceResponse.problem(html)?.let { throw SourceException.ParseError(it) }
            html
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
