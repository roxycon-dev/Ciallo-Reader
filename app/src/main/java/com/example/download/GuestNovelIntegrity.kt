package com.example.download

import android.graphics.BitmapFactory
import com.example.data.readImportBytes
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Stricter completeness checks for built-in, anonymous Chinese novel downloads only. */
internal object GuestNovelIntegrity {
    data class EpubSummary(val titles: List<String>, val images: Int, val replacementCharacters: Int)
    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")

    fun epub(file: File): EpubSummary = ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence().toList()
        require(entries.size in 1..30_000 && entries.map { it.name }.distinct().size == entries.size) { "EPUB 条目缺失或重复" }
        var expanded = 0L
        fun bytes(path: String): ByteArray {
            val entry = zip.getEntry(path) ?: error("EPUB 缺少文件：$path")
            require(entry.size in 0..32L * 1024 * 1024) { "EPUB 文件大小异常" }
            val raw = zip.getInputStream(entry).use { it.readImportBytes(32 * 1024 * 1024) }
            require(raw.size.toLong() == entry.size && CRC32().apply { update(raw) }.value == entry.crc) { "EPUB 文件截断或校验失败：$path" }
            return raw
        }
        for (entry in entries.filterNot { it.isDirectory }) {
            expanded += entry.size
            require(expanded in 0..512L * 1024 * 1024) { "EPUB 解压体积异常" }
            bytes(entry.name)
        }
        fun resolve(base: String, href: String): String {
            val relative = URI(href.replace(" ", "%20"))
            require(!relative.isAbsolute && relative.authority == null) { "EPUB 使用远程正文或插图，离线内容不完整" }
            val path = URI("/" + base).resolve(relative).normalize().path.removePrefix("/")
            require(!path.startsWith("../") && path.isNotEmpty() && zip.getEntry(path) != null) { "EPUB 正文或插图缺失：$path" }
            return path
        }
        val container = Jsoup.parse(utf8(bytes("META-INF/container.xml")), "", Parser.xmlParser())
        val opfPath = container.selectFirst("rootfile")?.attr("full-path")?.takeIf { it.isNotBlank() } ?: error("EPUB 书目缺失")
        val opf = Jsoup.parse(utf8(bytes(opfPath)), "", Parser.xmlParser())
        val items = opf.select("manifest > item").associateBy { it.attr("id") }
        val spine = opf.select("spine > itemref")
        require(spine.isNotEmpty()) { "EPUB 没有可读正文" }
        val titles = mutableListOf<String>()
        var imageCount = 0
        var chinese = 0
        var replacementCharacters = 0
        for (ref in spine) {
            val item = items[ref.attr("idref")] ?: error("EPUB 书脊引用缺失")
            val path = resolve(opfPath, item.attr("href"))
            val html = utf8(bytes(path))
            // HTML parsing may discard NUL; inspect decoded source before parsing.
            require(!html.contains('\u0000')) { "EPUB 正文包含乱码" }
            val doc = Jsoup.parse(html)
            val body = doc.body()
            val text = body.text()
            require(!text.contains('\u0000')) { "EPUB 正文包含乱码" }
            // utf8() already rejects malformed encoded bytes. A literal U+FFFD may
            // instead be an isolated missing character in an otherwise valid source
            // translation. Keep that text verbatim; do not reject a whole long novel
            // or invent a replacement. Still reject short/broken chapters and bursts.
            val missing = text.count { it == '\uFFFD' }
            replacementCharacters += missing
            require(missing == 0 || (missing == 1 && text.length >= 1_000 && replacementCharacters <= 3)) {
                "EPUB 正文包含大量缺字或乱码"
            }
            require(!com.example.source.hasMissingNovelContent(text)) { "源站 EPUB 含缺失正文或缺译章节" }
            chinese += text.count { it in '\u4e00'..'\u9fff' }
            val images = body.select("img[src],image[href],image[xlink:href]")
            require(text.isNotBlank() || images.isNotEmpty()) { "EPUB 含空白章节" }
            for (img in images) {
                val href = img.attr("src").ifBlank { img.attr("href") }.ifBlank { img.attr("xlink:href") }
                val data = bytes(resolve(path, href))
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, options)
                require(options.outWidth > 0 && options.outHeight > 0) { "EPUB 插图损坏" }
                imageCount++
            }
            if ("nav" !in item.attr("properties").split(' ')) {
                titles += doc.selectFirst("h1,h2,h3")?.text().orEmpty().ifBlank { doc.title() }
            }
        }
        require(chinese > 20) { "未发现足够的中文正文，未导入空书或错误语言文件" }
        EpubSummary(titles, imageCount, replacementCharacters)
    }

    /** Reject detectable gaps and empty numbered sections instead of silently sorting them away. */
    fun text(file: File) {
        val heading = Regex("^第([0-9]{1,7})[章回].{0,150}$")
        val numbered = linkedMapOf<Int, Boolean>()
        var active: Int? = null
        var body = false
        fun finish() { active?.let { number ->
            require(body) { "源站 TXT 第 $number 章只有标题，没有正文" }
            require(!numbered.containsKey(number)) { "源站 TXT 有重复章节编号 $number，未导入不确定的整本文件" }
            numbered[number] = true
        } }
        java.io.InputStreamReader(file.inputStream(), Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)).buffered().useLines { lines ->
            lines.forEach { raw ->
                require(!raw.contains('\uFFFD') && !raw.contains('\u0000')) { "源站 TXT 包含乱码" }
                val line = raw.trim().trimStart('\uFEFF')
                val number = heading.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()
                if (number != null) { finish(); active = number; body = false }
                else if (line.isNotBlank()) {
                    require(!com.example.source.hasMissingNovelContent(line) && !line.startsWith("正文加载失败")) { "源站 TXT 包含缺章占位内容" }
                    body = true
                }
            }
        }
        finish()
        val numbers = numbered.keys.sorted()
        if (numbers.isNotEmpty()) {
            require(numbers.first() in 0..1) { "源站 TXT 缺少开头章节" }
            val gap = numbers.zipWithNext().firstOrNull { (a, b) -> b != a + 1 }
            require(gap == null) { "源站 TXT 存在缺章（${gap!!.first + 1} 起），未导入残缺文件" }
        }
    }
}
