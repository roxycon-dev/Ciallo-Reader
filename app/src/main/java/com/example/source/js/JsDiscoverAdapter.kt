package com.example.source.js

import com.example.discover.*
import com.example.source.SearchBook
import org.json.JSONArray
import org.json.JSONObject

/** Parse native Venera metadata once, retaining provenance and absence rather than inferred statistics. */
internal object JsDiscoverAdapter {
    fun catalog(data: JSONObject): DiscoverCatalog {
        val feeds = data.optJSONArray("feeds") ?: JSONArray()
        val categories = data.optJSONArray("categories") ?: JSONArray()
        return DiscoverCatalog(
            (0 until feeds.length()).mapNotNull { i -> feeds.optJSONObject(i)?.let {
                DiscoverFeed(it.optString("id"), it.optString("label"), it.optString("kind").takeUnless { v -> v.isBlank() || v == "null" }) } },
            (0 until categories.length()).mapNotNull { i -> categories.optJSONObject(i)?.let {
                DiscoverNativeCategory(it.optString("id"), it.optString("label"), it.optString("group")) } },
            data.optBoolean("dynamicCategories"), data.optBoolean("explicit"), declared = true,
            statuses = data.optJSONArray("statuses")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            capabilities = data.optJSONObject("capabilities")?.let { obj -> obj.keys().asSequence().associateWith { key ->
                val values = obj.optJSONArray(key) ?: JSONArray()
                (0 until values.length()).map { values.getString(it) }
            } }.orEmpty())
    }
    fun page(data: JSONObject, sourceId: String, query: DiscoverQuery, now: Long = System.currentTimeMillis()): DiscoverPage {
        val rows = data.optJSONArray("entries") ?: data.optJSONArray("comics") ?: JSONArray()
        val native = query.feedId != null || query.categoryId != null ||
            (data.optBoolean("native") && query.categoryIds.isEmpty())
        val start = if (!native && query.dimension == DiscoverDimension.UPDATES) DiscoverEngine.rangeStart(query.range, now) else null
        var dated = false
        val entries = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val raw = row.optJSONObject("book") ?: row
            val id = raw.optString("id").trim()
            val title = raw.optString("title").trim()
            if (id.isBlank() || id.startsWith("__") || title.isBlank() || id == "null") return@mapNotNull null
            val metadata = JsComicMetadata.book(raw, sourceId, id)
            fun text(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
                row.opt(k)?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() && it != "null" } }
            fun number(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
                when (val value = row.opt(key)) {
                    is Number -> runCatching {
                        val decimal = value.toString().toBigDecimal().stripTrailingZeros()
                        decimal.takeIf { it.scale() <= 0 && it >= Long.MIN_VALUE.toBigDecimal() && it <= Long.MAX_VALUE.toBigDecimal() }?.toLong()
                    }.getOrNull()
                    is String -> value.toLongOrNull()
                    else -> null
                } }
            val updated = number("updatedAt", "updateTime")?.takeIf { it > 0 }?.let { if (it < 10_000_000_000L) it * 1000 else it }
                ?: DiscoverEngine.parseDate(text("updatedAt", "updateTime") ?: metadata.comicInfo?.updatedAt)
            if (updated != null) dated = true
            if (!native && query.dimension == DiscoverDimension.UPDATES && start != null &&
                (updated == null || updated < start || updated > now)) return@mapNotNull null
            val tags = metadata.comicInfo?.tags.orEmpty() + listOfNotNull(text("contentRating").takeIf { it in setOf("erotica", "pornographic") })
            DiscoverEntry(
                book = metadata.copy(title = title, comicId = id, comicInfo = metadata.comicInfo?.copy(tags = tags.distinct())),
                updatedAt = updated, latestChapter = text("latestChapter", "lastChapter"),
                discussions = number("discussions", "commentCount")?.takeIf { it >= 0 },
                clicks = number("clicks", "viewCount", "readCount")?.takeIf { it >= 0 },
                growth = text("growth")?.toDoubleOrNull()?.takeIf { it.isFinite() },
                rank = number("rank")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt(),
                rankChange = number("rankChange")?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt(),
                newEntry = row.optBoolean("newEntry"), hotComment = text("hotComment"), canonicalId = text("canonicalId"),
                nativeOrder = number("nativeOrder")?.toInt() ?: query.offset + index + 1,
                origin = text("origin") ?: data.optString("origin").takeIf { it.isNotBlank() }, subtitle = text("subtitle", "subTitle"), native = native,
                statistic = text("statistic")?.let { label -> Regex("^(关注|收藏)\\s+(\\d+)$").matchEntire(label)?.let { match ->
                    match.groupValues[2].toLongOrNull()?.let { "${match.groupValues[1]} ${DiscoverEngine.compact(it)}" }
                } ?: label })
        }
        val notice = data.optString("notice").takeUnless { it.isBlank() || it == "null" }
        return DiscoverPage(entries.distinctBy { it.book.id }, data.optBoolean("hasMore"), data.optBoolean("supported", true), notice,
            data.optString("nextCursor").takeUnless { it.isBlank() || it == "null" },
            data.optJSONObject("catalog")?.let { catalog(it).copy(dated = dated) }, native)
    }
}
