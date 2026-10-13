package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ParallelDecodeWorkersTest {
    private val mb = 1024L * 1024L

    @Test fun largeHeapUsesAllCoresUpToEight() {
        assertEquals(8, parallelDecodeWorkers(cores = 16, maxHeapBytes = 4096 * mb, rasterCells = 1024L * 1024L))
    }

    @Test fun phoneHeapCapsWorkersForBigRasters() {
        // 1536² raster ≈ 72 MB per worker; 256 MB heap * 0.4 ≈ 102 MB → one worker (sequential path).
        assertEquals(1, parallelDecodeWorkers(cores = 8, maxHeapBytes = 256 * mb, rasterCells = 1536L * 1536L))
        // 512 MB heap → two workers.
        assertEquals(2, parallelDecodeWorkers(cores = 8, maxHeapBytes = 512 * mb, rasterCells = 1536L * 1536L))
    }

    @Test fun neverBelowOne() {
        assertEquals(1, parallelDecodeWorkers(cores = 0, maxHeapBytes = 1, rasterCells = 10_000_000L))
    }
}
