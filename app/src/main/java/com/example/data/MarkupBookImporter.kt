package com.example.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup

private val markdownExtensions = listOf(TablesExtension.create(), StrikethroughExtension.create())
private val markdownParser = Parser.builder().extensions(markdownExtensions).build()
private val markdownRenderer = HtmlRenderer.builder().extensions(markdownExtensions).build()

internal suspend fun importMarkupBook(
    context: Context,
    uri: Uri,
    fileName: String,
    dao: BookDao,
    markdown: Boolean
): Result<Book> = try {
    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readImportBytes(16 * 1024 * 1024) }
    val charset = CharsetSniffer.detect(bytes.copyOf(minOf(bytes.size, 262_144)), bytes.size <= 262_144)
    val source = String(bytes, charset).removePrefix("\uFEFF")
    val html = if (markdown) renderBookMarkdown(source) else source
    val document = Jsoup.parse(html)
    val title = document.title().ifBlank { document.selectFirst("h1")?.text().orEmpty() }
        .ifBlank { fileName.substringBeforeLast('.') }
    val text = readBookHtml(html)
    require(text.text.isNotBlank())
    val initial = Book(title = title, filePath = uri.toString())
    val id = dao.insertBook(initial).toInt()
    val chapters = text.chapters(id, 0, title)
    dao.insertChapters(chapters)
    val book = initial.copy(id = id, totalChapters = chapters.size)
    dao.updateBook(book)
    Result.success(book)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

internal fun renderBookMarkdown(text: String): String = markdownRenderer.render(markdownParser.parse(text))

internal fun recognizeBookMarkup(context: Context, uri: Uri, fileName: String): String? {
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "epub" -> return "epub"
        "docx" -> return "docx"
        "md", "markdown", "mdown", "mkd" -> return "md"
        "htm", "html", "xhtml" -> return "html"
        "fb2" -> return "fb2"
        "mobi", "azw", "azw3", "prc" -> return "mobi"
        "txt", "zip", "bin", "" -> Unit
        else -> return null
    }
    val resolver = context.contentResolver
    val header = resolver.openInputStream(uri)?.use {
        val bytes = ByteArray(1024)
        val size = it.read(bytes).coerceAtLeast(0)
        bytes.copyOf(size)
    } ?: return null
    if (header.size >= 68 && String(header, 60, 8, Charsets.US_ASCII) == "BOOKMOBI") return "mobi"
    val head = header.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
    if (head.startsWith("<FictionBook", true) || head.startsWith("<?xml", true) &&
        head.contains("<FictionBook", true)) return "fb2"
    if (head.startsWith("<!doctype html", true) || head.startsWith("<html", true)) return "html"
    if (header.size < 4 || header[0] != 80.toByte() || header[1] != 75.toByte()) return null

    val local = uri.path?.takeIf { uri.scheme == "file" }?.let(::File)
    if (local?.isFile == true) {
        return runCatching {
            java.util.zip.ZipFile(local).use { zip ->
                when {
                    zip.getEntry("META-INF/container.xml") != null -> "epub"
                    zip.getEntry("word/document.xml") != null -> "docx"
                    else -> null
                }
            }
        }.getOrNull()
    }
    return resolver.openInputStream(uri)?.use { stream ->
        runCatching {
            ZipInputStream(stream).use { zip ->
                val budget = ArchiveBudget(8L * 1024 * 1024, 24L * 1024 * 1024, 64)
                repeat(64) {
                    when (zip.nextEntry?.name ?: return@use null) {
                        "META-INF/container.xml" -> return@use "epub"
                        "word/document.xml" -> return@use "docx"
                    }
                    budget.copyEntry(zip)
                }
                null
            }
        }.getOrNull()
    }
}
