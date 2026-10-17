package com.example.discover

/** A ranking's meaning takes precedence over a leftover status selection. */
internal data class DiscoverStatusScope(val completedOnly: Boolean = false, val newWorks: Boolean = false) {
    val showStatusChoices get() = !completedOnly && !newWorks
    val hint get() = when {
        completedOnly -> "仅显示已确认完结的作品"
        newWorks -> "按原站新上架排序，作品可能仍在连载"
        else -> null
    }
    fun effectiveStatus(selection: String): String = when {
        completedOnly -> "完结"
        newWorks -> "全部"
        else -> selection
    }
}

internal fun discoverStatusScope(dimension: DiscoverDimension, range: String, feedId: String?,
    categoryId: String?, catalog: DiscoverCatalog? = null): DiscoverStatusScope {
    val label = when {
        categoryId != null -> catalog?.categories?.firstOrNull { it.id == categoryId }?.label
        feedId == "native" -> catalog?.feeds?.firstOrNull()?.label
        feedId != null -> catalog?.feeds?.firstOrNull { it.id == feedId }?.label
        dimension == DiscoverDimension.RANKING -> range
        else -> null
    }.orEmpty()
    // Negative labels such as 未完结 / uncompleted must never become completed feeds.
    val completed = !Regex("未完[结結]|未[结結]束|不完[结結]|未完成|not\\s+(?:yet\\s+)?(?:completed|finished)|uncompleted|incomplete|unfinished|ongoing|连载|連載", RegexOption.IGNORE_CASE).containsMatchIn(label) &&
        Regex("完[结結]|已完成|(?<![a-z])(?:completed|finished|ended)(?![a-z])", RegexOption.IGNORE_CASE).containsMatchIn(label)
    val newWorks = Regex("新作|新[书書]|上架|(?<![a-z])new(?:\\s+(?:releases|works))?(?![a-z])|recently\\s+added", RegexOption.IGNORE_CASE).containsMatchIn(label)
    return DiscoverStatusScope(completedOnly = completed, newWorks = newWorks && !completed)
}
