package com.example.download

import android.graphics.BitmapFactory
import com.example.data.readImportBytes
import com.example.source.SourceResult
import com.example.source.executeCancellable
import com.example.source.impl.NovelPart
import com.example.source.impl.PublicNovelSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A failed chapter or illustration aborts the whole export; a partial EPUB is never imported. */
internal object GuestNovelEpub {
    private fun <T> value(result: SourceResult<T>): T = when (result) {
        is SourceResult.Success -> result.data
        is SourceResult.Error -> throw result.exception
    }
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    suspend fun write(source: PublicNovelSource, downloadUrl: String, target: File, progress: suspend (Int, Int) -> Unit) {
        val url = downloadUrl.toHttpUrl()
        require(url.fragment == "ciallo-public-epub-v1") { "小说下载标识无效" }
        val id = source.bookId(url)
        val book = value(source.getDetail(id))
        val toc = value(source.getChapters(id))
        require(toc.isNotEmpty()) { "源站目录为空" }
        val checkpoint = GuestNovelCheckpoint(target, source.id, downloadUrl, toc)
        val restored = checkpoint.completedChapters.coerceAtMost(toc.size)
        progress(restored, toc.size)
        val records = JSONArray()
        val assets = linkedMapOf<String, Pair<String, String>>()
        val imageRecords = JSONArray()
        var complete = false
        try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                fun entry(name: String, bytes: ByteArray, stored: Boolean = false) {
                    val e = ZipEntry(name)
                    if (stored) { e.method = ZipEntry.STORED; e.size = bytes.size.toLong(); e.compressedSize = e.size; e.crc = CRC32().apply { update(bytes) }.value }
                    zip.putNextEntry(e); zip.write(bytes); zip.closeEntry()
                }
                fun text(name: String, value: String) = entry(name, value.toByteArray(Charsets.UTF_8))
                entry("mimetype", "application/epub+zip".toByteArray(), true)
                text("META-INF/container.xml", """<?xml version="1.0" encoding="utf-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
                for ((index, chapter) in toc.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val cached = checkpoint.chapter(index)
                    val parts = cached ?: source.chapterParts(chapter.id)
                    val body = StringBuilder()
                    for (part in parts) when (part) {
                        is NovelPart.Text -> body.append("<p>").append(xml(part.value)).append("</p>")
                        is NovelPart.Image -> {
                            val asset = assets[part.url] ?: run {
                                val image = checkpoint.image(part.url) ?: run {
                                    val imageUrl = part.url.toHttpUrl()
                                    require(imageUrl.username.isEmpty() && imageUrl.password.isEmpty()) { "插图地址异常" }
                                    val req = Request.Builder().url(imageUrl).header("Referer", chapter.id)
                                        .header("User-Agent", "Mozilla/5.0").build()
                                    source.http.newCall(req).executeCancellable().use { response ->
                                        if (!response.isSuccessful) throw com.example.source.SourceException.NetworkError("插图下载失败（HTTP ${response.code}）")
                                        val bytes = response.body?.byteStream()?.use { it.readImportBytes(16 * 1024 * 1024) } ?: error("插图为空")
                                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                        require(options.outWidth > 0 && options.outHeight > 0) { "插图损坏，未生成缺图电子书" }
                                        val mime = options.outMimeType ?: error("插图格式无法识别")
                                        val extension = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> error("插图格式不受支持") }
                                        GuestNovelCheckpoint.Image(bytes, mime, extension).also { checkpoint.saveImage(part.url, it) }
                                    }
                                }
                                val path = "Images/${assets.size}.${image.extension}"
                                entry("OEBPS/$path", image.bytes)
                                imageRecords.put(JSONObject().put("url", part.url).put("path", path).put("sha256", hash(image.bytes)))
                                (path to image.mime).also { assets[part.url] = it }
                            }
                            body.append("<p><img src=\"").append(xml(asset.first)).append("\" alt=\"插图\"/></p>")
                        }
                    }
                    text("OEBPS/chapter$index.xhtml", """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${xml(chapter.title)}</title></head><body><h1>${xml(chapter.title)}</h1>$body</body></html>""")
                    val plain = parts.filterIsInstance<NovelPart.Text>().joinToString("\n\n") { it.value }
                    records.put(JSONObject().put("url", chapter.id).put("title", chapter.title)
                        .put("textSha256", hash(plain.toByteArray(Charsets.UTF_8))).put("characters", plain.length)
                        .put("paragraphs", parts.count { it is NovelPart.Text }).put("images", parts.count { it is NovelPart.Image }))
                    if (cached == null) checkpoint.saveChapter(index, parts)
                    progress(maxOf(restored, index + 1), toc.size)
                    // Repacking verified local chapters needs no network throttle.
                    if (cached == null) delay(200)
                }
                val latest = value(source.getChapters(id))
                require(toc.map { it.id to it.title } == latest.map { it.id to it.title }) { "下载期间源站目录变化，请重试；未导入旧目录的残缺书籍" }
                val nav = toc.mapIndexed { i, c -> "<li><a href=\"chapter$i.xhtml\">${xml(c.title)}</a></li>" }.joinToString("")
                text("OEBPS/nav.xhtml", """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol>$nav</ol></nav></body></html>""")
                val manifest = toc.indices.joinToString("") { "<item id=\"c$it\" href=\"chapter$it.xhtml\" media-type=\"application/xhtml+xml\"/>" } +
                    assets.values.mapIndexed { i, pair -> "<item id=\"img$i\" href=\"${xml(pair.first)}\" media-type=\"${xml(pair.second)}\"/>" }.joinToString("")
                val spine = toc.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }
                text("OEBPS/content.opf", """<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="bookid">${xml(source.id + ":" + id)}</dc:identifier><dc:title>${xml(book.title)}</dc:title><dc:creator>${xml(book.author)}</dc:creator><dc:language>zh</dc:language><meta property="dcterms:modified">${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())}</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest><spine>$spine</spine></package>""")
                text("META-INF/ciallo-source.json", JSONObject().put("sourceId", source.id).put("bookId", id)
                    .put("chapters", records).put("images", imageRecords).put("complete", true).toString())
            }
            complete = true
        } finally {
            if (!complete) target.delete()
        }
    }
}
