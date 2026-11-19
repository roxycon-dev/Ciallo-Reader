package com.example.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.example.data.BookTextSpan
import com.example.data.BookTextStyle
import com.example.data.Chapter
import com.example.data.decodeBookSpans
import com.example.data.spansIn

internal fun layoutBookSpans(chapter: Chapter?, indent: Boolean): List<BookTextSpan> {
    val spans = decodeBookSpans(chapter?.formatting)
    val text = chapter?.content.orEmpty()
    if (!indent || spans.isEmpty()) return spans
    val coordinates = ReaderTextCoordinates(text, indent)
    return spans.map {
        it.copy(start = coordinates.display(it.start), end = coordinates.display(it.end, includeIndent = false))
    }
}

internal fun bookSpanRanges(spans: List<BookTextSpan>, offset: Int, length: Int): List<AnnotatedString.Range<SpanStyle>> =
    spansIn(spans, offset, offset + length).map { span ->
        AnnotatedString.Range(bookSpanStyle(span.style), maxOf(span.start - offset, 0), minOf(span.end - offset, length))
    }.filter { it.start < it.end }.toList()

internal fun AnnotatedString.Builder.addBookSpans(spans: List<BookTextSpan>, offset: Int, length: Int) {
    for (range in bookSpanRanges(spans, offset, length)) addStyle(range.item, range.start, range.end)
}

private fun bookSpanStyle(flags: Int): SpanStyle = with(BookTextStyle) {
    fun has(flag: Int) = flags and flag != 0
    val decoration = buildList {
        if (has(Underline)) add(TextDecoration.Underline)
        if (has(Strike)) add(TextDecoration.LineThrough)
    }
    SpanStyle(
        fontWeight = FontWeight.Bold.takeIf { has(Bold) },
        fontStyle = FontStyle.Italic.takeIf { has(Italic) || has(Quote) },
        fontFamily = FontFamily.Monospace.takeIf { has(Code) || has(Table) },
        fontSize = when {
            has(Heading) -> 1.18.em
            has(Super) || has(Sub) -> .75.em
            else -> TextUnit.Unspecified
        },
        baselineShift = when {
            has(Super) -> BaselineShift.Superscript
            has(Sub) -> BaselineShift.Subscript
            else -> null
        },
        textDecoration = if (decoration.isEmpty()) null else TextDecoration.combine(decoration)
    )
}
