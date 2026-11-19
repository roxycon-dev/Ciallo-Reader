package com.example.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import com.example.data.Highlight

internal class ReaderTextCoordinates(text: String, indent: Boolean) {
    private val insertions = if (!indent) emptyList() else buildList {
        var start = 0
        for (line in text.split('\n')) {
            if (line.isNotBlank() && !line.startsWith("\u3000\u3000")) add(start)
            start += line.length + 1
        }
    }

    fun display(raw: Int, includeIndent: Boolean = true): Int {
        val match = insertions.binarySearch(raw)
        val shift = if (match >= 0) match + if (includeIndent) 1 else 0 else -match - 1
        return raw + shift * 2
    }

    fun raw(display: Int): Int {
        var low = 0
        var high = insertions.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (insertions[mid] + mid * 2 < display) low = mid + 1 else high = mid
        }
        val last = low - 1
        if (last < 0) return display
        return maxOf(insertions[last], display - low * 2)
    }
}

internal fun Highlight.toReaderMark(content: String, coordinates: ReaderTextCoordinates): ReaderMark? {
    if (selectedText.isBlank()) return null
    val start = startOffset
    val end = endOffset
    val selected = if (start != null && end != null && start >= 0 && end in (start + 1)..content.length &&
        content.substring(start, end) == selectedText) {
        start to end
    } else {
        val found = content.indexOf(selectedText)
        if (found < 0 || content.indexOf(selectedText, found + 1) >= 0) return null
        found to (found + selectedText.length)
    }
    val color = runCatching { Color(android.graphics.Color.parseColor(colorHex)) }.getOrDefault(Color(0xff71ccba))
    return ReaderMark(coordinates.display(selected.first), coordinates.display(selected.second, includeIndent = false),
        markStyle == "underline", color)
}

internal fun AnnotatedString.Builder.addReaderMarks(marks: List<ReaderMark>, offset: Int, length: Int) {
    for (mark in marks) {
        val start = (mark.start - offset).coerceAtLeast(0)
        val end = (mark.end - offset).coerceAtMost(length)
        if (start >= end) continue
        val span = if (mark.underline) SpanStyle(textDecoration = TextDecoration.Underline)
            else SpanStyle(background = mark.color.copy(alpha = .30f))
        addStyle(span, start, end)
    }
}
