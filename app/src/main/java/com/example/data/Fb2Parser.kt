package com.example.data

import com.example.i18n.tr

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * FB2（FictionBook）解析器：XML 结构 -> 书名/作者/分章正文/封面，
 * 入库结构与 EpubParser 一致（Book + Chapter）。
 */
object Fb2Parser {

    private const val TAG = "Fb2Parser"

    fun isFb2File(fileName: String): Boolean {
        return fileName.lowercase().endsWith(".fb2")
    }

    suspend fun importFb2(
        context: Context,
        uri: Uri,
        fileName: String,
        bookDao: BookDao,
        targetBookId: Int? = null
    ): Result<Book> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[Fb2Parser] Starting FB2 import for $fileName")
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readImportBytes() }
                ?: return@withContext Result.failure(Exception(tr("无法读取 FB2 文件")))
            val text = decodeFb2Text(bytes)
            if (!text.contains("<FictionBook", ignoreCase = true)) {
                return@withContext Result.failure(Exception(tr("不是有效的 FB2 文件")))
            }

            val doc = Jsoup.parse(text, "", Parser.xmlParser())
            val title = doc.selectFirst("book-title")?.text()?.trim()
                ?.ifBlank { null }
                ?: fileName.substringBeforeLast('.').ifBlank { fileName }
            val author = doc.selectFirst("author")?.text()?.trim()?.ifBlank { null } ?: "未知作者"


            val initialBook = Book(
                title = title,
                author = author,
                filePath = uri.toString(),
                contentType = "NOVEL",
                totalChapters = 0
            )
            // targetBookId 非空 = 老书补图片迁移：不新建书、失败不删书（调用方事务收尾）
            val bookId = targetBookId ?: bookDao.insertBook(initialBook).toInt()

            // 内嵌图片：<binary id/content-type> 全部提取到持久目录，正文占位符按 id 引用
            val fb2ImageDir = File(context.filesDir, "fb2_images/$bookId")
            val binaryImages = HashMap<String, Fb2Image>()
            doc.select("binary").forEach { bin ->
                val binId = bin.attr("id").trim()
                val base64 = bin.text().replace(Regex("\\s"), "")
                if (binId.isBlank() || base64.isBlank()) return@forEach
                runCatching {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    if (opts.outWidth <= 0 || opts.outHeight <= 0) return@forEach
                    val ext = detectImageExt(bytes) ?: "jpg"
                    if (!fb2ImageDir.exists()) fb2ImageDir.mkdirs()
                    val f = File(fb2ImageDir, "inline_" + binaryImages.size + "_" + System.nanoTime() + "." + ext)
                    f.writeBytes(bytes)
                    binaryImages[binId] = Fb2Image(f.absolutePath, opts.outWidth, opts.outHeight)
                }
            }

            // 只取第一个 <body>（正文），跳过 notes/comments 等附加 body
            val body = doc.select("body").firstOrNull()
            val sections = body?.select("section") ?: emptyList()
            val chapters = mutableListOf<Chapter>()
            var order = 0

            if (sections.isNotEmpty()) {
                sections.forEachIndexed { index, section ->
                    val sectionTitle = section.select("title").firstOrNull()?.text()?.trim()
                        ?.ifBlank { null }
                        ?: "第 ${index + 1} 章"
                    val builder = StyledTextBuilder()
                    section.select("p, image, subtitle, v, text-author").forEach { element ->
                        if (element.parents().firstOrNull { it.normalName() == "section" } != section ||
                            element.parents().any { it.normalName() == "title" }) return@forEach
                        builder.breakLine(element.normalName() != "v")
                        if (element.normalName() == "image") {
                            val reference = element.attr("l:href").ifBlank { element.attr("href") }.trimStart('#')
                            binaryImages[reference]?.let {
                                builder.append("[IMG:" + it.path + "|" + it.w + "|" + it.h + "]", 0)
                            }
                        } else {
                            builder.append(readBookHtml(element.outerHtml()))
                        }
                    }
                    chapters.addAll(builder.build().chapters(bookId, order, sectionTitle))
                    order = chapters.size
                }
            } else if (body != null) {
                chapters.addAll(readBookHtml(body.outerHtml()).chapters(bookId, 0, title, 5000))
            }

            if (chapters.isEmpty()) {
                if (targetBookId == null) bookDao.deleteBook(initialBook.copy(id = bookId))
                File(context.filesDir, "fb2_images/$bookId").deleteRecursively()
                return@withContext Result.failure(Exception(tr("FB2 文件中未找到有效正文内容")))
            }

            var coverUri: String? = null
            runCatching {
                val coverRef = doc.selectFirst("coverpage image")?.attr("href")?.trimStart('#')
                val binaryEl = coverRef?.let { ref ->
                    doc.selectFirst("binary[id=\"$ref\"]")
                }
                val base64 = binaryEl?.text()?.replace(Regex("\\s"), "")
                if (!base64.isNullOrBlank()) {
                    val coverBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    if (BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size) != null) {
                        val coverDir = File(context.filesDir, "fb2_covers")
                        if (!coverDir.exists()) coverDir.mkdirs()
                        val ext = detectImageExt(coverBytes) ?: "jpg"
                        val destCover = File(
                            coverDir,
                            "cover_${bookId}_${System.currentTimeMillis()}.$ext"
                        )
                        destCover.writeBytes(coverBytes)
                        coverUri = destCover.absolutePath
                    }
                }
            }

            bookDao.insertChapters(chapters)
            val finalBook = initialBook.copy(
                id = bookId,
                coverUri = coverUri,
                totalChapters = chapters.size
            )
            bookDao.updateBook(finalBook)
            Log.d(TAG, "[Fb2Parser] Successfully imported '${finalBook.title}' with ${chapters.size} chapters.")
            Result.success(finalBook)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "[Fb2Parser] Error during FB2 import", t)
            Result.failure(Exception(t.localizedMessage ?: "FB2 解析失败"))
        }
    }

    private data class Fb2Image(val path: String, val w: Int, val h: Int)

private fun decodeFb2Text(bytes: ByteArray): String {
        val sample = bytes.copyOf(minOf(bytes.size, 2048))
        val header = String(sample, StandardCharsets.ISO_8859_1)
        val encMatch = Regex(
            """<\?xml[^>]*encoding=["']([a-zA-Z0-9_-]+)["']""",
            RegexOption.IGNORE_CASE
        ).find(header)
        return try {
            encMatch?.groupValues?.get(1)?.let { name ->
                if (name.equals("utf-8", true) || name.equals("utf8", true)) {
                    return String(bytes, StandardCharsets.UTF_8)
                }
                return String(bytes, Charset.forName(name))
            }
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
            }
            val utf8 = String(bytes, StandardCharsets.UTF_8)
            if (!utf8.contains('\uFFFD')) {
                utf8
            } else {
                try {
                    String(bytes, Charset.forName("GBK"))
                } catch (_: Exception) {
                    utf8
                }
            }
        } catch (_: Exception) {
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    private fun detectImageExt(bytes: ByteArray): String? {
        return when {
            bytes.size >= 3 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() -> "png"
            bytes.size >= 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() -> "gif"
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            else -> null
        }
    }
}
