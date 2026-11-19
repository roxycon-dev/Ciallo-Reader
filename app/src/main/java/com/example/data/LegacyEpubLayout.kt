package com.example.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

internal suspend fun recoverArchivedEpubLayout(context: Context, book: Book, dao: BookDao) = withContext(Dispatchers.IO) {
    if (book.isComic) return@withContext
    val uri = Uri.parse(book.filePath)
    val source = when (uri.scheme) {
        null, "" -> File(book.filePath)
        "file" -> uri.path?.let(::File)
        else -> null
    } ?: return@withContext
    if (!source.isFile || !source.extension.equals("epub", true)) return@withContext
    val prefs = context.getSharedPreferences("ebook_layout_read", Context.MODE_PRIVATE)
    val signature = source.length().toString() + ":" + source.lastModified()
    val cacheKey = "epub:" + book.id
    if (prefs.getString(cacheKey, null) == signature) return@withContext
    if (dao.hasChapterFormatting(book.id)) {
        prefs.edit().putString(cacheKey, signature).apply()
        return@withContext
    }

    ZipFile(source).use { zip ->
        fun read(path: String, cap: Int = 2 * 1024 * 1024): String? {
            val entry = zip.getEntry(path) ?: return null
            if (entry.size > cap || entry.isDirectory) return null
            val data = zip.getInputStream(entry).use { it.readImportBytes(cap) }
            val charset = CharsetSniffer.detect(data.copyOf(minOf(data.size, 256 * 1024)), data.size <= 256 * 1024)
            return String(data, charset).removePrefix("\uFEFF")
        }

        fun resolve(origin: String, href: String): String? = runCatching {
            val relative = URLDecoder.decode(href.substringBefore('#').substringBefore('?'), "UTF-8")
            if (relative.startsWith('/') || relative.contains(':') || relative.contains('\\')) return null
            val resolved = URI(null, null, "/" + origin.substringBeforeLast('/', ""), null)
            val directory = resolved.path.trimEnd('/') + "/"
            val path = URI(null, null, directory + relative, null).normalize().path.removePrefix("/")
            path.takeIf { !it.startsWith("../") && it.isNotBlank() }
        }.getOrNull()

        val container = read("META-INF/container.xml", 128 * 1024) ?: return@withContext
        val packagePath = Jsoup.parse(container, "", Parser.xmlParser()).selectFirst("rootfile[full-path]")
            ?.attr("full-path") ?: return@withContext
        val opf = read(packagePath) ?: return@withContext
        val document = Jsoup.parse(opf, "", Parser.xmlParser())
        val byId = document.select("manifest > item[id][href]").associate { it.attr("id") to it.attr("href") }
        val packageMode = document.select("meta").mapNotNull {
            if (it.attr("name").equals("primary-writing-mode", true) || it.attr("property").endsWith(":writing-mode", true)) {
                normalizeWritingMode(it.attr("content").ifBlank { it.text() })
            } else null
        }.lastOrNull()
        val styles = mutableMapOf<String, String>()
        var physical = 0
        val pending = ArrayList<Chapter>(16)
        suspend fun applyBatch() {
            if (pending.isEmpty()) return
            val data = dao.getChaptersByOrders(book.id, pending.map { it.chapterOrder }).associateBy { it.chapterOrder }
            for (fresh in pending) {
                val old = data[fresh.chapterOrder] ?: continue
                val aligned = transferBookStyles(fresh.content, old.content, decodeBookSpans(fresh.formatting)) ?: continue
                dao.setChapterFormatting(book.id, old.id, old.content, encodeBookSpans(aligned), fresh.writingMode)
            }
            pending.clear()
        }
        for (item in document.select("spine > itemref[idref]")) {
            currentCoroutineContext().ensureActive()
            val href = byId[item.attr("idref")] ?: continue
            val path = resolve(packagePath, href) ?: continue
            val xhtml = read(path) ?: continue
            val stylesheet = Jsoup.parse(xhtml).select("link[rel=stylesheet][href]").take(24).mapNotNull { link ->
                val cssPath = resolve(path, link.attr("href")) ?: return@mapNotNull null
                styles[cssPath] ?: read(cssPath, 512_000)?.also { if (styles.size < 48) styles[cssPath] = it }
            }
            val parsed = readBookHtml(xhtml, stylesheet, packageMode)
            // Recreate the former chapter partition first. Images in the stored text are
            // ignored by the comparison, but all non-whitespace characters must agree.
            val priorText = EpubParser.extractCleanTextFromHtml(xhtml)
            val aligned = transferBookStyles(parsed.text, priorText, parsed.spans)
            val rows = FormattedBookText(priorText, aligned.orEmpty(), parsed.writingMode).chapters(book.id, physical, "")
            physical += rows.size
            pending.addAll(rows.filter { it.formatting != null || it.writingMode != null })
            if (pending.size >= 12) applyBatch()
        }
        applyBatch()
    }
    prefs.edit().putString(cacheKey, signature).apply()
}

internal fun transferBookStyles(fresh: String, existing: String, styles: List<BookTextSpan>): List<BookTextSpan>? {
    if (fresh == existing) return styles
    fun visible(text: String): List<Int> {
        val images = Regex("""\[IMG:[^]]+]""").findAll(text).map { it.range }.iterator()
        var image = if (images.hasNext()) images.next() else null
        return text.indices.filter { index ->
            if (image?.last?.let { index > it } == true) image = if (images.hasNext()) images.next() else null
            index !in (image ?: IntRange.EMPTY) && !text[index].isWhitespace()
        }
    }
    val from = visible(fresh)
    val to = visible(existing)
    if (from.size != to.size || from.indices.any { fresh[from[it]] != existing[to[it]] }) return null
    fun nextIndex(offset: Int): Int = from.binarySearch(offset).let { if (it >= 0) it else -it - 1 }
    return styles.mapNotNull { style ->
        val start = nextIndex(style.start)
        val end = nextIndex(style.end) - 1
        if (end < start || start !in to.indices) null
        else BookTextSpan(to[start], to[end] + 1, style.style)
    }
}
