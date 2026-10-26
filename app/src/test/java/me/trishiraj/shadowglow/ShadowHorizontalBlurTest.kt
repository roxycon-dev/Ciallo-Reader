package me.trishiraj.shadowglow

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

class ShadowHorizontalBlurTest {
    // Frozen pre-optimization window update, including Float rounding and edge replication.
    private fun reference(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        val last = w - 1
        for (y in 0 until h) {
            val base = y * w
            var sum = 0
            for (i in -r..r) sum += src[base + i.coerceIn(0, last)]
            for (x in 0 until w) {
                dst[base + x] = ((sum * inv) + .5f).toInt()
                sum += src[base + (x + r + 1).coerceAtMost(last)] - src[base + (x - r).coerceAtLeast(0)]
            }
        }
    }

    @Test fun segmentedWindowsRetainEveryPixelAndBufferTail() {
        val random = Random(729191)
        for (w in listOf(1, 2, 3, 4, 5, 7, 31, 32, 33, 64, 97, 257, 1024)) {
            for (h in listOf(1, 2, 7, 32)) for (r in listOf(0, 1, 2, 3, 7, 16, 31, 127, 256)) {
                val src = IntArray(w * h + 17) { random.nextInt(256) }
                val expected = IntArray(src.size) { -149 }
                val actual = expected.copyOf()
                reference(src, expected, w, h, r)
                boxBlurHorizontal(src, actual, w, h, r)
                assertArrayEquals("$w x $h radius $r", expected, actual)
            }
        }
    }
}
