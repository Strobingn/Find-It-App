package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The in-heap chunk-index cache is bounded (each entry ~512 KB) and least-recently-used first out. */
class LazChunkIndexMemoryTest {
    private fun index() = LazChunkIndex(
        10L, 5, 0.0, 1.0, 0.0, 1.0,
        DoubleArray(2), DoubleArray(2), DoubleArray(2), DoubleArray(2),
        IntArray(1), IntArray(1),
    )

    @Test
    fun evictsLeastRecentlyUsedBeyondCap() {
        val lru = LazChunkIndex.Companion.LruMemory(3)
        lru["a"] = index(); lru["b"] = index(); lru["c"] = index()
        assertNotNull(lru["a"]) // touch a: b is now eldest
        lru["d"] = index()
        assertEquals(3, lru.size)
        assertNull(lru["b"])
        assertNotNull(lru["a"]); assertNotNull(lru["c"]); assertNotNull(lru["d"])
    }

    @Test
    fun appCacheCapIsEight() = assertEquals(8, LazChunkIndex.MEMORY_ENTRIES)
}
