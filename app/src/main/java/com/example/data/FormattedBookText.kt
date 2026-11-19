package com.example.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

data class BookTextSpan(val start: Int, val end: Int, val style: Int)

internal object BookTextStyle {
    const val Bold = 1
    const val Italic = 2
    const val Underline = 4
    const val Strike = 8
    const val Code = 16
    const val Super = 32
    const val Sub = 64
    const val Heading = 128
    const val Quote = 256
    const val Table = 512
}

internal data class FormattedBookText(
    val text: String,
    val spans: List<BookTextSpan> = emptyList(),
    val writingMode: String? = null
) {
    fun chapters(bookId: Int, order: Int, title: String, limit: Int = MAX_CHAPTER_LENGTH): List<Chapter> {
        var offset = 0
        return splitChapterText(text, limit).mapIndexed { index, part ->
            val end = offset + part.length
            val styles = spansIn(spans, offset, end).map {
                BookTextSpan(maxOf(it.start, offset) - offset, minOf(it.end, end) - offset, it.style)
            }.toList()
            offset = end
            Chapter(bookId = bookId, chapterOrder = order + index,
                title = if (index == 0) title else "$title (续${index + 1})",
                content = part, formatting = encodeBookSpans(styles), writingMode = writingMode)
        }
    }
}

internal fun encodeBookSpans(spans: List<BookTextSpan>): String? =
    if (spans.isEmpty()) null else spans.joinToString(";") { "${it.start},${it.end},${it.style}" }

internal fun decodeBookSpans(value: String?): List<BookTextSpan> = value?.splitToSequence(';')
    ?.take(120_000)?.mapNotNull { entry ->
        val fields = entry.split(',', limit = 3)
        if (fields.size != 3) return@mapNotNull null
        val start = fields[0].toIntOrNull() ?: return@mapNotNull null
        val end = fields[1].toIntOrNull() ?: return@mapNotNull null
        val style = fields[2].toIntOrNull() ?: return@mapNotNull null
        if (start >= 0 && end > start && style in 1..1023) BookTextSpan(start, end, style) else null
    }?.toList().orEmpty()

internal fun spansIn(spans: List<BookTextSpan>, start: Int, end: Int): Sequence<BookTextSpan> {
    val found = spans.binarySearchBy(start + 1) { it.end }
    val index = if (found >= 0) found else -found - 1
    return spans.subList(index, spans.size).asSequence().takeWhile { it.start < end }
}

internal fun mergeChapterParts(metadata: Chapter, parts: List<Chapter>): Chapter {
    val text = StringBuilder()
    val spans = buildList {
        parts.forEach { part ->
            val offset = text.length
            addAll(decodeBookSpans(part.formatting).filter { it.end <= part.content.length }.map {
                BookTextSpan(it.start + offset, it.end + offset, it.style)
            })
            text.append(part.content)
        }
    }
    return metadata.copy(content = text.toString(), formatting = encodeBookSpans(spans))
}

private val htmlFormatRule = Regex("""([^{}]+)\{([^{}]+)\}""")
private val bookImageToken = Regex("""\[IMG:[^]]+]""")
private val horizontalSpace = Regex("[\\t\\n\\r\\u000c ]+")
private val cssComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val cssDisplayNone = Regex("""(?i)(?:^|;)\s*display\s*:\s*none\b""")
private val cssWritingMode = Regex("""(?i)(?:^|;)\s*(?:-(?:epub|webkit)-)?writing-mode\s*:\s*(vertical-rl|vertical-lr|horizontal-tb|tb-rl|tb-lr)\b""")

internal fun normalizeWritingMode(value: String?): String? = when (value?.trim()?.lowercase()?.replace('_', '-')) {
    "vertical-rl", "tb-rl" -> "vertical-rl"
    "vertical-lr", "tb-lr" -> "vertical-lr"
    "horizontal-tb", "horizontal-lr", "horizontal-rl", "lr-tb" -> "horizontal-tb"
    else -> null
}

