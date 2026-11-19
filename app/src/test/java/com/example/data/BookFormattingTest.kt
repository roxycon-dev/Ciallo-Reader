package com.example.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Adler32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Test

class BookFormattingTest {
    @Test fun stylesFromHtmlAndLinkedCssAreAppliedToDisplayText() {
        val html = """<html><body class="rtl"><h1>Title</h1><p>Some <span class="weight">bold <em>and italic</em></span> text</p>
            <ol start="3"><li>Numbered</li></ol><table><tr><th>A</th><td>B</td></tr></table>
            <p style="display:none">hidden text</p><script>ignored()</script></body></html>"""
        val result = readBookHtml(html, listOf(".rtl { -epub-writing-mode: vertical-rl } .weight {font-weight: 700;}"))
        assertEquals("vertical-rl", result.writingMode)
        assertEquals(BookTextStyle.Bold or BookTextStyle.Italic, styleAt(result, "and italic"))
        assertTrue(styleAt(result, "Title") and BookTextStyle.Heading != 0)
        assertTrue(result.text.contains("3. Numbered"))
        assertTrue(result.text.contains("A │ B"))
        assertFalse(result.text.contains("hidden text"))
        assertFalse(result.text.contains("ignored"))
        val parts = result.chapters(9, 0, "Test", 20)
        val merged = ChapterMerger.buildLogicalChapters(parts).chapters
        assertEquals(result.text, merged.joinToString("") { it.content })
        assertEquals(encodeBookSpans(result.spans), encodeBookSpans(parts.flatMapIndexed { index, part ->
            val offset = parts.take(index).sumOf { it.content.length }
            decodeBookSpans(part.formatting).map { it.copy(start = it.start + offset, end = it.end + offset) }
        }.fold(mutableListOf<BookTextSpan>()) { all, span ->
            if (all.lastOrNull()?.let { it.style == span.style && it.end == span.start } == true) {
                all[all.lastIndex] = all.last().copy(end = span.end)
            } else all.add(span)
            all
        }))
    }

    @Test fun markdownRendersMarkupAndKeepsDeliberateCodeLiteral() {
        val rendered = readBookHtml(renderBookMarkdown("# Heading\n\nPlain **Bold** and ~~gone~~.\n\n" +
            "| A | B |\n|---|---|\n| 1 | 2 |\n\n```html\n<b>example</b>\n```"))
        assertEquals(BookTextStyle.Bold, styleAt(rendered, "Bold"))
        assertEquals(BookTextStyle.Strike, styleAt(rendered, "gone"))
        assertFalse(rendered.text.contains("**"))
        assertTrue(rendered.text.contains("1 │ 2"))
        assertEquals(BookTextStyle.Code, styleAt(rendered, "<b>example</b>"))
    }

    @Test fun docxFollowsRunStyleAndKeepsTableCellsSeparate() {
        val xml = """<w:document xmlns:w="word"><w:body>
            <w:p><w:r><w:rPr><w:rStyle w:val="Strong"/></w:rPr><w:t>Deep</w:t></w:r>
              <w:r><w:rPr><w:i/><w:vertAlign w:val="superscript"/></w:rPr><w:t>2</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Left</w:t></w:r></w:p></w:tc>
              <w:tc><w:p><w:r><w:t>Right</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>"""
        val styles = """<w:styles xmlns:w="word"><w:style w:styleId="Strong"><w:rPr><w:b/></w:rPr></w:style></w:styles>"""
        val result = docxParagraphs(xml, styles, "", emptyMap())
        assertEquals(listOf("Deep2", "Left │ Right"), result.map { it.text.text })
        assertEquals(BookTextStyle.Bold, styleAt(result.first().text, "Deep"))
        assertEquals(BookTextStyle.Italic or BookTextStyle.Super, styleAt(result.first().text, "2"))
    }

    @Test fun reattachesFormattingWithoutRewritingStoredText() {
        val ranges = transferBookStyles("A word", "A\n\n[IMG:a|4|4]\nword", listOf(BookTextSpan(2, 6, 1)))
        assertEquals(listOf(BookTextSpan(15, 19, 1)), ranges)
        assertNull(transferBookStyles("same", "other", emptyList()))
    }

