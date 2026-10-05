package com.example.source.keyword

import com.example.source.anilist.TitleNormalizer
import java.util.Locale

/** Metadata changes requests only. It never filters a source's returned books. */
internal data class KeywordName(val text: String, val language: String)
internal data class KeywordConcept(
    val id: String,
    val names: List<KeywordName>,
    val source: String,
) {
    val kind: String get() = when {
        id.startsWith("seed:common:") || id.startsWith("ehtag:female:") ||
            id.startsWith("ehtag:male:") || id.startsWith("ehtag:mixed:") || id.startsWith("ehtag:other:") -> "common"
        id.contains(":character:") -> "character"
        id.contains(":artist:") || id.contains(":person:") || id.contains(":author:") || id.contains(":staff:") || id.contains(":creator:") -> "person"
        id.contains(":parody:") || id.contains(":work:") || id.contains(":media:") || id.contains(":subject:") -> "work"
        else -> "unknown"
    }
}

internal data class KeywordProviderState(val provider: String, val outcome: String, val retryAt: Long = 0,
    val detail: String = "") {
    val displayName: String get() = when (provider) {
        "wikidata" -> "Wikidata"
        "bangumi:subjects" -> "Bangumi 作品"
        "bangumi:characters" -> "Bangumi 角色"
        "bangumi:persons" -> "Bangumi 人物"
        "anilist" -> "AniList"
        else -> "名称查询"
    }
}
internal data class OnlineKeywordResult(
    val concepts: List<KeywordConcept>,
    val states: List<KeywordProviderState> = emptyList(),
    val cached: Boolean = false,
    val persisted: Boolean = false,
)
internal data class KeywordUpdate(
    val added: List<String> = emptyList(),
    val status: String,
    val origins: Map<String, String> = emptyMap(),
    val providers: List<KeywordProviderState> = emptyList(),
)

/** Completeness belongs to one entity, never the union of unrelated search candidates. */
internal object KeywordConcepts {
    fun complete(concepts: List<KeywordConcept>): Boolean = concepts.isNotEmpty() && concepts.all { c ->
        val languages = c.names.map { it.language }.toSet()
        val native = "ja" in languages || "native" in languages
        native && if (c.kind == "work" || c.kind == "common") "en" in languages
            else languages.any { it in setOf("en", "romaji", "latin") }
    }

    fun merge(concepts: List<KeywordConcept>): List<KeywordConcept> {
        val merged = mutableListOf<KeywordConcept>()
        for (concept in concepts) {
            val index = merged.indexOfFirst { old ->
                val shared = old.names.map { KeywordKeys.match(it.text) }.toSet()
                    .intersect(concept.names.map { KeywordKeys.match(it.text) }.toSet()).filter { it.isNotBlank() }
                val unknownBridge = (old.kind == "unknown") != (concept.kind == "unknown") && shared.size >= 2
                old.id == concept.id || unknownBridge || (old.kind == concept.kind && old.kind != "unknown" &&
                    old.names.any { a -> a.language != "zh" && a.language != "alias" && concept.names.any { b ->
                        b.language != "zh" && b.language != "alias" && KeywordKeys.match(a.text) == KeywordKeys.match(b.text)
                    } })
            }
            if (index < 0) merged += concept else {
                val old = merged[index]
                val primary = if (old.kind == "unknown" && concept.kind != "unknown") concept else old
                merged[index] = primary.copy(names = (old.names + concept.names).distinct(),
                    source = (old.source.split(" + ") + concept.source.split(" + ")).distinct().joinToString(" + "))
            }
        }
        return merged
    }
}

internal object KeywordKeys {
    fun match(text: String): String = TitleNormalizer.compact(text)

    // Never fold CJK, remove punctuation or remove Japanese vowel marks here.
    fun query(text: String): String = text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun language(text: String): String = when {
        text.any { it in '\u3040'..'\u30ff' } -> "ja"
        text.any { it in '\u4e00'..'\u9fff' } -> "zh"
        text.any { it.isLetter() } -> "en"
        else -> "und"
    }

    // Preserve an explicitly written search expression instead of silently rewriting it.
    fun isPlain(text: String): Boolean = text.length in 1..160 &&
        !Regex("[\"$\\\\]|(?:^|\\s)[-~]|[a-zA-Z]+:").containsMatchIn(text)
}

/** A Chinese given name separated from a surname is a reliable short form.
 * Do not turn arbitrary prefixes, surnames or substrings into aliases. */
internal object KeywordPersonNames {
    fun shortForms(text: String): List<String> {
        val parts = text.split('·', '・', '•', '‧', '･').map(String::trim)
        if (parts.size < 2) return emptyList()
        // Only the first name, never the family name after the separator.
        return listOfNotNull(parts.first().takeIf { it.length in 2..6 && it.all { c -> c in '\u4e00'..'\u9fff' } })
    }

    fun rank(names: List<KeywordName>, keys: Set<String>, allowShortName: Boolean): Int? {
        if (names.any { KeywordKeys.match(it.text) in keys }) return 0
        if (allowShortName && names.filter { it.language == "zh" }.any { name ->
                shortForms(name.text).any { KeywordKeys.match(it) in keys }
            }) return 1
        return null
    }
}

internal object KeywordVariants {
    const val LIMIT = 6

    /** Whole names win over splitting (e.g. a title containing spaces). */
    fun compose(input: String, parts: List<String>, concepts: List<List<KeywordConcept>>): List<KeywordName> {
        if (parts.size == 1) return concepts.single().flatMap { it.names }
        val out = mutableListOf<KeywordName>()
        for (lang in listOf("en", "ja", "romaji", "zh")) {
            var mapped = false
            val words = parts.mapIndexed { i, original ->
                val names = concepts[i].flatMap { it.names }.filter { KeywordKeys.query(it.text) != KeywordKeys.query(original) }
                (names.firstOrNull { it.language == lang } ?: names.firstOrNull {
                    (lang == "ja" && it.language == "native") || (lang == "en" && it.language == "latin")
                })?.text?.also { mapped = true } ?: original
            }
            if (mapped) out += KeywordName(words.joinToString(" "), lang)
        }
        return out
    }

    fun prioritized(names: List<KeywordName>): List<KeywordName> {
        // Resolve labels before textual deduplication, so a Chinese alias cannot hide
        // an explicitly identified Japanese/native spelling with the same text.
        val order = listOf("en", "ja", "native", "romaji", "latin", "alias", "zh", "und")
        val distinct = names.filter { it.text.isNotBlank() && it.text.length <= 160 }
            .groupBy { KeywordKeys.query(it.text) }.values.map { same ->
                same.minBy { order.indexOf(it.language).takeIf { rank -> rank >= 0 } ?: 99 }
            }
        val groups = distinct.groupBy { if (it.language == "native") "ja" else it.language }.mapValues { (language, rows) ->
            if (language == "alias") rows.sortedBy { name ->
                (if (name.text.all { it.code < 128 } && name.text.length <= 40) 0 else 1000) + name.text.length
            } else rows
        }
        val priorities = (order + groups.keys).distinct()
        return buildList {
            for (i in 0 until (groups.values.maxOfOrNull { it.size } ?: 0)) {
                for (lang in priorities) groups[lang]?.getOrNull(i)?.let(::add)
            }
        }
    }
}
