package com.example.data.favorite

import com.example.source.ComicChapter
import com.example.source.ComicChapterTitle
import com.example.source.anilist.TitleNormalizer
import java.text.Normalizer

/** Cross-source identity uses unique titles or explicit episode numbers, never list positions or IDs. */
object ComicChapterMatching {
    fun mapping(old: List<ComicChapter>, fresh: List<ComicChapter>): Map<String, ComicChapter> {
        val result = linkedMapOf<String, ComicChapter>()
        val used = hashSetOf<String>()
        fun pair(key: (ComicChapter) -> String?) {
            val oldGroups = old.mapNotNull { c -> key(c)?.let { it to c } }.groupBy({ it.first }, { it.second })
            val newGroups = fresh.mapNotNull { c -> key(c)?.let { it to c } }.groupBy({ it.first }, { it.second })
            oldGroups.forEach { (identity, group) ->
                val to = newGroups[identity]?.singleOrNull()
                val from = group.singleOrNull()
                if (from != null && to != null && from.id !in result && to.id !in used && compatibleVolumes(from, to)) {
                    result[from.id] = to
                    used.add(to.id)
                }
            }
        }
        pair { c -> TitleNormalizer.compact(c.title).takeIf { it.isNotBlank() }?.let { "${volume(c)}|$it" } }
        pair { c -> number(c.title)?.let { "${volume(c)}|$it" } }
        // Some sources omit volume names. Only globally unique numbers may bridge that omission.
        pair { c -> number(c.title) }
        return result
    }

    private fun volume(c: ComicChapter): String {
        val inline = Regex("(?i)第\\s*(\\d+)\\s*[卷巻]|vol(?:ume)?\\.?\\s*(\\d+)").find(c.title)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
        val raw = c.volume?.trim()?.takeIf { it.isNotBlank() } ?: inline.orEmpty()
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val digits = Regex("(?i)^(?:第\\s*)?(\\d+)(?:\\s*[卷巻])?$|^(?:vol(?:ume)?\\.?\\s*)(\\d+)$").matchEntire(normalized)
        return digits?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()?.toString()
            ?: TitleNormalizer.compact(raw)
    }

    private fun compatibleVolumes(a: ComicChapter, b: ComicChapter): Boolean =
        volume(a).isBlank() || volume(b).isBlank() || volume(a) == volume(b)

    private fun number(raw: String): String? {
        val title = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
        if (Regex("(?i)上篇|下篇|前篇|后篇|後篇|上半|下半|part|[（(]\\s*[上下前后後]\\s*[）)]").containsMatchIn(title)) return null
        val withoutVolume = title.replace(Regex("(?i)^(?:第\\s*\\d+\\s*[卷巻]|vol(?:ume)?\\.?\\s*\\d+)\\s+"), "")
        return ComicChapterTitle.numberText(withoutVolume)
    }
}

data class FavoriteMigrationReport(val migrated: Int, val unmatched: Int, val resumeMatched: Boolean)
