package com.example.ui.comic

import kotlin.math.roundToLong

/** 列表实测高度对应的滚动范围；尚未进入视口的图片由调用方估算高度。 */
internal data class ComicScrollMetrics(
    val geometry: ComicScrollGeometry,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val canScrollBackward: Boolean,
    val canScrollForward: Boolean,
) {
    constructor(itemSizes: List<Int>, itemSpacing: Int, viewportSize: Int,
        firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int,
        canScrollBackward: Boolean, canScrollForward: Boolean) : this(
        ComicScrollGeometry(itemSizes, itemSpacing, viewportSize), firstVisibleItemIndex,
        firstVisibleItemScrollOffset, canScrollBackward, canScrollForward,
    )

    val itemSizes get() = geometry.itemSizes
    val itemSpacing get() = geometry.itemSpacing
    val viewportSize get() = geometry.viewportSize
    private val starts get() = geometry.starts
    private val scrollRange get() = geometry.scrollRange


    val fraction: Float get() = when {
        itemSizes.isEmpty() || !canScrollBackward -> 0f
        !canScrollForward -> 1f
        scrollRange == 0L -> 0f
        else -> ((starts[firstVisibleItemIndex.coerceIn(itemSizes.indices)] +
            firstVisibleItemScrollOffset.coerceAtLeast(0)).toDouble() / scrollRange).toFloat().coerceIn(0f, 1f)
    }

    /** Custom swipe paging advances one viewport, including within a single very tall image. */
    fun pageFraction(next: Boolean): Float {
        if (scrollRange <= 0L) return fraction
        val step = viewportSize.coerceAtLeast(1) * .90 / scrollRange
        return (fraction + if (next) step else -step).toFloat().coerceIn(0f, 1f)
    }

    /** 滑条落点反算到项内像素偏移，长图和末图都能精确定位。 */
    fun target(fraction: Float): ComicScrollTarget {
        if (itemSizes.isEmpty()) return ComicScrollTarget(0, 0)
        val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        val pixel = (safeFraction * scrollRange.toDouble()).roundToLong()
        var low = 0
        var high = starts.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (starts[middle] <= pixel) low = middle else high = middle - 1
        }
        val index = low
        return ComicScrollTarget(index, (pixel - starts[index]).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}

/** Geometry changes on measurement/viewport updates, not on every scroll pixel. */
internal data class ComicScrollGeometry(val itemSizes: List<Int>, val itemSpacing: Int, val viewportSize: Int) {
    val starts = LongArray(itemSizes.size)
    val scrollRange: Long
    init {
        var height = 0L
        itemSizes.forEachIndexed { index, size ->
            starts[index] = height
            height += size.coerceAtLeast(1)
            if (index < itemSizes.lastIndex) height += itemSpacing.coerceAtLeast(0)
        }
        scrollRange = (height - viewportSize.coerceAtLeast(0)).coerceAtLeast(0)
    }
}

internal class ComicScrollGeometryCache {
    private val heights = HashMap<Int, Int>()
    private var dirty = true
    private var cached: ComicScrollGeometry? = null
    fun record(index: Int, height: Int) {
        val size = height.coerceAtLeast(1)
        if (heights[index] != size) { heights[index] = size; dirty = true }
    }
    fun geometry(count: Int, spacing: Int, viewport: Int): ComicScrollGeometry {
        cached?.let { if (!dirty && it.itemSizes.size == count && it.itemSpacing == spacing && it.viewportSize == viewport) return it }
        val estimated = heights.values.average().toInt().coerceAtLeast(1)
        return ComicScrollGeometry(List(count) { heights[it] ?: estimated }, spacing, viewport).also {
            cached = it; dirty = false
        }
    }
}

internal data class ComicScrollTarget(val index: Int, val offset: Int)