    @Test fun readsCompressedMdx2DefinitionsAndAliasesSharingOffset() {
        val root = kotlin.io.path.createTempDirectory("ciallo-mdx").toFile()
        try {
            val input = File(root, "fixture.mdx")
            val record = "<b>A fox.</b>\u0000".toByteArray()
            val definitions = mdxBlock(record)
            val keyData = fixtureBytes { out ->
                for (word in listOf("fox", "vulpes")) {
                    out.writeLong(0)
                    out.write(word.toByteArray())
                    out.writeByte(0)
                }
            }
            val keys = mdxBlock(keyData)
            val infoData = fixtureBytes { out ->
                out.writeLong(2)
                for (word in listOf("fox", "vulpes")) {
                    out.writeShort(word.length)
                    out.write(word.toByteArray())
                    out.writeByte(0)
                }
                out.writeLong(keys.size.toLong())
                out.writeLong(keyData.size.toLong())
            }
            val info = mdxBlock(infoData)
            val header = """<Dictionary GeneratedByEngineVersion="2.0" Encoding="UTF-8" Title="Test MDX" Encrypted="No" />"""
                .toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
            val keyHeader = fixtureBytes { out ->
                listOf(1L, 2L, infoData.size.toLong(), info.size.toLong(), keys.size.toLong()).forEach(out::writeLong)
            }
            DataOutputStream(input.outputStream()).use { out ->
                out.writeInt(header.size)
                out.write(header)
                out.writeInt(Integer.reverseBytes(adlerValue(header)))
                out.write(keyHeader)
                out.writeInt(adlerValue(keyHeader))
                out.write(info)
                out.write(keys)
                listOf(1L, 2L, 16L, definitions.size.toLong(), definitions.size.toLong(), record.size.toLong())
                    .forEach(out::writeLong)
                out.write(definitions)
            }
            val result = mutableMapOf<String, String>()
            assertEquals("Test MDX", extractMdxEntries(input, File(root, "expanded")) { word, text -> result[word] = text })
            assertEquals(mapOf("fox" to "<b>A fox.</b>", "vulpes" to "<b>A fox.</b>"), result)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun readsMobiOrthDictionaryByItsIndexedByteRange() {
        val text = "<p>A reader.</p>".toByteArray()
        val header = java.nio.ByteBuffer.allocate(232)
        header.putShort(0, 1)
        header.putInt(4, text.size)
        header.putShort(8, 1)
        header.position(16)
        header.put("MOBI".toByteArray()).putInt(192)
        header.putInt(28, 65001)
        header.putInt(36, 6)
        header.putInt(40, 2)
        val metadata = java.nio.ByteBuffer.allocate(80)
        metadata.put("INDX".toByteArray()).putInt(52)
        metadata.putInt(24, 1)
        metadata.putInt(28, 65001)
        metadata.putInt(36, 1)
        metadata.position(52)
        metadata.put("TAGX".toByteArray()).putInt(24).putInt(1)
        metadata.put(byteArrayOf(1, 1, 1, 0, 2, 1, 2, 0, 0, 0, 0, 1))
        val record = java.nio.ByteBuffer.allocate(70)
        record.put("INDX".toByteArray()).putInt(32)
        record.putInt(20, 64)
        record.putInt(24, 1)
        record.position(32)
        record.put(6).put("reader".toByteArray()).put(3).put(0x80.toByte()).put((text.size or 128).toByte())
        record.position(64)
        record.put("IDXT".toByteArray()).putShort(32)
        val records = listOf(header.array(), text, metadata.array(), record.array())
        val pdb = java.nio.ByteBuffer.allocate(78 + 8 * records.size)
        pdb.position(60)
        pdb.put("BOOKMOBI".toByteArray())
        pdb.putShort(76, records.size.toShort())
        pdb.position(78)
        var offset = pdb.capacity()
        for (part in records) {
            pdb.putInt(offset).putInt(0)
            offset += part.size
        }
        val file = File.createTempFile("ciallo-mobi-", ".mobi")
        try {
            file.outputStream().use { out ->
                out.write(pdb.array())
                records.forEach(out::write)
            }
            val entries = mutableMapOf<String, String>()
            extractMobiEntries(file) { word, definition -> entries[word] = definition }
            assertEquals(mapOf("reader" to "<p>A reader.</p>"), entries)
        } finally {
            file.delete()
        }
    }

    private fun mdxBlock(bytes: ByteArray): ByteArray = fixtureBytes { output ->
        output.writeInt(0x02000000)
        output.writeInt(adlerValue(bytes))
        DeflaterOutputStream(output).use { it.write(bytes) }
    }

    private fun fixtureBytes(write: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(write)
        return bytes.toByteArray()
    }

    private fun adlerValue(bytes: ByteArray) = Adler32().apply { update(bytes) }.value.toInt()

    private fun styleAt(book: FormattedBookText, fragment: String): Int {
        val start = book.text.indexOf(fragment)
        assertTrue("Missing " + fragment, start >= 0)
        return book.spans.firstOrNull { start >= it.start && start < it.end }?.style ?: 0
    }
}
