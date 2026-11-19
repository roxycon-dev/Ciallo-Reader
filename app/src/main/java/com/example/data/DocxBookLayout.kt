package com.example.data

import android.graphics.BitmapFactory
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

internal data class DocxParagraph(val text: FormattedBookText, val isHeading: Boolean = false, val compact: Boolean = false)

private fun Element.wordTag() = normalName().substringAfter(':')
private fun Element.childNamed(name: String) = children().firstOrNull { it.wordTag() == name }
private fun Element.wordValue(name: String = "val") = attributes().firstOrNull {
    it.key.substringAfter(':').equals(name, true)
}?.value.orEmpty()
private fun wordDocument(xml: String) = Jsoup.parse(xml, "", Parser.xmlParser())

internal fun docxParagraphs(xml: String, stylesXml: String, numberingXml: String, images: Map<String, String>): List<DocxParagraph> {
    val styleIndex = wordDocument(stylesXml).getAllElements().filter { it.wordTag() == "style" }
        .associateBy { it.wordValue("styleId") }
    val definitions = wordDocument(numberingXml).getAllElements()
    val numbering = definitions.filter { it.wordTag() == "num" }.associate {
        it.wordValue("numId") to it.childNamed("abstractnumid")?.wordValue()
    }
    val listKinds = definitions.filter { it.wordTag() == "abstractnum" }.associate { definition ->
        definition.wordValue("abstractNumId") to definition.children().filter { it.wordTag() == "lvl" }
            .associate { it.wordValue("ilvl") to (it.childNamed("numfmt")?.wordValue() to it.childNamed("start")?.wordValue()?.toIntOrNull()) }
    }
    val counters = mutableMapOf<Pair<String, String>, Int>()

    fun styleFlags(id: String?, depth: Int = 0): Int {
        if (depth >= 8 || id.isNullOrEmpty()) return 0
        val style = styleIndex[id] ?: return 0
        return wordRunFlags(style.childNamed("rpr"), styleFlags(style.childNamed("basedon")?.wordValue(), depth + 1))
    }

    fun paragraph(node: Element): DocxParagraph {
        val properties = node.childNamed("ppr")
        val style = properties?.childNamed("pstyle")?.wordValue()
        val heading = style?.startsWith("Heading", true) == true ||
            (properties?.childNamed("outlinelvl")?.wordValue()?.toIntOrNull() ?: 9) in 0..8
        val inherited = styleFlags(style) or if (heading) BookTextStyle.Heading or BookTextStyle.Bold else 0
        val text = StyledTextBuilder()
        val num = properties?.childNamed("numpr")
        val number = num?.childNamed("numid")?.wordValue()?.takeUnless { it.isEmpty() || it == "0" }
        if (number != null) {
            val level = num?.childNamed("ilvl")?.wordValue() ?: "0"
            val (kind, start) = listKinds[numbering[number]]?.get(level) ?: (null to null)
            val key = number to level
            val count = counters.getOrElse(key) { start ?: 1 }
            counters[key] = count + 1
            text.append(if (kind == "decimal") "$count. " else "• ", inherited, preserve = true)
        }
        for (run in node.getAllElements().filter { it.wordTag() == "r" }) {
            val props = run.childNamed("rpr")
            val flags = wordRunFlags(props, inherited or styleFlags(props?.childNamed("rstyle")?.wordValue()))
            for (part in run.children()) when (part.wordTag()) {
                "t" -> text.append(part.wholeText(), flags, preserve = true)
                "tab" -> text.append("    ", flags, preserve = true)
                "br", "cr" -> text.append("\n", flags, preserve = true)
                "drawing", "pict" -> {
                    part.getAllElements().firstOrNull { it.wordTag() == "blip" }
                        ?.wordValue("embed")?.let(images::get)?.let { path ->
                            val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(path, size)
                            if (size.outWidth > 0 && size.outHeight > 0) {
                                text.breakLine(true)
                                text.append("[IMG:$path|" + size.outWidth + "|" + size.outHeight + "]", 0)
                                text.breakLine(true)
                            }
                        }
                }
            }
        }
        return DocxParagraph(text.build(), heading, number != null)
    }

    val body = wordDocument(xml).getAllElements().firstOrNull { it.wordTag() == "body" }
    return buildList {
        for (element in body?.children().orEmpty()) when (element.wordTag()) {
            "p" -> add(paragraph(element))
            "tbl" -> for (row in element.children().filter { it.wordTag() == "tr" }) {
                val text = StyledTextBuilder()
                row.children().filter { it.wordTag() == "tc" }.forEachIndexed { index, cell ->
                    if (index > 0) text.append(" │ ", BookTextStyle.Table, preserve = true)
                    cell.children().filter { it.wordTag() == "p" }.forEachIndexed { pos, cellParagraph ->
                        if (pos > 0) text.append(" / ", BookTextStyle.Table, preserve = true)
                        val value = paragraph(cellParagraph).text
                        text.append(value.copy(spans = tableSpans(value)))
                    }
                }
                add(DocxParagraph(text.build(), compact = true))
            }
        }
    }.filter { it.text.text.isNotBlank() || it.isHeading }
}

private fun tableSpans(text: FormattedBookText): List<BookTextSpan> = buildList {
    var offset = 0
    for (span in text.spans) {
        if (offset < span.start) add(BookTextSpan(offset, span.start, BookTextStyle.Table))
        add(span.copy(style = span.style or BookTextStyle.Table))
        offset = span.end
    }
    if (offset < text.text.length) add(BookTextSpan(offset, text.text.length, BookTextStyle.Table))
}

private fun wordRunFlags(properties: Element?, inherited: Int): Int {
    var flags = inherited
    for (element in properties?.children().orEmpty()) {
        val value = element.wordValue().lowercase()
        val enabled = value !in setOf("0", "false", "off", "none")
        val flag = when (element.wordTag()) {
            "b", "bcs" -> BookTextStyle.Bold
            "i", "ics" -> BookTextStyle.Italic
            "u" -> BookTextStyle.Underline
            "strike", "dstrike" -> BookTextStyle.Strike
            "vertalign" -> when (value) {
                "superscript" -> BookTextStyle.Super
                "subscript" -> BookTextStyle.Sub
                else -> 0
            }
            else -> 0
        }
        flags = if (enabled) flags or flag else flags and flag.inv()
    }
    return flags
}