internal fun readBookHtml(html: String, styleSheets: List<String> = emptyList(), defaultWritingMode: String? = null): FormattedBookText {
    val document = Jsoup.parse(html)
    val body = document.body()
    val styleRules = (styleSheets + document.select("style").map { it.data() }).asSequence().flatMap {
        htmlFormatRule.findAll(cssComment.replace(it.take(512_000), "")).asSequence()
    }.take(800).toList()
    val cssByElement = mutableMapOf<Element, StringBuilder>()
    for (rule in styleRules) {
        val selector = rule.groupValues[1].trim()
        val declarations = rule.groupValues[2].trim()
        if (selector.isBlank() || selector.startsWith('@') || declarations.length > 4096) continue
        if (cssFlags(declarations, 0) == 0 && cssWritingMode.find(declarations) == null &&
            !declarations.contains("normal", true) && !declarations.contains("display", true)) continue
        runCatching { document.select(selector) }.getOrNull()?.forEach {
            cssByElement.getOrPut(it, ::StringBuilder).append(';').append(declarations)
        }
    }
    fun css(element: Element): String = (cssByElement[element]?.toString() ?: "") + ";" + element.attr("style")
    fun mode(element: Element): String? = normalizeWritingMode(cssWritingMode.findAll(css(element)).lastOrNull()?.groupValues?.get(1))

    val epubMeta = document.select("meta[name]").lastOrNull {
        it.attr("name").equals("primary-writing-mode", true)
    }?.attr("content")
    val writingMode = mode(body) ?: document.selectFirst("html")?.let(::mode)
        ?: body.children().singleOrNull()?.let(::mode) ?: normalizeWritingMode(epubMeta) ?: defaultWritingMode
    document.select("script, style, template, iframe, object, noscript, rt, rp, [hidden]").remove()
    val text = StyledTextBuilder()
    fun visit(element: Element, inherited: Int, pre: Boolean) {
        val tag = element.normalName()
        val computed = css(element)
        if (cssDisplayNone.containsMatchIn(computed)) return
        val flags = cssFlags(computed, inherited or tagFlags(tag))
        val preserve = pre || tag == "pre"
        if (tag == "br") { text.breakLine(false); return }
        if (tag == "hr") { text.breakLine(true); text.append("────────", 0); text.breakLine(true); return }
        if (element.isBlock && tag !in tableCells) text.breakLine(tag !in compactBlocks)
        if (tag in tableCells && element.elementSiblingIndex() > 0) text.append(" │ ", BookTextStyle.Table)
        if (tag == "li") {
            val parent = element.parent()
            val prefix = if (parent?.normalName() == "ol") {
                val first = parent.attr("start").toIntOrNull() ?: 1
                val number = element.attr("value").toIntOrNull() ?: first + element.elementSiblingIndex()
                "$number. "
            } else "• "
            text.append(prefix, flags)
        }
        for (node in element.childNodes()) when (node) {
            is TextNode -> text.append(if (preserve) node.wholeText.replace("\r\n", "\n") else collapseBookWhitespace(node.wholeText), flags, preserve)
            is Element -> visit(node, flags, preserve)
        }
        if (element.isBlock && tag !in tableCells) text.breakLine(tag !in compactBlocks)
    }
    visit(body, 0, false)
    return text.build(writingMode)
}

private fun collapseBookWhitespace(text: String): String {
    if (!text.contains("[IMG:")) return horizontalSpace.replace(text, " ")
    return buildString {
        var offset = 0
        for (image in bookImageToken.findAll(text)) {
            append(horizontalSpace.replace(text.substring(offset, image.range.first), " "))
            append(image.value)
            offset = image.range.last + 1
        }
        append(horizontalSpace.replace(text.substring(offset), " "))
    }
}

private val tableCells = setOf("td", "th")
private val compactBlocks = setOf("li", "tr", "thead", "tbody", "tfoot")

