package com.example.ui.comic

import kotlin.math.roundToLong

/** 列表实测高度对应的滚动范围；尚未进入视口的图片由调用方估算高度。 */
internal data class ComicScrollMetrics(
    val itemSizes: List<Int>,
    val itemSpacing: Int,
    val viewportSize: Int,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val canScrollBackward: Boolean,
    val canScrollForward: Boolean,
) {
    private val starts = LongArray(itemSizes.size)
    private val scrollRange: Long

    init {
        var height = 0L
        itemSizes.forEachIndexed { index, size ->
            starts[index] = height
            height += size.coerceAtLeast(1)
            if (index < itemSizes.lastIndex) height += itemSpacing.coerceAtLeast(0)
        }
        scrollRange = (height - viewportSize.coerceAtLeast(0)).coerceAtLeast(0)
    }

    val fraction: Float get() = when {
        itemSizes.isEmpty() || !canScrollBackward -> 0f
        !canScrollForward -> 1f
        scrollRange == 0L -> 0f
        else -> ((starts[firstVisibleItemIndex.coerceIn(itemSizes.indices)] +
            firstVisibleItemScrollOffset.coerceAtLeast(0)).toDouble() / scrollRange).toFloat().coerceIn(0f, 1f)
    }

    /** 滑条落点反算到项内像素偏移，长图和末图都能精确定位。 */
    fun target(fraction: Float): ComicScrollTarget {
        if (itemSizes.isEmpty()) return ComicScrollTarget(0, 0)
        val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        val pixel = (safeFraction * scrollRange.toDouble()).roundToLong()
        val index = starts.indexOfLast { it <= pixel }.coerceAtLeast(0)
        return ComicScrollTarget(index, (pixel - starts[index]).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}

internal data class ComicScrollTarget(val index: Int, val offset: Int)
