package com.example.ui.comic

import android.graphics.Bitmap

/** Exact input matches only. Stores FP32 network cores before strength/detail composition.
 * Mobile caller keeps one entry (910,336 B); no page-sized cache or second session.
 * Access is serialized by MangaJaNai's page lock.
 */
internal class MangaJaNaiTileCache(private val capacity: Int = 8) {
    private data class Entry(var hash: Int, val input: IntArray, val output: FloatArray)
    private val entries = ArrayList<Entry>(capacity)
    internal val size: Int get() = entries.size

    fun find(input: IntArray): FloatArray? {
        val hash = input.contentHashCode()
        val index = entries.indexOfFirst { it.hash == hash && it.input.contentEquals(input) }
        if (index < 0) return null
        val entry = entries.removeAt(index)
        entries.add(entry)
        return entry.output
    }

    fun put(input: IntArray, output: FloatArray) {
        if (capacity <= 0) return
        val entry = if (entries.size < capacity) {
            Entry(input.contentHashCode(), input.copyOf(), output.copyOf())
        } else {
            entries.removeAt(0).also {
                require(it.input.size == input.size && it.output.size == output.size)
                it.hash = input.contentHashCode()
                input.copyInto(it.input)
                output.copyInto(it.output)
            }
        }
        entries.add(entry)
    }

    fun clear() = entries.clear()
}

/** One native pixel read per tile, followed by the same clamped edge replication as before. */
internal fun readMangaJaNaiTile(source: Bitmap, x: Int, y: Int, target: IntArray,
                              tileSize: Int = MangaJaNai.TILE, halo: Int = MangaJaNai.HALO) {
    val side = tileSize + halo * 2
    val left = maxOf(0, x - halo)
    val top = maxOf(0, y - halo)
    val right = minOf(source.width, x + tileSize + halo)
    val bottom = minOf(source.height, y + tileSize + halo)
    val dx = left - x + halo
    val dy = top - y + halo
    val width = right - left
    val height = bottom - top
    source.getPixels(target, dy * side + dx, side, left, top, width, height)
    for (row in dy until dy + height) {
        val offset = row * side
        java.util.Arrays.fill(target, offset, offset + dx, target[offset + dx])
        java.util.Arrays.fill(target, offset + dx + width, offset + side, target[offset + dx + width - 1])
    }
    for (row in 0 until dy) target.copyInto(target, row * side, dy * side, (dy + 1) * side)
    for (row in dy + height until side) {
        target.copyInto(target, row * side, (dy + height - 1) * side, (dy + height) * side)
    }
}