private fun tagFlags(tag: String): Int = with(BookTextStyle) {
    when (tag) {
        "b", "strong", "th" -> Bold
        "em", "i", "cite", "emphasis" -> Italic
        "u", "ins" -> Underline
        "del", "s", "strike", "strikethrough" -> Strike
        "tt", "pre", "code", "kbd", "samp" -> Code
        "sup" -> Super
        "sub" -> Sub
        "h1", "h2", "h3", "h4", "h5", "h6" -> Heading or Bold
        "blockquote" -> Quote
        "table" -> Table
        else -> 0
    }
}

private fun cssFlags(css: String, inherited: Int): Int {
    var flags = inherited
    for (declaration in css.split(';')) {
        val key = declaration.substringBefore(':').trim().lowercase()
        val value = declaration.substringAfter(':', "").substringBefore('!').trim().lowercase()
        with(BookTextStyle) {
            when (key) {
                "font-weight" -> flags = when {
                    value == "bold" || value == "bolder" || (value.toIntOrNull() ?: 0) >= 600 -> flags or Bold
                    value == "normal" || (value.toIntOrNull() ?: 0) in 100..500 -> flags and Bold.inv()
                    else -> flags
                }
                "font-style" -> flags = when {
                    value.startsWith("italic") || value.startsWith("oblique") -> flags or Italic
                    value == "normal" -> flags and Italic.inv()
                    else -> flags
                }
                "text-decoration", "text-decoration-line" -> {
                    if ("underline" in value) flags = flags or Underline
                    if ("line-through" in value) flags = flags or Strike
                }
                "font-family" -> if ("monospace" in value) flags = flags or Code
                "vertical-align" -> if (value == "super") flags = flags or Super else if (value == "sub") flags = flags or Sub
            }
        }
    }
    return flags
}

internal class StyledTextBuilder {
    private val buffer = StringBuilder()
    private val spans = mutableListOf<BookTextSpan>()
    private var pendingBreak = 0

    fun breakLine(paragraph: Boolean) {
        pendingBreak = maxOf(pendingBreak, if (paragraph) 2 else 1)
    }

    fun append(text: String, style: Int, preserve: Boolean = false) {
        var value = text
        if (!preserve && (pendingBreak > 0 || buffer.isEmpty() || buffer.last() == ' ')) value = value.trimStart(' ')
        if (value.isEmpty()) return
        if (pendingBreak > 0 && buffer.isNotEmpty()) {
            while (buffer.lastOrNull() == ' ') buffer.setLength(buffer.length - 1)
            repeat(pendingBreak) { buffer.append('\n') }
        }
        pendingBreak = 0
        val start = buffer.length
        buffer.append(value)
        if (style != 0) {
            val previous = spans.lastOrNull()
            if (previous?.style == style && previous.end >= start) {
                spans[spans.lastIndex] = previous.copy(end = buffer.length)
            } else {
                if (previous != null && previous.end > start) {
                    if (previous.start < start) spans[spans.lastIndex] = previous.copy(end = start)
                    else spans.removeAt(spans.lastIndex)
                }
                spans.add(BookTextSpan(start, buffer.length, style))
            }
        }
    }

    fun append(text: FormattedBookText) {
        var offset = 0
        for (span in text.spans) {
            if (span.start > offset) append(text.text.substring(offset, span.start), 0, preserve = true)
            append(text.text.substring(span.start, span.end), span.style, preserve = true)
            offset = span.end
        }
        if (offset < text.text.length) append(text.text.substring(offset), 0, preserve = true)
    }

    fun build(mode: String? = null): FormattedBookText {
        val text = buffer.toString().trimEnd()
        return FormattedBookText(text, spans.filter { it.start < text.length }.map { it.copy(end = minOf(it.end, text.length)) }, mode)
    }
}

internal fun compatibleReaderColumns(table: String, actual: Set<String>, expected: Set<String>): Boolean {
    val optional = when (table) {
        "chapters" -> setOf("formatting", "writingMode")
        "highlights" -> setOf("startOffset", "endOffset", "markStyle")
        else -> emptySet()
    }
    return expected.containsAll(actual) && (expected - actual).all { it in optional }
}

