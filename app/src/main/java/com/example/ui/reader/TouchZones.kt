package com.example.ui.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

enum class TouchAction(val label: String) {
    NONE("不执行操作"), PREVIOUS("上一页"), NEXT("下一页"), CONTROLS("呼出 / 收起状态栏"),
    SETTINGS("打开设置"), CONTENTS("打开目录"), LISTEN("听书"), EXIT("退出阅读"),
}
enum class SwipeAction(val label: String) { NONE("不执行操作"), PAGE("滑动翻页"), CONTROLS("呼出状态栏"), SETTINGS("打开设置") }

/** Rectangles use viewport fractions; thresholds use dp, independent of editor scale or display density. */
data class TouchZone(
    val id: String = UUID.randomUUID().toString(),
    val left: Float = .15f, val top: Float = .2f, val right: Float = .85f, val bottom: Float = .65f,
    val tap: TouchAction = TouchAction.CONTROLS,
    val swipe: SwipeAction = SwipeAction.PAGE,
    /** null delegates to the existing reader's threshold, never an invented replacement default. */
    val distanceDp: Float? = null,
) {
    fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
    fun normalized(): TouchZone {
        fun Float.safe(default: Float) = if (isFinite()) this else default
        val l = left.safe(.15f).coerceIn(0f, .92f); val t = top.safe(.2f).coerceIn(0f, .92f)
        return copy(left = l, top = t, right = right.safe(.85f).coerceIn(l + .08f, 1f),
            bottom = bottom.safe(.65f).coerceIn(t + .08f, 1f),
            distanceDp = distanceDp?.takeIf { it.isFinite() }?.coerceIn(12f, 360f))
    }
    fun json() = JSONObject().apply {
        put("id", id); put("l", left); put("t", top); put("r", right); put("b", bottom)
        put("tap", tap.name); put("swipe", swipe.name); distanceDp?.let { put("distance", it) }
    }
    companion object {
        fun decode(raw: String?): List<TouchZone>? = raw?.let { text -> runCatching {
            val array = JSONArray(text)
            require(array.length() <= 64)
            List(array.length()) { index ->
                val o = array.getJSONObject(index)
                TouchZone(id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    left = o.getDouble("l").toFloat(), top = o.getDouble("t").toFloat(),
                    right = o.getDouble("r").toFloat(), bottom = o.getDouble("b").toFloat(),
                    tap = TouchAction.entries.firstOrNull { it.name == o.optString("tap") } ?: TouchAction.NONE,
                    swipe = SwipeAction.entries.firstOrNull { it.name == o.optString("swipe") } ?: SwipeAction.NONE,
                    distanceDp = if (o.has("distance")) o.getDouble("distance").toFloat() else null).normalized()
            }.distinctBy { it.id }
        }.getOrNull() }
        fun encode(zones: List<TouchZone>) = JSONArray().apply { zones.take(64).forEach { put(it.normalized().json()) } }.toString()
        /** Last rectangle wins, for BOTH behaviors. NONE blocks lower rectangles; no fall-through. */
        fun hit(zones: List<TouchZone>, x: Float, y: Float) = zones.lastOrNull { it.contains(x, y) }
    }
}

class NovelTouchZoneStore(context: Context) {
    private val prefs = context.getSharedPreferences("reader_touch_zones_v1", Context.MODE_PRIVATE)
    fun load() = TouchZone.decode(prefs.getString("novel", null))
    fun save(zones: List<TouchZone>?) {
        prefs.edit().apply { if (zones == null) remove("novel") else putString("novel", TouchZone.encode(zones)) }.apply()
    }
}

/** One-way classification: once a gesture moves, releasing can NEVER produce a click. */
class TouchGestureDecision(private val slop: Float, private val threshold: Float) {
    var moved = false; private set
    var cancelled = false; private set
    private var maxPointers = 1
    var dx = 0f; private set
    var dy = 0f; private set
    fun update(x: Float, y: Float, pointers: Int = 1, consumed: Boolean = false) {
        dx = x; dy = y; maxPointers = maxOf(maxPointers, pointers)
        if (abs(x) > slop || abs(y) > slop) moved = true
        if (maxPointers > 1 || consumed) cancelled = true
    }
    fun isTap() = !moved && !cancelled
    fun isSwipe(vertical: Boolean) = moved && !cancelled &&
        (if (vertical) abs(dy) >= threshold && abs(dy) > abs(dx) * 1.1f else abs(dx) >= threshold && abs(dx) > abs(dy) * 1.1f)
}
