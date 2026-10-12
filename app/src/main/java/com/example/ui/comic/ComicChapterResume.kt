package com.example.ui.comic

import org.json.JSONObject

/** Per-chapter resume survives changed image URLs, chapter visits and process restarts. */
internal data class ComicChapterResume(
    val page: Int = 0,
    val scrollPage: Int = -1,
    val scrollOffset: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("page", page).put("scrollPage", scrollPage).put("scrollOffset", scrollOffset)

    fun startPage(requested: Int, count: Int, vertical: Boolean): Int =
        (if (requested == Int.MAX_VALUE) count - 1
         else if (requested > 0 && requested != page) requested
         else if (vertical && scrollPage >= 0) scrollPage else if (requested > 0) requested else page)
            .coerceIn(0, (count - 1).coerceAtLeast(0))

    companion object {
        fun fromJson(json: JSONObject) = ComicChapterResume(
            json.optInt("page", 0).coerceAtLeast(0),
            json.optInt("scrollPage", -1).coerceAtLeast(-1),
            json.optInt("scrollOffset", 0).coerceAtLeast(0),
        )
    }
}
