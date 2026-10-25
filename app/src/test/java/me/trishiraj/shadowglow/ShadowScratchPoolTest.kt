package me.trishiraj.shadowglow

import org.junit.Assert.*
import org.junit.Test

class ShadowScratchPoolTest {
    @Test fun keepsEveryConcurrentScratchBufferWithoutSharingCheckedOutStorage() {
        val pool = ShadowScratchPool(byteBudget = 4 * 4096 * 4)
        val original = List(4) { pool.acquire(2000) }
        assertEquals(4, original.toSet().size)
        original.forEachIndexed { index, array -> array[0] = index; pool.release(array) }
        val next = List(4) { pool.acquire(2000) }
        assertEquals(original.toSet(), next.toSet())
        assertEquals(0, pool.retainedByteCount)
        assertTrue(pool.acquire(2000) !in next)
    }

    @Test fun evictsLeastRecentlyUsedStorageToHonorGlobalByteBudget() {
        val pool = ShadowScratchPool(byteBudget = 64 * 1024)
        val small = pool.acquire(4096); val medium = pool.acquire(8192); val large = pool.acquire(12288)
        pool.release(small); pool.release(medium); pool.release(large)
        assertEquals(48 * 1024, pool.retainedByteCount)
        assertNotSame(small, pool.acquire(4096))
        assertNotSame(medium, pool.acquire(8192))
        assertSame(large, pool.acquire(12288))
    }

    @Test fun perBucketLimitAndOversizedBuffersCannotGrowThePool() {
        val pool = ShadowScratchPool(byteBudget = 128 * 1024, maxPooledInts = 8192)
        val arrays = List(6) { pool.acquire(4096) }
        arrays.forEach(pool::release)
        assertEquals(64 * 1024, pool.retainedByteCount)
        val oversized = pool.acquire(8193)
        assertEquals(8193, oversized.size)
        pool.release(oversized)
        assertEquals(64 * 1024, pool.retainedByteCount)
        assertNotSame(oversized, pool.acquire(8193))
    }

    @Test fun zeroBudgetRetainsNoArray() {
        val pool = ShadowScratchPool(byteBudget = 0)
        val array = pool.acquire(4096)
        pool.release(array)
        assertEquals(0, pool.retainedByteCount)
        assertNotSame(array, pool.acquire(4096))
    }

    @Test fun aDuplicateReturnCannotCreateTwoOwnersOfTheSameArray() {
        val pool = ShadowScratchPool()
        val array = pool.acquire(4096)
        pool.release(array); pool.release(array)
        assertEquals(4096 * 4, pool.retainedByteCount)
        assertSame(array, pool.acquire(4096))
        assertNotSame(array, pool.acquire(4096))
    }
}
