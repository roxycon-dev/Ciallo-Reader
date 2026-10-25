package me.trishiraj.shadowglow

import java.util.ArrayDeque
import java.util.LinkedHashMap

/** Bounded scratch ownership; checked-out arrays never remain in the pool. */
internal class ShadowScratchPool(
    private val byteBudget: Int = 4 * 1024 * 1024,
    private val maxPooledInts: Int = 262144,
    private val buffersPerBucket: Int = 4,
) {
    private val buckets = LinkedHashMap<Int, ArrayDeque<IntArray>>(16, .75f, true)
    private var cachedBytes = 0

    init {
        require(byteBudget >= 0 && maxPooledInts >= 4096 && buffersPerBucket > 0)
    }

    fun acquire(length: Int): IntArray {
        require(length >= 0)
        if (length > maxPooledInts) return IntArray(length)
        val size = ((length + 4095) / 4096) * 4096
        val reused = synchronized(this) {
            buckets[size]?.pollLast()?.also { cachedBytes -= it.size * 4 }
        }
        return reused ?: IntArray(size)
    }

    @Synchronized
    fun release(buffer: IntArray) {
        val bytes = buffer.size * 4L
        if (buffer.isEmpty() || buffer.size > maxPooledInts || buffer.size % 4096 != 0 || bytes > byteBudget) return
        val queue = buckets.getOrPut(buffer.size) { ArrayDeque(buffersPerBucket) }
        if (queue.size >= buffersPerBucket || queue.contains(buffer)) return
        while (cachedBytes + bytes > byteBudget) {
            // Empty buckets are retained (at most 64 by default), avoiding metadata
            // churn when a frame checks out every array from the current bucket.
            for (old in buckets.values) {
                val evicted = old.pollFirst() ?: continue
                cachedBytes -= evicted.size * 4
                break
            }
        }
        queue.addLast(buffer)
        cachedBytes += bytes.toInt()
    }

    @get:Synchronized
    internal val retainedByteCount: Int get() = cachedBytes
}
