package com.example.source.keyword

import java.io.Reader

/** Small curated seed, not a promise that every word or proper name exists locally. */
internal class LocalKeywordIndex(reader: Reader) {
    private val index = mutableMapOf<String, MutableList<KeywordConcept>>()

    init {
        reader.buffered().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                val fields = line.split('\t')
                require(fields.size == 5) { "Invalid keyword seed row" }
                val names = buildList {
                    fields[1].split('|').filter { it.isNotBlank() }.forEach { add(KeywordName(it, "zh")) }
                    fields[2].split('|').filter { it.isNotBlank() }.forEach { add(KeywordName(it, "en")) }
                    fields[3].split('|').filter { it.isNotBlank() }.forEach { add(KeywordName(it, "ja")) }
                    fields[4].split('|').filter { it.isNotBlank() }.forEach { add(KeywordName(it, "romaji")) }
                }
                val concept = KeywordConcept("seed:${fields[0]}:${KeywordKeys.match(fields[1].substringBefore('|'))}", names, "curated-v1")
                names.map { KeywordKeys.match(it.text) }.filter { it.isNotBlank() }.distinct().forEach { key ->
                    index.getOrPut(key) { mutableListOf() }.add(concept)
                }
            }
        }
    }

    fun find(input: String): List<KeywordConcept> = index[KeywordKeys.match(input)].orEmpty()
}
