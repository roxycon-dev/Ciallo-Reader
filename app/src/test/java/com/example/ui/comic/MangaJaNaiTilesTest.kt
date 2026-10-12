package com.example.ui.comic

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MangaJaNaiTilesTest {
    @Test fun bulkPixelReadMatchesOriginalClampedSamplingForEveryEdgeAndPartialTile() {
        for ((width, height) in listOf(1 to 1, 13 to 29, 48 to 48, 97 to 113, 170 to 185)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height) { i ->
                -0x1000000 or ((i * 13 and 255) shl 16) or ((i * 37 and 255) shl 8) or (i * 71 and 255)
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            try {
                val side = MangaJaNai.TILE + MangaJaNai.HALO * 2
                val tile = IntArray(side * side)
                for (y in 0 until height step MangaJaNai.TILE) for (x in 0 until width step MangaJaNai.TILE) {
                    readMangaJaNaiTile(bitmap, x, y, tile)
                    for (ty in 0 until side) for (tx in 0 until side) {
                        val sx = (x + tx - MangaJaNai.HALO).coerceIn(0, width - 1)
                        val sy = (y + ty - MangaJaNai.HALO).coerceIn(0, height - 1)
                        assertEquals("$width/$height tile $x/$y pixel $tx/$ty", pixels[sy * width + sx], tile[ty * side + tx])
                    }
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun hashCollisionAndSinglePixelChangeNeverReuseDifferentInput() {
        val cache = MangaJaNaiTileCache()
        val first = intArrayOf(0, 31)
        val collision = intArrayOf(1, 0)
        assertEquals(first.contentHashCode(), collision.contentHashCode())
        cache.put(first, floatArrayOf(0.12345678f))
        assertNull(cache.find(collision))
        assertNull(cache.find(intArrayOf(0, 30)))
        assertArrayEquals(floatArrayOf(0.12345678f), cache.find(first.copyOf()), 0f)
    }

    @Test fun cacheOwnsCopiesAndEvictsLeastRecentlyUsedWithinFixedCapacity() {
        val cache = MangaJaNaiTileCache(2)
        val input = intArrayOf(1)
        val output = floatArrayOf(0.31234568f)
        cache.put(input, output)
        input[0] = 99
        output[0] = 0f
        cache.put(intArrayOf(2), floatArrayOf(2f))
        assertEquals(0.31234568f, cache.find(intArrayOf(1))!![0], 0f)
        cache.put(intArrayOf(3), floatArrayOf(3f))
        assertNull(cache.find(intArrayOf(2)))
        assertNotNull(cache.find(intArrayOf(1)))
        assertEquals(2, cache.size)
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.find(intArrayOf(1)))
    }

    @Test fun zeroCapacityDisablesReuse() {
        val cache = MangaJaNaiTileCache(0)
        cache.put(intArrayOf(1), floatArrayOf(0.5f))
        assertNull(cache.find(intArrayOf(1)))
        assertEquals(0, cache.size)
    }
}
