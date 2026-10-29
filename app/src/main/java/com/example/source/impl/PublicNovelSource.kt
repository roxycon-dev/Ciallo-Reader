package com.example.source.impl

import android.content.Context
import com.example.source.*
import com.example.source.js.JsSourceProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Only individually verified, public guest sites belong in this registry. */
object PublicNovelSources {
    // CZBooks failed its live whole-book audit (403); it is deliberately not published.
    val ids = setOf("quanben", "ihuaben", "sfacg", "fanqie")
    fun create(context: Context, id: String): PublicNovelSource? = if (id !in ids) null else when (id) {
        "czbooks" -> PublicNovelSource(context, PublicNovelSite.CZBOOKS)
        "quanben" -> PublicNovelSource(context, PublicNovelSite.QUANBEN)
        "ihuaben" -> PublicNovelSource(context, PublicNovelSite.IHUABEN)
        "sfacg" -> PublicNovelSource(context, PublicNovelSite.SFACG)
        "fanqie" -> PublicNovelSource(context, PublicNovelSite.FANQIE)
        "linovel" -> PublicNovelSource(context, PublicNovelSite.LINOVEL)
        else -> null
    }
    fun all(context: Context) = ids.mapNotNull { create(context, it) }
}

enum class PublicNovelSite(val sourceId: String, val label: String, val home: String) {
    CZBOOKS("czbooks", "小说狂人", "https://czbooks.net/"),
    QUANBEN("quanben", "全本小说网", "https://www.quanben.io/"),
    IHUABEN("ihuaben", "话本小说", "https://www.ihuaben.com/"),
    SFACG("sfacg", "SF轻小说", "https://m.sfacg.com/"),
    LINOVEL("linovel", "轻之文库", "https://www.linovel.net/"),
    FANQIE("fanqie", "番茄小说（公开聚合）", "https://fanqienovel.com/")
}

