package com.example.data

data class SpokenParagraph(val text: String, val start: Int, val end: Int)

data class ListeningChapter(
    val bookKey: String,
    val chapterKey: String,
    val bookTitle: String,
    val title: String,
    val paragraphs: List<SpokenParagraph>
)

data class ListeningState(
    val chapter: ListeningChapter? = null,
    val paragraph: Int = 0,
    val playing: Boolean = false,
    val preparing: Boolean = false,
    val engineReady: Boolean = false,
    val error: String? = null,
    val completion: Long = 0,
    val finished: Boolean = false,
    val timer: Int = 0,
    val buffering: String? = null
)

private val inlineImage = Regex("\\[IMG:[^\\]]*]")
private val paragraphLine = Regex("[^\\r\\n]+")

internal fun spokenParagraphs(content: String): List<SpokenParagraph> =
    paragraphLine.findAll(content).mapNotNull { line ->
        val speech = inlineImage.replace(line.value, "").trim()
        if (speech.isEmpty()) null else SpokenParagraph(speech, line.range.first, line.range.last + 1)
    }.toList()

internal fun List<SpokenParagraph>.paragraphAt(offset: Int): Int {
    val index = binarySearchBy(offset) { it.start }
    return if (index >= 0) index else (-index - 2).coerceAtLeast(0)
}

internal fun formatNovelReadingText(text: String, indent: Boolean): String =
    if (indent) text.split('\n').joinToString("\n") { line ->
        if (line.isNotBlank() && !line.startsWith("\u3000\u3000")) "\u3000\u3000" + line else line
    } else text
