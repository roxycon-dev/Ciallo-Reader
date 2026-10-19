package com.example.download

import com.example.source.SourceResult
import com.example.source.impl.XbookcnSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 书阁整本下载：逐章抓正文并在本地生成 EPUB。
 *
 * 该源没有可直接下载的文件，只能边抓边打包；任何一章失败或目录在下载期间
 * 发生变化都整体作废，绝不导入缺章的电子书。
 */
internal object XbookcnEpub {
    private fun <T> value(result: SourceResult<T>): T = when (result) {
        is SourceResult.Success -> result.data
        is SourceResult.Error -> throw result.exception
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    suspend fun write(
        source: XbookcnSource,
        downloadUrl: String,
        target: File,
        progress: suspend (Int, Int) -> Unit
    ) {
        val url = downloadUrl.toHttpUrl()
        require(url.fragment == XbookcnSource.EPUB_MARKER) { "书阁下载标识无效" }
        val bookId = url.pathSegments.getOrNull(1) ?: error("书阁下载地址无效")
        XbookcnSource.requireBookId(bookId)
        val book = value(source.getDetail(bookId))
        val toc = value(source.getChapters(bookId))
        require(toc.isNotEmpty()) { "书阁目录为空，未生成电子书" }
        val records = JSONArray()
        var complete = false
        try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                fun entry(name: String, bytes: ByteArray, stored: Boolean = false) {
                    val e = ZipEntry(name)
                    if (stored) {
                        e.method = ZipEntry.STORED
                        e.size = bytes.size.toLong()
                        e.compressedSize = e.size
                        e.crc = CRC32().apply { update(bytes) }.value
                    }
                    zip.putNextEntry(e)
                    zip.write(bytes)
                    zip.closeEntry()
                }

                fun text(name: String, value: String) =
                    entry(name, value.toByteArray(Charsets.UTF_8))

                entry("mimetype", "application/epub+zip".toByteArray(), true)
                text(
                    "META-INF/container.xml",
                    """<?xml version="1.0" encoding="utf-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
                )
                for ((index, chapter) in toc.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val body = value(source.getChapterText(chapter.id))
                    val paragraphs = body.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
                    require(paragraphs.isNotEmpty()) { "第 ${index + 1} 章正文为空，未生成缺章电子书" }
                    val html = buildString {
                        append("""<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>""")
                        append(xml(chapter.title)).append("</title></head><body><h1>")
                        append(xml(chapter.title)).append("</h1>")
                        paragraphs.forEach { append("<p>").append(xml(it)).append("</p>") }
                        append("</body></html>")
                    }
                    text("OEBPS/chapter$index.xhtml", html)
                    val plain = paragraphs.joinToString("\n\n")
                    records.put(
                        JSONObject().put("url", chapter.id).put("title", chapter.title)
                            .put("textSha256", hash(plain.toByteArray(Charsets.UTF_8)))
                            .put("characters", plain.length)
                            .put("paragraphs", paragraphs.size)
                    )
                    progress(index + 1, toc.size)
                    // 顺序抓取，避免对源站形成并发压力。
                    delay(180)
                }
                val latest = value(source.getChapters(bookId))
                require(toc.map { it.id to it.title } == latest.map { it.id to it.title }) {
                    "下载期间源站目录变化，请重试；未导入旧目录的残缺书籍"
                }
                val nav = toc.mapIndexed { i, chapter ->
                    "<li><a href=\"chapter$i.xhtml\">${xml(chapter.title)}</a></li>"
                }.joinToString("")
                text(
                    "OEBPS/nav.xhtml",
                    """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol>$nav</ol></nav></body></html>"""
                )
                val manifest = toc.indices.joinToString("") {
                    "<item id=\"c$it\" href=\"chapter$it.xhtml\" media-type=\"application/xhtml+xml\"/>"
                }
                val spine = toc.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }
                text(
                    "OEBPS/content.opf",
                    """<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="bookid">${xml(XbookcnSource.SOURCE_ID + ":" + bookId)}</dc:identifier><dc:title>${xml(book.title)}</dc:title><dc:creator>${xml(book.author)}</dc:creator><dc:language>zh</dc:language><meta property="dcterms:modified">${
                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .format(java.util.Date())
                    }</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest><spine>$spine</spine></package>"""
                )
                text(
                    "META-INF/ciallo-source.json",
                    JSONObject().put("sourceId", XbookcnSource.SOURCE_ID).put("bookId", bookId)
                        .put("chapters", records).put("images", JSONArray()).put("complete", true)
                        .toString()
                )
            }
            complete = true
        } finally {
            if (!complete) target.delete()
        }
    }
}
