package com.example.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.example.data.SpokenParagraph

internal fun AnnotatedString.Builder.addListeningHighlight(
    paragraph: SpokenParagraph,
    blockStart: Int,
    blockLength: Int,
    color: Color
) {
    val start = (paragraph.start - blockStart).coerceAtLeast(0)
    val end = (paragraph.end - blockStart).coerceAtMost(blockLength)
    if (start < end) addStyle(SpanStyle(background = color), start, end)
}
