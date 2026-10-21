package com.example.discover

import com.example.source.SearchBook
import com.example.source.isKnownComicAuthor
import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone
import java.util.Locale

enum class DiscoverDimension(val label: String, val ranges: List<String>) {
    UPDATES("更新", listOf("最新", "今日", "近 3 天", "本周")),
    DISCUSSION("热议", listOf("24h", "7d", "30d")),
    RANKING("榜单", listOf("热门", "飙升", "新作", "完结")),
    CLICKS("点击", listOf("日", "周", "月", "总"))
}
enum class DiscoverLayout(val label: String) { LIST("列表"), GRID("双列网格"), LARGE("大图") }
data class DiscoverQuery(val dimension: DiscoverDimension, val range: String, val page: Int = 1,
    val cursor: String? = null, val offset: Int = 0, val feedId: String? = null, val categoryId: String? = null,
    val includeAdult: Boolean = true, val standardCategories: Set<String> = emptySet(),
    val categoryIds: List<String> = emptyList(), val status: String? = null)
data class DiscoverFeed(val id: String, val label: String, val kind: String? = null)
data class DiscoverNativeCategory(val id: String, val label: String, val group: String = "") {
    val standardIds: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { DiscoverTaxonomy.classifyNative(label, group) }
}
data class DiscoverCatalog(val feeds: List<DiscoverFeed> = emptyList(), val categories: List<DiscoverNativeCategory> = emptyList(),
    val dynamicCategories: Boolean = false, val explicit: Boolean = false,
    val declared: Boolean = false, val capabilities: Map<String, List<String>> = emptyMap(), val dated: Boolean = false,
    val statuses: List<String> = emptyList()) {
    fun ranges(dimension: DiscoverDimension): List<String> {
        if (!declared) return dimension.ranges
        val ranges = capabilities[dimension.name.lowercase()].orEmpty()
        return if (dimension == DiscoverDimension.UPDATES && dated && ranges.isNotEmpty()) dimension.ranges else ranges
    }
    fun supports(dimension: DiscoverDimension, range: String) = !declared || range in ranges(dimension)
    /** Query the most selective mapped facet; other facets are applied to its results. */
    fun routes(selected: Set<String>): List<String> = DiscoverTaxonomy.roots.mapNotNull { root ->
        val picks = selected.intersect(root.leaves)
        categories.filter { c -> picks.any { it in c.standardIds } && c.label !in setOf("全部", "All") }
            .map { it.id }.distinct().takeIf { picks.isNotEmpty() && it.isNotEmpty() }
    }.minByOrNull { it.size }.orEmpty()
}
/** Null means the source did not supply the value, never a fabricated zero. */
data class DiscoverEntry(
    val book: SearchBook,
    val updatedAt: Long? = null,
    val latestChapter: String? = null,
    val discussions: Long? = null,
    val clicks: Long? = null,
    val growth: Double? = null,
    val rank: Int? = null,
    val rankChange: Int? = null,
    val newEntry: Boolean = false,
    val hotComment: String? = null,
    val canonicalId: String? = null,
    val nativeOrder: Int? = null,
    val origin: String? = null,
    val subtitle: String? = null,
    val native: Boolean = false,
    val statistic: String? = null,
)
data class DiscoverPage(val entries: List<DiscoverEntry> = emptyList(), val hasMore: Boolean = false,
    val supported: Boolean = true, val notice: String? = null, val nextCursor: String? = null,
    val catalog: DiscoverCatalog? = null, val native: Boolean = false)
data class DiscoverWork(val key: String, val variants: List<DiscoverEntry>, val score: Double) {
    val book get() = variants.first().book
    val tags get() = variants.flatMap { it.book.discoverTags }.distinct()
    val updatedAt get() = variants.mapNotNull { it.updatedAt }.maxOrNull()
}

object DiscoverEngine {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    fun identity(entry: DiscoverEntry): String = entry.canonicalId?.let { "id:$it" }
        ?: "title:${normalize(entry.book.title)}|author:${normalize(entry.book.author.takeIf { it.isKnownComicAuthor() }.orEmpty())}"

    private fun workGroups(entries: List<DiscoverEntry>): List<List<DiscoverEntry>> {
        val parents = IntArray(entries.size) { it }
        fun root(index: Int): Int { var n = index; while (parents[n] != n) n = parents[n]; return n }
        fun author(index: Int) = entries[index].book.author.takeIf { it.isKnownComicAuthor() }?.let(::normalize).orEmpty()
        val authorSets = Array(entries.size) { i -> setOf(author(i)).filter { it.isNotEmpty() }.toSet() }
        val canonicalSets = Array(entries.size) { i -> setOfNotNull(entries[i].canonicalId) }
        val aliases = entries.indices.flatMap { i -> (listOf(entries[i].book.title) + entries[i].book.comicInfo?.alternateTitles.orEmpty())
            .map(::normalize).filter { it.isNotEmpty() }.distinct().map { it to i } }.groupBy({ it.first }, { it.second })
        fun join(a: Int, b: Int, trusted: Boolean = false) {
            val ra = root(a); val rb = root(b)
            if (ra == rb) return
            val idsA = canonicalSets[ra]
            val idsB = canonicalSets[rb]
            if (!trusted && idsA.isNotEmpty() && idsB.isNotEmpty() && idsA.intersect(idsB).isEmpty()) return
            val authorsA = authorSets[ra]
            val authorsB = authorSets[rb]
            if (!trusted && authorsA.isNotEmpty() && authorsB.isNotEmpty() && authorsA.intersect(authorsB).isEmpty()) return
            parents[rb] = ra
            canonicalSets[ra] = idsA + idsB
            authorSets[ra] = authorsA + authorsB
        }
        entries.indices.filter { entries[it].canonicalId != null }.groupBy { entries[it].canonicalId }.values.forEach { group ->
            group.drop(1).forEach { join(group.first(), it, trusted = true) } }
        aliases.values.forEach { group ->
            val knownAuthors = group.map(::author).filter { it.isNotEmpty() }.distinct()
            val candidates = if (knownAuthors.size > 1) group.filter { author(it).isNotEmpty() } else group
            for (a in candidates.indices) for (b in a + 1 until candidates.size) join(candidates[a], candidates[b])
        }
        return entries.indices.groupBy(::root).values.map { group -> group.map { entries[it] }.sortedBy { it.book.sourceId } }
    }