/** No account, credential store, or browser cookies are used by this adapter. */
class PublicNovelSource(
    context: Context,
    val site: PublicNovelSite,
    private val baseUrl: HttpUrl = site.home.toHttpUrl(),
    client: OkHttpClient? = null
) : ComicSource, UpdatableNovelSource {
    override val id = site.sourceId
    override val name = site.label
    // Chapter APIs are used to assemble the EPUB. Expose offline reading, which preserves
    // illustrations, rather than advertising the plain-text preview as a complete reader.
    override suspend fun getCoverHeaders(url: String) = mapOf("Referer" to baseUrl.toString())
    override val capabilities = SourceCapabilities(supportEbook = true, supportOnlineText = site == PublicNovelSite.FANQIE)
    internal val http = client ?: JsSourceProxy.failoverClient(context.applicationContext,
        SharedHttpTransport.builder().cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS).build())
    private val fanqie by lazy { FanqieGateway(http) }

    private fun validId(id: String): String = id.also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "小说标识无效" }
    }
    internal fun bookUrl(bookId: String): HttpUrl = if (site == PublicNovelSite.FANQIE)
        baseUrl.newBuilder().addPathSegment("page").addPathSegment(validId(bookId)).build()
        else if (site == PublicNovelSite.SFACG)
        baseUrl.newBuilder().addPathSegment("b").addPathSegment(validId(bookId)).addPathSegment("").build()
        else if (site == PublicNovelSite.IHUABEN || site == PublicNovelSite.LINOVEL)
        baseUrl.newBuilder().addPathSegment("book").addPathSegment(validId(bookId) + ".html").build()
        else baseUrl.newBuilder().addPathSegment("n").addPathSegment(validId(bookId))
            .apply { if (site == PublicNovelSite.QUANBEN) addPathSegment("") }.build()
    internal fun bookId(url: HttpUrl): String {
        require(url.host == baseUrl.host && url.scheme == baseUrl.scheme) { "小说地址不属于当前源" }
        val pattern = when (site) {
            PublicNovelSite.FANQIE -> "^/page/([0-9]{18,20})$"
            PublicNovelSite.IHUABEN, PublicNovelSite.LINOVEL -> "^/book/([0-9]+)\\.html$"
            PublicNovelSite.SFACG -> "^/b/([0-9]+)/?$"
            else -> "^/n/([A-Za-z0-9_-]+)/?$"
        }
        return validId(Regex(pattern).matchEntire(url.encodedPath)?.groupValues?.get(1)
            ?: error("小说下载地址无效"))
    }
    override suspend fun getShareUrl(bookId: String) = bookUrl(bookId).toString()

    internal suspend fun page(url: HttpUrl): Document {
        repeat(3) { attempt ->
            try { return pageOnce(url) }
            catch (e: java.io.IOException) {
                currentCoroutineContext().ensureActive()
                if (attempt == 2) throw e
                delay((attempt + 1) * 1000L)
            }
        }
        error("源站读取失败")
    }
    private suspend fun pageOnce(url: HttpUrl, userAgent: String = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/119.0.0.0 Mobile Safari/537.36"): Document = withContext(Dispatchers.IO) {
        val hosts = setOf(baseUrl.host) + when (site) {
            PublicNovelSite.IHUABEN -> setOf("so.ihuaben.com")
            PublicNovelSite.SFACG -> setOf("s.sfacg.com", "book.sfacg.com")
            else -> emptySet()
        }
        require(url.host in hosts && url.scheme == baseUrl.scheme) { "源站页面地址异常" }
        val request = Request.Builder().url(url).header("User-Agent", userAgent)
            .header("Referer", baseUrl.toString()).header("Accept", "text/html").build()
        http.newCall(request).executeCancellable().use { response ->
            require(response.isSuccessful) { "$name 暂时无法免登录访问（HTTP ${response.code}）" }
            require(response.request.url.host in hosts) { "源站跳转到了其他网站" }
            val html = response.readSourceText(6 * 1024 * 1024)
            SourceResponse.problem(html)?.let { error(it) }
            require(!html.contains('\uFFFD')) { "源站文字编码异常，未导入不完整文本" }
            val doc = Jsoup.parse(html, url.toString())
            require(!doc.title().contains("找不到") && !doc.title().contains("Redirecting", true)) { "源站未返回小说页面" }
            doc
        }
    }
    private suspend fun <T> result(block: suspend () -> T): SourceResult<T> = withContext(Dispatchers.IO) {
        try { SourceResult.Success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: SourceException) { SourceResult.Error(e) }
        catch (e: java.io.IOException) { SourceResult.Error(SourceException.NetworkError(e.message ?: "$name 网络中断", e)) }
        catch (e: Exception) { SourceResult.Error(SourceException.ParseError(e.message ?: "$name 读取失败")) }
    }
    private fun meta(doc: Document, key: String) = doc.selectFirst("meta[property='$key']")?.attr("content").orEmpty()

    override suspend fun search(keyword: String) = result {
        if (keyword.isBlank()) return@result emptyList<SearchBook>()
        if (site == PublicNovelSite.FANQIE) return@result fanqie.search(keyword)
        val url = if (site == PublicNovelSite.CZBOOKS) baseUrl.newBuilder().addPathSegment("s").addPathSegment(keyword.trim()).build()
            else if (site == PublicNovelSite.IHUABEN) "https://so.ihuaben.com/search".toHttpUrl().newBuilder().addQueryParameter("keyword", keyword.trim()).build()
            else if (site == PublicNovelSite.SFACG) "https://s.sfacg.com/".toHttpUrl().newBuilder().addQueryParameter("Key", keyword.trim()).addQueryParameter("S", "1").build()
            else if (site == PublicNovelSite.LINOVEL) baseUrl.newBuilder().addPathSegment("search").addPathSegment("").addQueryParameter("kw", keyword.trim()).build()
            else baseUrl.newBuilder().addPathSegment("index.php").addQueryParameter("c", "book")
                .addQueryParameter("a", "search").addQueryParameter("keywords", keyword.trim()).build()
        val doc = page(url)
        if (site == PublicNovelSite.SFACG) {
            return@result doc.select("a[href*='book.sfacg.com/Novel/']").mapNotNull { a ->
                val resource = Regex("/Novel/([0-9]+)").find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
                if (a.text().isBlank()) return@mapNotNull null
                SearchBook(resource, id, a.text(), "", format = "epub", language = "中文",
                    novelInfo = NovelInfo(translation = "中文原创", notice = "只支持整本公开作品；目录含 VIP 或锁定章节时会拒绝整本下载。"))
            }.distinctBy { it.id }
        }
        if (site == PublicNovelSite.LINOVEL) {
            return@result doc.select("a.search-book[href]").mapNotNull { a ->
                val resource = baseUrl.resolve(a.attr("href"))?.let { runCatching { bookId(it) }.getOrNull() } ?: return@mapNotNull null
                val title = a.selectFirst(".book-title")?.text().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                SearchBook(resource, id, title, a.selectFirst(".desc p")?.text().orEmpty().removeSuffix("/ 著").trim(),
                    cover = a.selectFirst("img[src]")?.absUrl("src"), format = "epub", language = "中文")
            }.distinctBy { it.id }
        }
        val cards = when (site) {
            PublicNovelSite.CZBOOKS -> doc.select(".novel-list .novel-item")
            PublicNovelSite.IHUABEN -> doc.select(".searchresult")
            else -> doc.select(".list2")
        }
        require(cards.isNotEmpty() || doc.text().contains("没有") || doc.text().contains("沒有") || doc.text().contains("找不到")) {
            "源站搜索结果格式变化，请稍后重试"
        }
        cards.mapNotNull { card ->
            val a = card.selectFirst(when (site) {
                PublicNovelSite.CZBOOKS -> ".novel-item-title a, .novel-item-thumbnail a, a[href*=/n/]"
                PublicNovelSite.IHUABEN -> "h2 a[href]"
                else -> "h3 a[href]"
            })
                ?: return@mapNotNull null
            val link = baseUrl.resolve(a.attr("href")) ?: return@mapNotNull null
            val resource = runCatching { bookId(link) }.getOrNull() ?: return@mapNotNull null
            val title = if (site == PublicNovelSite.CZBOOKS) card.selectFirst(".novel-item-title")?.text().orEmpty() else a.text()
            if (title.isBlank()) return@mapNotNull null
            SearchBook(resource, id, title,
                card.selectFirst(when (site) {
                    PublicNovelSite.CZBOOKS -> ".novel-item-author a"
                    PublicNovelSite.IHUABEN -> "p.text-muted a"
                    else -> "p.author, .author"
                })?.text().orEmpty(),
                cover = if (site == PublicNovelSite.IHUABEN) null else card.selectFirst("img[src]")?.absUrl("src"), format = "epub", language = "中文",
                novelInfo = NovelInfo(latestChapter = card.selectFirst(".novel-item-newest-chapter a")?.text(),
                    status = card.selectFirst(".novel-item-state")?.text(), translation = "中文", notice = "免登录公开正文；下载会核对全部目录，任何一章失败均不会标为完成。"))
        }.distinctBy { it.id }
    }

    private val discovery by lazy { HtmlNovelDiscovery(id, baseUrl, { url ->
        if (site == PublicNovelSite.SFACG && url == baseUrl) {
            // The mobile root currently serves a soft 404. The desktop site's public
            // recommendation page has the same numeric identities as /b/{id}/.
            page("https://book.sfacg.com/".toHttpUrl()).also { doc ->
                doc.select("a[href]").forEach { a ->
                    val u = "https://book.sfacg.com/".toHttpUrl().resolve(a.attr("href"))
                    if (u?.host == "book.sfacg.com") Regex("^/Novel/([0-9]+)/?$").matchEntire(u.encodedPath)?.let {
                        a.attr("href", bookUrl(it.groupValues[1]).toString())
                    }
                }
            }
        } else page(url)
    }, { url ->
        runCatching { bookId(url) }.getOrNull()
    }) }
    private val fanqieDiscovery by lazy { FanqieDiscovery(baseUrl, http) { url -> guestNovelIoRetry { pageOnce(url, "Mozilla/5.0") } } }
    override suspend fun discoverCatalog(resolveDynamic: Boolean) = result {
        if (site == PublicNovelSite.FANQIE) fanqieDiscovery.catalog() else discovery.catalog()
    }
    override suspend fun discover(query: com.example.discover.DiscoverQuery) = result {
        if (site == PublicNovelSite.FANQIE) fanqieDiscovery.discover(query) else discovery.discover(query)
    }

    override suspend fun getDetail(bookId: String) = result { detail(bookId) }
    override suspend fun refreshNovelDetail(bookId: String) = result { detail(bookId) }
    private suspend fun detail(bookId: String): SearchBook {
        if (site == PublicNovelSite.FANQIE) return fanqie.detail(bookId)
        val doc = page(bookUrl(bookId))
        val title: String
        val author: String
        val synopsis: String
        if (site == PublicNovelSite.CZBOOKS) {
            title = doc.selectFirst(".novel-detail .title")?.ownText().orEmpty().trim().removeSurrounding("《", "》")
            author = doc.selectFirst(".novel-detail .author a")?.text().orEmpty()
            synopsis = doc.selectFirst(".novel-detail .description")?.text().orEmpty()
        } else if (site == PublicNovelSite.LINOVEL) {
            title = doc.selectFirst("h1.book-title")?.text().orEmpty()
            author = doc.selectFirst(".book-author")?.text().orEmpty().removeSuffix("/ 著").trim()
            synopsis = doc.selectFirst(".book-intro")?.text().orEmpty()
        } else if (site == PublicNovelSite.SFACG) {
            title = doc.selectFirst(".book_newtitle")?.text().orEmpty()
            author = doc.selectFirst(".book_info3")?.ownText().orEmpty().substringBefore('/')
            synopsis = doc.selectFirst(".book_Profile, .book_profile")?.text().orEmpty()
        } else if (site == PublicNovelSite.IHUABEN) {
            title = doc.selectFirst("h1")?.text().orEmpty()
            author = doc.select("a[href*=user.ihuaben.com]").firstOrNull {
                it.text().isNotBlank() && Regex("/[0-9]+/?$").containsMatchIn(it.attr("href"))
            }?.text().orEmpty()
            synopsis = doc.selectFirst(".bookintro, .book-intro, .bookIntro")?.text().orEmpty()
        } else {
            title = meta(doc, "og:novel:book_name").ifBlank { doc.selectFirst("h1")?.text().orEmpty() }
            author = meta(doc, "og:novel:author")
            synopsis = doc.selectFirst(".description")?.text().orEmpty()
        }
        require(title.isNotBlank()) { "未找到小说标题" }
        val toc = if (site == PublicNovelSite.CZBOOKS) parseChapters(bookId, doc) else chapters(bookId)
        return SearchBook(bookId, id, title, author, cover = meta(doc, "og:image").takeIf { it.isNotBlank() },
            description = synopsis, format = "epub", language = "中文", novelInfo = NovelInfo(synopsis = synopsis,
                status = meta(doc, "og:novel:status").takeIf { it.isNotBlank() }
                    ?: doc.selectFirst(".novel-detail .state")?.text(),
                chapterCount = toc.size, latestChapter = toc.last().title, translation = "中文",
                notice = "整本下载包含源站本次公开目录的全部章节；源站缺章或更新滞后无法由客户端补齐。"))
    }
    override suspend fun getChapters(bookId: String) = result { chapters(bookId) }
    internal suspend fun chapters(bookId: String): List<ComicChapter> {
        if (site == PublicNovelSite.FANQIE) return fanqie.chapters(bookId)
        if (site == PublicNovelSite.LINOVEL) {
            val doc = page(bookUrl(bookId))
            val volumes = doc.select(".catalog-list .volume-wrp")
            require(volumes.isNotEmpty()) { "轻之文库未返回完整分卷目录" }
            val numbered = volumes.map { volume ->
                val title = volume.selectFirst(".volume .title")?.text().orEmpty()
                val number = Regex("^第([0-9]+)卷").find(title)?.groupValues?.get(1)?.toIntOrNull()
                    ?: error("分卷顺序无法确认，未下载乱序小说")
                Triple(number, title, volume)
            }
            require(numbered.map { it.first }.distinct().size == numbered.size) { "分卷编号重复，无法确认顺序" }
            val chapters = numbered.sortedBy { it.first }.flatMap { (_, volumeTitle, volume) ->
                volume.select(".chapter-list a[href]").map { a ->
                    val link = baseUrl.resolve(a.attr("href")) ?: error("章节地址无效")
                    require(link.host == baseUrl.host && Regex("^/book/$bookId/[0-9]+\\.html$").matches(link.encodedPath)) { "轻之文库章节归属异常" }
                    require(a.select(".icon-lock, .vip").isEmpty() && a.text().isNotBlank() && !a.text().contains("删掉了")) {
                        "源站目录含锁定、删除或无标题章节，不能完整下载"
                    }
                    ComicChapter(link.toString(), a.ownText(), volume = volumeTitle, order = 0f)
                }
            }
            require(chapters.size in 1..30_000 && chapters.map { it.id }.distinct().size == chapters.size) { "轻之文库目录为空或重复" }
            return chapters.mapIndexed { i, c -> c.copy(order = i.toFloat()) }
        }
        if (site == PublicNovelSite.SFACG) {
            val doc = page(baseUrl.newBuilder().addPathSegment("i").addPathSegment(validId(bookId)).addPathSegment("").build())
            val links = doc.select(".mulu_list a[href]")
            require(links.isNotEmpty()) { "SF 未返回完整目录" }
            require(links.none { it.select(".icon-lock2, .icon-lock, .vip").isNotEmpty() }) {
                "此书含 VIP 或锁定章节，不能免登录完整下载；请另选全本公开作品"
            }
            return links.mapIndexed { i, a ->
                val link = baseUrl.resolve(a.attr("href")) ?: error("章节地址无效")
                require(link.host == baseUrl.host && Regex("^/c/[0-9]+/$").matches(link.encodedPath) && a.text().isNotBlank()) { "SF 目录结构变化" }
                ComicChapter(link.toString(), a.text(), order = i.toFloat())
            }.also { require(it.size <= 30_000 && it.map { c -> c.id }.distinct().size == it.size) { "SF 目录异常" } }
        }
        if (site == PublicNovelSite.IHUABEN) {
            val url = baseUrl.newBuilder().addPathSegments("book/chapters").addPathSegment(validId(bookId)).build()
            val request = Request.Builder().url(url).header("Referer", bookUrl(bookId).toString()).header("User-Agent", "Mozilla/5.0").build()
            val data = http.newCall(request).executeCancellable().use { response ->
                require(response.isSuccessful) { "话本目录请求失败（HTTP ${response.code}）" }
                SourceResponse.jsonObject(response.readSourceText(6 * 1024 * 1024))
            }
            require(data.optInt("code", -1) == 0) { "话本未返回公开目录" }
            val items = data.getJSONArray("chapters")
            require(items.length() in 1..30_000) { "话本目录为空或过大" }
            return (0 until items.length()).map { i ->
                val item = items.getJSONObject(i)
                require(item.optString("chapterType") == "FREE" && item.optString("status") == "PUBLISHED") {
                    "此书含付费或非公开章节，不能免登录完整下载；请另选全本公开作品"
                }
                require(item.optString("bookId") == bookId) { "话本目录归属异常" }
                val chapter = item.getLong("chapterId")
                require(chapter > 0 && item.optString("title").isNotBlank()) { "章节标识或标题为空" }
                ComicChapter(baseUrl.resolve("book/$bookId/$chapter.html")!!.toString(), item.getString("title"), order = i.toFloat())
            }.also { require(it.map { c -> c.id }.distinct().size == it.size) { "话本目录有重复章节地址" } }
        }
        val url = if (site == PublicNovelSite.QUANBEN) bookUrl(bookId).newBuilder().addPathSegment("list.html").build() else bookUrl(bookId)
        return parseChapters(bookId, page(url))
    }
    private fun parseChapters(bookId: String, doc: Document): List<ComicChapter> {
        val root = doc.selectFirst(if (site == PublicNovelSite.CZBOOKS) "#chapter-list" else ".list3")
            ?: error("源站未返回完整章节目录")
        val chapters = root.select("a[href]").mapNotNull { a ->
            val url = baseUrl.resolve(a.attr("href")) ?: return@mapNotNull null
            if (url.host != baseUrl.host || !url.encodedPath.startsWith("/n/${validId(bookId)}/") || a.text().isBlank()) return@mapNotNull null
            require(!a.text().contains("[锁]") && !a.hasClass("vip")) { "此书含非公开章节，不能免登录整本下载" }
            ComicChapter(url.toString(), a.text(), order = 0f)
        }
        require(chapters.isNotEmpty() && chapters.map { it.id }.distinct().size == chapters.size) { "小说目录为空或存在重复章节地址" }
        require(chapters.size <= 30_000) { "小说目录过大" }
        return chapters.mapIndexed { index, c -> c.copy(order = index.toFloat()) }
    }

    internal suspend fun chapterParts(chapterId: String): List<NovelPart> {
        if (site == PublicNovelSite.FANQIE) return fanqie.parts(chapterId)
        val url = chapterId.toHttpUrl()
        val doc = page(url)
        val root = doc.selectFirst(when (site) {
            PublicNovelSite.CZBOOKS -> ".content"
            PublicNovelSite.IHUABEN -> "#contentsource[data-ssr-read=true]"
            PublicNovelSite.SFACG -> ".yuedu"
            PublicNovelSite.LINOVEL -> "#readerArticle"
            else -> "#content"
        })
            ?: error("未找到章节正文，不能保存空章")
        val copy = root.clone()
        copy.select("script, style, noscript, #ad, ins, iframe").remove()
        if (site == PublicNovelSite.IHUABEN) copy.select(".avatar, .num, .passage-cosplay-preview").remove()
        require(copy.select("canvas, svg, video, audio").isEmpty()) { "章节包含尚不支持的正文格式，未生成残缺下载" }
        val parts = extractParts(copy)
        validateParts(parts)
        // Do not silently accept a teaser in place of a chapter.
        val text = parts.filterIsInstance<NovelPart.Text>().joinToString("\n") { it.value }
        require(!hasMissingNovelContent(text) && listOf("请登录后阅读", "請登入後閱讀", "购买本章", "購買本章", "本章未完").none { text.contains(it) }) {
            "章节未完整公开，下载已停止"
        }
        require(doc.select("a[href]").none { a ->
            (a.text().trim() in setOf("下一頁", "下一页", "继续阅读", "繼續閱讀")) &&
                a.absUrl("href").let { target -> target.contains(url.encodedPath.substringBeforeLast('/') + "/") &&
                    Regex("_[0-9]+\\.html(?:$|[?#])").containsMatchIn(target) }
        }) { "检测到分页正文，未将不完整章节保存为完成" }
        return parts
    }
    override suspend fun getChapterText(chapterId: String) = result {
        chapterParts(chapterId).joinToString("\n\n") { when (it) {
            is NovelPart.Text -> it.value
            is NovelPart.Image -> "[插图] ${it.url}"
        } }
    }
    override suspend fun getDownloadInfo(bookId: String) = result {
        validId(bookId)
        DownloadInfo(bookUrl(bookId).newBuilder().fragment("ciallo-public-epub-v1").build().toString(), "$bookId.epub", "epub")
    }
    override suspend fun getChapterImages(chapterId: String): SourceResult<List<String>> = SourceResult.Error(SourceException.ParseError("请使用小说阅读器"))
    override suspend fun login(credential: LoginCredential) = SourceResult.Success(true)
    override suspend fun logout() = Unit
    override suspend fun isLoggedIn() = true
}

