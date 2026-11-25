package com.example.source

enum class SourceSortMode { DEFAULT, CUSTOM, SPEED }

data class SourceOrdering(
    val mode: SourceSortMode = SourceSortMode.DEFAULT,
    val customIds: List<String> = emptyList(),
    val speedMillis: Map<String, Long> = emptyMap(),
) {
    fun applyTo(sources: List<BookSource>, mode: SourceSortMode = this.mode): List<BookSource> {
        return when (mode) {
            SourceSortMode.DEFAULT -> sources
            SourceSortMode.SPEED -> sources.sortedBy { speedMillis[it.id] ?: Long.MAX_VALUE }
            SourceSortMode.CUSTOM -> {
                val positions = customIds.withIndex().associate { (index, id) -> id to index }
                sources.sortedBy { positions[it.id] ?: Int.MAX_VALUE }
            }
        }
    }
}
