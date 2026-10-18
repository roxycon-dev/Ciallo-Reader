package com.example.source

import java.math.BigDecimal
import java.text.Normalizer

/** Episode identity comes from an explicit title, never the catalogue's row number. */
object ComicChapterTitle {
    private const val numeral = "[0-9零〇一二两兩三四五六七八九十百千]+(?:\\.[0-9]+)?"
    private val episode = Regex("^(?:第\\s*)?($numeral)\\s*[话話章回]")
    private val english = Regex("^(?:chapter|ch\\.?|episode|ep\\.?)\\s*([0-9]+(?:\\.[0-9]+)?)(?=$|[^\\p{L}\\p{N}.]|\\.(?![0-9]))", RegexOption.IGNORE_CASE)
    private val bare = Regex("^([0-9]+(?:\\.[0-9]+)?)(?=$|\\s|[:：])")
    private val special = Regex("番外|特別|特别|特典|附录|附錄|外传|外傳|小剧场|小劇場|extra|special|omake|side\\s*story|bonus", RegexOption.IGNORE_CASE)
    private val notice = Regex("公告|通知|告知|停更|休刊|休载|休載|休更|请假|請假|延期|暂停更新|暫停更新|notice|announcement|hiatus|update\\s*pause", RegexOption.IGNORE_CASE)

    fun number(raw: String): BigDecimal? {
        val title = Normalizer.normalize(raw.take(512), Normalizer.Form.NFKC).trim()
        if (special.containsMatchIn(title) || notice.containsMatchIn(title)) return null
        val token = (episode.find(title) ?: english.find(title) ?: bare.find(title))
            ?.groupValues?.get(1) ?: return null
        if (token.length > 16) return null
        token.toBigDecimalOrNull()?.let { return it.stripTrailingZeros() }
        val digits = "零一二三四五六七八九"
        fun digit(c: Char): Int? = when (c) {
            '〇' -> 0
            '两', '兩' -> 2
            else -> digits.indexOf(c).takeIf { it >= 0 }
        }
        if (token.all { digit(it) != null }) return token.map { digit(it) }.joinToString("").toBigDecimalOrNull()
        var total = 0
        var current = 0
        for (c in token) {
            val value = digit(c)
            if (value != null) current = value else {
                val unit = when (c) { '十' -> 10; '百' -> 100; '千' -> 1000; else -> return null }
                total += (if (current == 0) 1 else current) * unit
                current = 0
            }
        }
        return (total + current).toBigDecimal()
    }

    fun numberText(title: String): String? = number(title)?.toPlainString()

    fun label(title: String): String = numberText(title)?.let { "第 $it 话" }
        ?: title.trim().ifBlank { "未命名章节" }
}
