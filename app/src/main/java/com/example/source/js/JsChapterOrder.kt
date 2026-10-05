package com.example.source.js

import com.example.source.ComicChapter
import java.math.BigDecimal
import java.text.Normalizer

/** Convert a JS catalogue to reading order once, before caching or handing it to consumers. */
internal object JsChapterOrder {
    private const val numeral = "[0-9零〇一二两兩三四五六七八九十百千]+(?:\\.[0-9]+)?"
    private val episode = Regex("^(?:第\\s*)?($numeral)\\s*[话話章回]")
    private val englishEpisode = Regex("^(?:chapter|ch\\.?|episode|ep\\.?)\\s*([0-9]+(?:\\.[0-9]+)?)(?=$|\\s|[:：.(（-])", RegexOption.IGNORE_CASE)
    private val bareEpisode = Regex("^([0-9]+(?:\\.[0-9]+)?)(?=$|\\s|[:：])")
    private val volume = Regex("^(?:(?:单行本|單行本)\\s*)?(?:第\\s*)?($numeral)\\s*[卷巻册冊]$")
    private val englishVolume = Regex("^(?:vol(?:ume)?\\.?|book)\\s*([0-9]+)$", RegexOption.IGNORE_CASE)
    private val special = Regex("番外|特別|特别|附录|附錄|extra|special|omake", RegexOption.IGNORE_CASE)

    fun normalize(
        chapters: List<ComicChapter>,
        sourceKey: String,
        hasExplicitOrder: Boolean = chapters.any { it.order != 0f },
    ): List<ComicChapter> {
        if (chapters.isEmpty()) return chapters
        // Metadata really supplied by the source takes priority over numbers in its titles.
        if (hasExplicitOrder && chapters.all { it.order.isFinite() } &&
            chapters.map { it.order }.distinct().size > 1) {
            return chapters.sortedBy { it.order }
        }

        val defaultDescending = sourceKey.removePrefix("js_").lowercase() == "manhuaren"
        fun groupKey(chapter: ComicChapter) = chapter.volume?.trim()?.ifBlank { null }
        val groups = chapters.groupBy(::groupKey)
        val sequences = groups.mapValues { (_, group) ->
            if (descending(group) ?: defaultDescending) reverseBlocks(group) else group
        }

        // Only explicitly numbered, contiguous volumes may change their group order.
        // Named groups (serial/collected edition/extras) keep the source's layout.
        val numberedVolumes = groups.keys.map { name -> name?.let(::volumeNumber) }
        val contiguous = chapters.zipWithNext().count { (a, b) -> groupKey(a) != groupKey(b) } + 1 == groups.size
        val sequence = if (groups.size > 1 && contiguous && numberedVolumes.all { it != null } &&
            numberedVolumes.filterNotNull().distinct().size == groups.size) {
            groups.keys.sortedBy { volumeNumber(it!!) }.flatMap { sequences.getValue(it) }
        } else {
            // Retain even interleaved group slots instead of gathering unrelated editions together.
            val iterators = sequences.mapValues { it.value.iterator() }
            chapters.map { iterators.getValue(groupKey(it)).next() }
        }
        return sequence.mapIndexed { index, chapter -> chapter.copy(order = index.toFloat()) }
    }

    private fun descending(chapters: List<ComicChapter>): Boolean? {
        val numbers = chapters.mapNotNull { episodeNumber(it.title) }
        var ascending = 0
        var descending = 0
        numbers.zipWithNext().forEach { (a, b) ->
            when {
                a < b -> ascending++
                a > b -> descending++
            }
        }
        val comparisons = ascending + descending
        if (comparisons == 0 || numbers.first().compareTo(numbers.last()) == 0) return null
        // Require a consistent trend across the catalogue, not merely its first and last rows.
        return when {
            descending.toDouble() / comparisons >= 0.8 && numbers.first() > numbers.last() -> true
            ascending.toDouble() / comparisons >= 0.8 && numbers.first() < numbers.last() -> false
            else -> null
        }
    }

    /** Reverse episode blocks without swapping upper/lower parts of the same episode. */
    private fun reverseBlocks(chapters: List<ComicChapter>): List<ComicChapter> {
        val blocks = mutableListOf<MutableList<ComicChapter>>()
        var previous: BigDecimal? = null
        chapters.forEach { chapter ->
            val number = episodeNumber(chapter.title)
            val prior = previous
            if (number != null && prior != null && number.compareTo(prior) == 0) {
                blocks.last().add(chapter)
            } else {
                blocks.add(mutableListOf(chapter))
            }
            previous = number
        }
        return blocks.asReversed().flatten()
    }

    private fun clean(raw: String): String = Normalizer.normalize(raw.take(512), Normalizer.Form.NFKC).trim()

    private fun episodeNumber(raw: String): BigDecimal? {
        val title = clean(raw)
        if (special.containsMatchIn(title)) return null
        val token = episode.find(title)?.groupValues?.get(1)
            ?: englishEpisode.find(title)?.groupValues?.get(1)
            ?: volume.matchEntire(title)?.groupValues?.get(1)
            ?: englishVolume.matchEntire(title)?.groupValues?.get(1)
            ?: bareEpisode.find(title)?.groupValues?.get(1)
        return token?.let(::number)
    }

    private fun volumeNumber(raw: String): BigDecimal? {
        val title = clean(raw)
        return (volume.matchEntire(title) ?: englishVolume.matchEntire(title))
            ?.groupValues?.get(1)?.let(::number)
    }

    private fun number(token: String): BigDecimal? {
        if (token.length > 16) return null
        token.toBigDecimalOrNull()?.let { return it }
        val digits = "零一二三四五六七八九"
        fun digit(char: Char): Int? = when (char) {
            '〇' -> 0
            '两', '兩' -> 2
            else -> digits.indexOf(char).takeIf { it >= 0 }
        }
        if (token.all { digit(it) != null }) {
            return token.map { digit(it) }.joinToString("").toBigDecimalOrNull()
        }
        var total = 0
        var current = 0
        token.forEach { char ->
            val value = digit(char)
            if (value != null) current = value else {
                val unit = when (char) { '十' -> 10; '百' -> 100; '千' -> 1000; else -> return null }
                total += (if (current == 0) 1 else current) * unit
                current = 0
            }
        }
        return (total + current).toBigDecimal()
    }
}
