package com.example.ui.reader

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 展示文案在读取时翻译（枚举在类加载期初始化，构造期翻译会把语言冻住）。 */
enum class TouchAction(private val raw: String) {
    NONE("不执行操作"), PREVIOUS("上一页"), NEXT("下一页"), CONTROLS("呼出 / 收起状态栏"),
    SETTINGS("打开设置"), CONTENTS("打开目录"), LISTEN("听书"), EXIT("退出阅读");

    val label: String get() = com.example.i18n.tr(raw)
}

enum class SwipeAction(private val raw: String) {
    NONE("禁用此区滑动"), PAGE("跟随阅读模式"), CONTROLS("呼出状态栏"), SETTINGS("打开设置");

    val label: String get() = com.example.i18n.tr(raw)
}

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
                    swipe = SwipeAction.entries.firstOrNull { it.name == o.optString("swipe") } ?: SwipeAction.PAGE,
                    distanceDp = if (o.has("distance")) o.getDouble("distance").toFloat() else null).normalized()
            }.distinctBy { it.id }
        }.getOrNull() }
        fun encode(zones: List<TouchZone>) = JSONArray().apply { zones.take(64).forEach { put(it.normalized().json()) } }.toString()
        /** Last rectangle wins, for BOTH behaviors. NONE blocks lower rectangles; no fall-through. */
        fun hit(zones: List<TouchZone>, x: Float, y: Float) = zones.lastOrNull { it.contains(x, y) }
    }
}

internal fun List<TouchZone>?.zoneAt(point: Offset, size: IntSize): TouchZone? =
    this?.let { TouchZone.hit(it, point.x / size.width.coerceAtLeast(1), point.y / size.height.coerceAtLeast(1)) }

internal fun TouchZone?.allowsNativeSwipe() = this == null || swipe == SwipeAction.PAGE

internal enum class ZoneHandle(val x: Int, val y: Int) {
    TOP_LEFT(-1, -1), TOP(0, -1), TOP_RIGHT(1, -1), RIGHT(1, 0),
    BOTTOM_RIGHT(1, 1), BOTTOM(0, 1), BOTTOM_LEFT(-1, 1), LEFT(-1, 0);

    fun point(zone: TouchZone, size: Size, inset: Float): Offset {
        fun position(direction: Int, low: Float, high: Float, span: Float): Float {
            val edge = inset.coerceAtMost(span / 2f)
            val value = when (direction) { -1 -> low; 1 -> high; else -> (low + high) / 2f }
            return (value * span).coerceIn(edge, span - edge)
        }
        return Offset(position(x, zone.left, zone.right, size.width), position(y, zone.top, zone.bottom, size.height))
    }
}

internal fun dragTouchZone(zone: TouchZone, handle: ZoneHandle?, delta: Offset, snap: Offset): TouchZone {
    val minimum = .08f
    fun nearStart(value: Float, threshold: Float) = if (value <= threshold) 0f else value
    fun nearEnd(value: Float, threshold: Float) = if (value >= 1f - threshold) 1f else value
    if (handle == null) {
        val dx = delta.x.coerceIn(-zone.left, 1f - zone.right)
        val dy = delta.y.coerceIn(-zone.top, 1f - zone.bottom)
        return zone.copy(left = zone.left + dx, right = zone.right + dx, top = zone.top + dy, bottom = zone.bottom + dy)
    }
    return zone.copy(
        left = if (handle.x < 0) nearStart((zone.left + delta.x).coerceIn(0f, (zone.right - minimum).coerceAtLeast(0f)), snap.x) else zone.left,
        top = if (handle.y < 0) nearStart((zone.top + delta.y).coerceIn(0f, (zone.bottom - minimum).coerceAtLeast(0f)), snap.y) else zone.top,
        right = if (handle.x > 0) nearEnd((zone.right + delta.x).coerceIn((zone.left + minimum).coerceAtMost(1f), 1f), snap.x) else zone.right,
        bottom = if (handle.y > 0) nearEnd((zone.bottom + delta.y).coerceIn((zone.top + minimum).coerceAtMost(1f), 1f), snap.y) else zone.bottom,
    )
}

internal fun novelPageTap(x: Float, width: Int, edge: Float): TouchAction = when {
    x < width * edge -> TouchAction.PREVIOUS
    x > width * (1f - edge) -> TouchAction.NEXT
    else -> TouchAction.CONTROLS
}

internal fun List<TouchZone>.migrateLegacyTouchZones(): List<TouchZone> =
    map { if (it.id == "scroll" && it.swipe == SwipeAction.NONE) it.copy(swipe = SwipeAction.PAGE) else it }

class NovelTouchZoneStore(context: Context) {
    private val prefs = context.getSharedPreferences("reader_touch_zones_v1", Context.MODE_PRIVATE)
    fun load(profile: String): List<TouchZone>? {
        val legacy = prefs.getString("novel", null)
        if (legacy != null) {
            val editor = prefs.edit().remove("novel")
            if (!prefs.contains(profile)) TouchZone.decode(legacy)?.migrateLegacyTouchZones()?.let {
                editor.putString(profile, TouchZone.encode(it))
            }
            editor.apply()
        }
        return TouchZone.decode(prefs.getString(profile, null))
    }

    fun save(profile: String, zones: List<TouchZone>?) {
        prefs.edit().apply { if (zones.isNullOrEmpty()) remove(profile) else putString(profile, TouchZone.encode(zones)) }.apply()
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
        if (kotlin.math.hypot(x, y) > slop) moved = true
        if (maxPointers > 1 || consumed) cancelled = true
    }
    fun isTap() = !moved && !cancelled
    fun isSwipe(@Suppress("UNUSED_PARAMETER") vertical: Boolean) = moved && !cancelled &&
        kotlin.math.hypot(dx, dy) >= threshold
}

/** Only a fast release overrides the native completion decision. No axis lock or path rewriting. */
internal fun fastPageCommit(distanceDp: Float?, delta: androidx.compose.ui.geometry.Offset,
                            velocity: androidx.compose.ui.geometry.Offset, density: Float,
                            nativeCommit: Boolean): Boolean {
    if (distanceDp == null || velocity.getDistance() < 850f * density) return nativeCommit
    // A fast flick back toward the grab point means return, even after a long outward curl.
    if (delta.x * velocity.x + delta.y * velocity.y < 0f) return false
    return delta.getDistance() >= distanceDp * density
}

/** Positive means physical right/down. PAGE remains bidirectional, independent of tap action. */
internal fun swipeGuideSign(action: TouchAction, reverse: Boolean): Float =
    (if (action == TouchAction.PREVIOUS) 1f else -1f) * if (reverse) -1f else 1f