internal sealed class NovelPart {
    data class Text(val value: String) : NovelPart()
    data class Image(val url: String) : NovelPart()
}

/** Walk text nodes and line boundaries, preserving inline spans, Unicode and illustration order. */
internal fun extractParts(root: Element): List<NovelPart> {
    val parts = mutableListOf<NovelPart>()
    val line = StringBuilder()
    fun flush() { line.toString().trim().takeIf { it.isNotEmpty() }?.let { parts += NovelPart.Text(it) }; line.setLength(0) }
    fun walk(node: Node) {
        when (node) {
            is TextNode -> line.append(node.wholeText)
            is Element -> when (node.normalName()) {
                "br" -> flush()
                "img" -> {
                    flush()
                    val url = node.absUrl("data-original").ifBlank { node.absUrl("data-src") }.ifBlank { node.absUrl("src") }
                    require(url.startsWith("https://") || url.startsWith("http://")) { "插图地址不可用" }
                    parts += NovelPart.Image(url)
                }
                else -> {
                    if (node.isBlock) flush()
                    node.childNodes().forEach(::walk)
                    if (node.isBlock) flush()
                }
            }
        }
    }
    walk(root); flush()
    return parts
}

internal fun validateParts(parts: List<NovelPart>) {
    require(parts.isNotEmpty()) { "源站章节为空" }
    parts.filterIsInstance<NovelPart.Text>().forEach { part ->
        require(part.value.none { it == '\uFFFD' || it == '\u0000' || it in '\uE000'..'\uF8FF' || (it.code < 32 && !it.isWhitespace()) }) {
            "正文包含乱码或加密字形，未保存损坏文本"
        }
    }
}
