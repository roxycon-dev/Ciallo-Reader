package com.example.data

import com.example.i18n.tr

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * DOCX 解析器：从 zip 里取 word/document.xml，按 Heading1/Heading2 分章，
 * 提取 <w:t> 文本入库（结构与 EpubParser 一致）。
 */
object DocxParser {

    private const val TAG = "DocxParser"

    fun isDocxFile(fileName: String): Boolean {
        return fileName.lowercase().endsWith(".docx")
    }

    suspend fun importDocx(
        context: Context,
        uri: Uri,
        fileName: String,
        bookDao: BookDao,
        targetBookId: Int? = null
    ): Result<Book> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[DocxParser] Starting DOCX import for $fileName")
            val docx = readDocxContent(context, uri, 0)
                ?: return@withContext Result.failure(Exception(tr("无法读取 DOCX 文件（缺少 word/document.xml）")))
            val paragraphs = docxParagraphs(docx.documentXml, docx.stylesXml, docx.numberingXml, docx.relTargets)
            if (paragraphs.isEmpty()) {
                return@withContext Result.failure(Exception(tr("DOCX 文件中未找到有效正文内容")))
            }

            val title = paragraphs.firstOrNull { it.isHeading }?.text?.text
                ?.ifBlank { null }
                ?: fileName.substringBeforeLast('.').ifBlank { fileName }

            val initialBook = Book(
                title = title,
                author = "未知作者",
                filePath = uri.toString(),
                contentType = "NOVEL",
                totalChapters = 0
            )
            // targetBookId 非空 = 老书补图片迁移：不新建书、失败不删书（调用方事务收尾）
            val bookId = targetBookId ?: bookDao.insertBook(initialBook).toInt()

            val chapters = mutableListOf<Chapter>()
            var order = 0
            var currentTitle = "第 1 章"
            var currentContent = StyledTextBuilder()

            fun flushChapter() {
                chapters.addAll(currentContent.build().chapters(bookId, order, currentTitle))
                order = chapters.size
                currentContent = StyledTextBuilder()
            }

            for (paragraph in paragraphs) {
                if (paragraph.isHeading) {
                    flushChapter()
                    currentTitle = paragraph.text.text.ifBlank { "第 " + (order + 1) + " 章" }
                } else {
                    currentContent.breakLine(!paragraph.compact)
                    currentContent.append(paragraph.text)
                }
            }
            flushChapter()
            if (chapters.isEmpty()) {
                val fallback = StyledTextBuilder()
                for (paragraph in paragraphs) {
                    fallback.breakLine(true)
                    fallback.append(paragraph.text)
                }
                chapters.addAll(fallback.build().chapters(bookId, 0, title))
            }

            if (chapters.isEmpty()) {
                if (targetBookId == null) bookDao.deleteBook(initialBook.copy(id = bookId))
                return@withContext Result.failure(Exception(tr("DOCX 文件中未找到有效正文内容")))
            }

            bookDao.insertChapters(chapters)
            val finalBook = initialBook.copy(
                id = bookId,
                totalChapters = chapters.size
            )
            bookDao.updateBook(finalBook)
            Log.d(TAG, "[DocxParser] Successfully imported '${finalBook.title}' with ${chapters.size} chapters.")
            Result.success(finalBook)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "[DocxParser] Error during DOCX import", t)
            Result.failure(Exception(t.localizedMessage ?: "DOCX 解析失败"))
        }
    }

    private data class DocxContent(
        val documentXml: String,
        val stylesXml: String,
        val numberingXml: String,
        /** rId -> 已落盘的媒体文件绝对路径（正文插图的引用来源） */
        val relTargets: Map<String, String>
    )

    private fun readDocxContent(context: Context, uri: Uri, bookId: Int): DocxContent? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        var documentXml: String? = null
        var stylesXml = ""
        var numberingXml = ""
        val rels = mutableMapOf<String, String>()
        val imageDir = File(context.filesDir, "docx_images/$bookId")

        val budget = ArchiveBudget(16L * 1024 * 1024)
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    ArchiveBudget.destination(imageDir, entry.name)
                    when {
                        entry.name in setOf("word/document.xml", "word/styles.xml", "word/numbering.xml") -> {
                            val out = ByteArrayOutputStream()
                            budget.copyEntry(zip, out)
                            val xml = String(out.toByteArray(), StandardCharsets.UTF_8)
                            when (entry.name) {
                                "word/document.xml" -> documentXml = xml
                                "word/styles.xml" -> stylesXml = xml
                                else -> numberingXml = xml
                            }
                        }
                        entry.name == "word/_rels/document.xml.rels" -> {
                            val out = ByteArrayOutputStream()
                            budget.copyEntry(zip, out)
                            val relXml = String(out.toByteArray(), StandardCharsets.UTF_8)
                            org.jsoup.Jsoup.parse(relXml, "", org.jsoup.parser.Parser.xmlParser())
                                .getAllElements().filter { it.normalName().substringAfter(':') == "relationship" }
                                .forEach { relation ->
                                    val id = relation.attr("Id")
                                    val target = relation.attr("Target")
                                    if (id.isNotBlank() && target.startsWith("media/") &&
                                        !relation.attr("TargetMode").equals("External", true)) rels[id] = target
                                }
                        }
                        !entry.isDirectory && entry.name.startsWith("word/media/") -> {
                            if (!imageDir.exists()) imageDir.mkdirs()
                            val f = File(imageDir, entry.name.substringAfterLast('/'))
                            FileOutputStream(f).use { budget.copyEntry(zip, it) }
                            rels["__file__" + entry.name] = f.absolutePath
                        }
                        else -> budget.copyEntry(zip)
                    }
                    entry = zip.nextEntry
                }
            }
        }

        if (documentXml == null) return null
        // rId -> 落盘图片路径
        val resolved = mutableMapOf<String, String>()
        for ((rid, target) in rels) {
            if (rid.startsWith("__file__")) continue
            val name = target.substringAfterLast('/')
            val local = File(imageDir, name)
            if (local.exists()) resolved[rid] = local.absolutePath
        }
        return DocxContent(documentXml, stylesXml, numberingXml, resolved)
    }

}
