package com.example.discover

import com.example.source.*

/** Storage and source boundaries, independent of the currently visible tab. */
enum class DiscoverPartition(val label: String, val preferenceSuffix: String) {
    COMIC("漫画", ""), NOVEL("小说", "_novel");
    fun accepts(source: BookSource) = when (this) {
        COMIC -> source.isComicSource
        NOVEL -> source.isNovelSource
    }
}
internal val SearchBook.discoverStatus get() = novelInfo?.status ?: comicInfo?.status
internal val SearchBook.discoverTags get() =
    (novelInfo?.tags.orEmpty() + listOfNotNull(novelInfo?.category) + comicInfo?.tags.orEmpty()).distinct()

/** Imported chapter novels keep their existing catalogue/online-reading route. */
internal val BookSource.discoverUsesNovelDetails get() = isNovelSource &&
    (WholeBookNovelSources.contains(id) || this !is ComicSource)