    /** Equal source budgets: a site's absolute traffic or list length cannot dominate another site. */
    fun merge(entries: List<DiscoverEntry>, dimension: DiscoverDimension, aggregate: Boolean = true): List<DiscoverWork> {
        val distinct = entries.distinctBy { it.book.sourceId to it.book.id }
        val scores = mutableMapOf<Pair<String, String>, Double>()
        val groups = distinct.groupBy { it.book.sourceId }
        groups.values.forEach { rows ->
            fun value(row: DiscoverEntry): Long? = if (row.native) null else when (dimension) {
                DiscoverDimension.UPDATES -> row.updatedAt
                DiscoverDimension.DISCUSSION -> row.discussions
                DiscoverDimension.CLICKS -> row.clicks
                DiscoverDimension.RANKING -> row.rank?.toLong()?.let { -it }
            }
            val ordered = rows.sortedWith(compareBy<DiscoverEntry> { if (value(it) == null) 1 else 0 }
                .thenByDescending { value(it) }.thenBy { it.nativeOrder ?: Int.MAX_VALUE })
            val tieRanks = mutableMapOf<Long, Int>()
            ordered.forEachIndexed { index, row ->
                val position = if (row.native) row.nativeOrder ?: index + 1
                    else if (dimension == DiscoverDimension.RANKING && row.rank != null) row.rank
                    else value(row)?.let { if (aggregate && dimension == DiscoverDimension.UPDATES) index + 1 else tieRanks.getOrPut(it) { index + 1 } }
                        ?: row.nativeOrder ?: index + 1
                // Reciprocal source rank is independent of fetched page length and site traffic.
                scores[row.book.sourceId to row.book.id] = 1.0 / (1.0 + .08 * (position.coerceAtLeast(1) - 1))
            }
        }
        val works = if (aggregate) workGroups(distinct) else distinct.map { listOf(it) }
        return works.map { variants ->
            val rows = variants.groupBy { it.book.sourceId }.values.map { sourceRows ->
                sourceRows.maxBy { scores[it.book.sourceId to it.book.id] ?: 0.0 } }
            val key = if (aggregate) rows.map(::identity).sorted().first() else "${rows.first().book.sourceId}::${rows.first().book.id}"
            DiscoverWork(key, rows,
                rows.sumOf { scores[it.book.sourceId to it.book.id] ?: 0.0 } / groups.size.coerceAtLeast(1)) }
            .sortedWith(compareByDescending<DiscoverWork> { it.score }.thenBy { it.key })
    }
    fun rangeStart(range: String, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        val day = Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        when (range) {
            "今日", "日" -> Unit
            "近 3 天" -> day.add(Calendar.DAY_OF_YEAR, -2)
            "本周", "周" -> day.add(Calendar.DAY_OF_YEAR, -((day.get(Calendar.DAY_OF_WEEK) + 5) % 7))
            else -> return null
        }
        return day.timeInMillis
    }
    /** At most one adjacent move for close scores; native rankings keep their exact positions. */
    fun personalize(works: List<DiscoverWork>, dimension: DiscoverDimension, affinity: (DiscoverWork) -> Int): List<DiscoverWork> {
        if (dimension == DiscoverDimension.RANKING) return works
        val result = works.toMutableList()
        var index = 0
        while (index < result.lastIndex) {
            val first = result[index]; val second = result[index + 1]
            if (second.score >= first.score * .92 && affinity(second) > affinity(first)) {
                result[index] = second; result[index + 1] = first; index += 2
            } else index++
        }
        return result
    }
    fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val text = value.trim().replace(Regex("(\\.\\d{3})\\d+(?=Z$|[+-]\\d{2}:\\d{2}$)"), "$1")
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
            val parsed = runCatching {
                val position = java.text.ParsePosition(0)
                java.text.SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false }.parse(text, position)
                    ?.takeIf { position.index == text.length }?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
    fun compact(value: Long?): String = when {
        value == null -> "未提供"
        value >= 100_000_000 -> String.format(Locale.CHINA, "%.1f亿", value / 100_000_000.0)
        value >= 10_000 -> String.format(Locale.CHINA, "%.1f万", value / 10_000.0)
        else -> value.toString()
    }
}
