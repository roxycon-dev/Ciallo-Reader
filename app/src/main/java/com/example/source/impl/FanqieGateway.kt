package com.example.source.impl

import com.example.source.*
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** Anonymous public catalogues; chapter text is supplied by the named third-party API. */
internal class FanqieGateway(private val http: OkHttpClient) {
    private val notice = "搜索与目录来自番茄公开接口，书荒殿补充检索；正文由张老师API提供。接口可能缺章或停止服务，更新与覆盖以实际返回为准。"
    private fun id(value: String) = value.also { require(it.matches(Regex("[0-9]{18,20}"))) { "番茄小说标识无效" } }
    private suspend fun json(url: HttpUrl): JSONObject = guestNovelIoRetry {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0").header("Referer", "https://${url.host}/").build()
        http.newCall(request).executeCancellable().use { response ->
            require(response.request.url.host == url.host && response.request.url.scheme == "https") { "番茄接口跳转地址异常" }
            if (!response.isSuccessful) throw SourceException.NetworkError("番茄接口返回 HTTP ${response.code}")
            val body = response.readSourceText(6 * 1024 * 1024)
            if (body.isBlank()) throw java.io.IOException("番茄接口暂时返回空响应")
            SourceResponse.jsonObject(body).also {
                // This gateway intermittently returns code -1 before the next public GET succeeds.
                if (it.optInt("code", -1) == -1) throw java.io.IOException("番茄接口暂时无法提供此内容，请稍后重试或换源")
                require(it.optInt("code", -1) in setOf(0, 200)) { "番茄接口暂时无法提供此内容，请稍后重试或换源" }
            }
        }
    }
    private fun api(action: String, parameter: String, value: String) = "https://biek.top/api/fq.php".toHttpUrl()
        .newBuilder().addQueryParameter("action", action).addQueryParameter(parameter, value).build()
    private fun array(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private fun text(data: JSONObject, vararg names: String): String = names.firstNotNullOfOrNull {
        (data.opt(it) as? String)?.takeIf(String::isNotBlank)
    }.orEmpty()
    private fun book(data: JSONObject, index: Boolean = false): SearchBook {
        val bookId = id(text(data, if (index) "bookId" else "book_id"))
        val title = Jsoup.parse(text(data, if (index) "bookTitle" else "title", "book_name")).text()
        require(title.isNotBlank() && !title.contains("***")) { "番茄未返回可用书名，作品可能已下架" }
        val intro = text(data, if (index) "bookIntro" else "abstract")
        val status = if (index) text(data, "bookStatus") else when (text(data, "creation_status")) {
            "0" -> "已完结"; "1" -> "连载中"; else -> "状态未知"
        }
        return SearchBook(bookId, "fanqie", title, text(data, if (index) "authorName" else "author"),
            cover = text(data, if (index) "coverImgUrl" else "thumb_url").takeIf(String::isNotBlank),
            format = "epub", language = "中文", description = intro,
            novelInfo = NovelInfo(synopsis = intro, status = status,
                category = text(data, if (index) "bookType" else "category").takeIf(String::isNotBlank),
                translation = "中文网文", notice = notice))
    }
    suspend fun search(keyword: String): List<SearchBook> {
        val query = keyword.trim()
        if (query.isEmpty()) return emptyList()
        // A shared link or numeric ID can open works omitted from the current search index.
        val direct = Regex("^(?:https://fanqienovel\\.com/page/)?([0-9]{18,20})/?(?:\\?.*)?$").matchEntire(query)
        if (direct != null) return listOf(detail(direct.groupValues[1]))
        val found = linkedMapOf<String, SearchBook>()
        var succeeded = false
        var failure: Exception? = null
        try {
            val url = "https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/".toHttpUrl().newBuilder()
                .addQueryParameter("device_platform", "android").addQueryParameter("parent_enterfrom", "novel_channel_search.tab.")
                .addQueryParameter("offset", "0").addQueryParameter("aid", "1967").addQueryParameter("q", query).build()
            val items = json(url).getJSONObject("data").getJSONArray("ret_data")
            array(items).filter { it.optString("genre") == "0" }.forEach { item ->
                runCatching { book(item) }.getOrNull()?.let { found[it.id] = it }
            }
            succeeded = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e }
        try {
            val url = "https://www.shuhuangdian.com/api/book/search".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", query).addQueryParameter("page", "1").addQueryParameter("pageSize", "20").build()
            val items = json(url).getJSONObject("data").getJSONArray("books")
            array(items).filter { it.optString("site") == "番茄" && it.optString("id").startsWith("fq") }.forEach { item ->
                runCatching { book(item, true) }.getOrNull()?.let { found.putIfAbsent(it.id, it) }
            }
            succeeded = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e }
        if (!succeeded) throw requireNotNull(failure)
        fun normalized(s: String) = s.filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)
        return found.values.sortedByDescending { normalized(it.title) == normalized(query) }
    }
    suspend fun detail(bookId: String): SearchBook {
        id(bookId)
        val primary = try {
            val data = json(api("detail", "book_id", bookId)).getJSONObject("data").getJSONArray("book_infos")
            array(data).firstOrNull { it.optString("book_id") == bookId }?.let { book(it) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        val result = primary ?: run {
            val url = "https://www.shuhuangdian.com/api/book".toHttpUrl().newBuilder().addQueryParameter("id", "fq$bookId").build()
            val item = json(url).getJSONObject("data").getJSONObject("bookInfo")
            require(item.optString("bookId") == bookId && item.optString("site") == "番茄") { "番茄详情归属异常" }
            book(item, true)
        }
        val toc = chapters(bookId)
        return result.copy(novelInfo = result.novelInfo?.copy(chapterCount = toc.size, latestChapter = toc.last().title))
    }
    suspend fun chapters(bookId: String): List<ComicChapter> {
        val url = "https://fanqienovel.com/api/reader/directory/detail".toHttpUrl().newBuilder().addQueryParameter("bookId", id(bookId)).build()
        // This official endpoint has no code field, unlike the other gateways.
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val data = guestNovelIoRetry { http.newCall(request).executeCancellable().use { r ->
            if (!r.isSuccessful) throw SourceException.NetworkError("番茄目录返回 HTTP ${r.code}")
            SourceResponse.jsonObject(r.readSourceText(6 * 1024 * 1024)).getJSONObject("data")
        } }
        return parseChapters(data)
    }
    internal fun parseChapters(data: JSONObject): List<ComicChapter> {
        val expected = data.getJSONArray("allItemIds").let { a -> (0 until a.length()).map { id(a.getString(it)) } }
        val volumes = data.getJSONArray("chapterListWithVolume")
        val rows = (0 until volumes.length()).flatMap { array(volumes.getJSONArray(it)) }
        require(expected.size in 1..30_000 && expected.distinct().size == expected.size && rows.map { it.optString("itemId") } == expected) { "番茄目录为空、重复或不完整" }
        return rows.mapIndexed { i, item ->
            // isChapterLock marks the web reader's app-only preview, including free chapters.
            // The public text gateway is independent of that web presentation flag.
            require(item.has("needPay") && item.optInt("needPay", -1) == 0 &&
                !item.optBoolean("isPaidPublication") && !item.optBoolean("isPaidStory")) {
                "此书含付费章节，不能免登录完整下载"
            }
            val title = item.optString("title").trim()
            require(title.isNotBlank()) { "番茄章节标题缺失" }
            ComicChapter("https://fanqienovel.com/reader/${id(item.getString("itemId"))}", title,
                volume = item.optString("volume_name"), order = i.toFloat())
        }
    }
    suspend fun parts(chapterUrl: String): List<NovelPart> {
        val url = chapterUrl.toHttpUrl()
        require(url.scheme == "https" && url.host == "fanqienovel.com" && url.username.isEmpty() && url.password.isEmpty() &&
            Regex("^/reader/[0-9]{18,20}$").matches(url.encodedPath) && url.query == null && url.fragment == null) { "番茄章节地址无效" }
        val data = json(api("content", "item_id", id(url.pathSegments.last())))
        return parseParts(data)
    }
    internal fun parseParts(data: JSONObject): List<NovelPart> {
        require(data.optInt("code", -1) in setOf(0, 200)) { "番茄正文接口暂时不可用" }
        val html = data.opt("content") as? String ?: (data.optJSONObject("data")?.opt("content") as? String).orEmpty()
        val doc = Jsoup.parseBodyFragment(html)
        require(doc.select("script,style,iframe,canvas,img").isEmpty()) { "番茄返回了不支持的正文格式" }
        val paragraphs = if (doc.select("p").isNotEmpty()) doc.select("p").map { it.wholeText().trim() }
            else doc.body().wholeText().lineSequence().map(String::trim).toList()
        val lines = paragraphs.filter(String::isNotBlank)
        val text = lines.joinToString("\n\n")
        require(text.isNotBlank() && !hasMissingNovelContent(text) && !text.contains('\uFFFD') &&
            text.none { it in '\uE000'..'\uF8FF' } && listOf("获取内容失败", "请登录后阅读", "本章未完", "购买本章").none(text::contains)) {
            "番茄章节为空、缺失或未完整公开，未保存不完整正文"
        }
        return lines.map { NovelPart.Text(it) }
    }
}
