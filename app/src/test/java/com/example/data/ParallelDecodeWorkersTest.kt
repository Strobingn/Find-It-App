package com.example.data

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class ParallelSegmentCountTest {
    private val mb = 1024L * 1024L

    @Test fun smallUsgsTileUsesEveryConnection() {
        assertEquals(12, LazDownloadManager().parallelSegmentCount(12 * mb, 12))
    }

    @Test fun largeTileSplitsIntoQueueOfFourMbSegments() {
        assertEquals(55, LazDownloadManager().parallelSegmentCount(217 * mb + 1, 12))
    }

    @Test fun layoutIsDeterministicForResume() {
        assertEquals(
            LazDownloadManager().parallelSegmentCount(227_436_117L, 12),
            LazDownloadManager().parallelSegmentCount(227_436_117L, 12),
        )
    }
}

/** The ordered parallel decoder must reproduce the sequential decoder bit for bit. */
class ParallelDecodeMatchesSequentialTest {
    private fun hash(result: DemGenerator.LasLoadResult): String {
        val g = result.grid
        val md = MessageDigest.getInstance("SHA-256")
        g.bareEarth.forEach { v -> val b = java.lang.Float.floatToRawIntBits(v); md.update(byteArrayOf((b shr 24).toByte(), (b shr 16).toByte(), (b shr 8).toByte(), b.toByte())) }
        g.canopySpikes.forEach { v -> val b = java.lang.Float.floatToRawIntBits(v); md.update(byteArrayOf((b shr 24).toByte(), (b shr 16).toByte(), (b shr 8).toByte(), b.toByte())) }
        md.update(ByteArray(g.validData.size) { if (g.validData[it]) 1 else 0 })
        return md.digest().joinToString("") { "%02x".format(it) } + " points=${result.totalPointsRead} ground=${result.groundPointsUsed}"
    }

    @Test fun autzenParallelEqualsSequential() {
        val file = File("/workspace/lazdata/autzen-classified.laz")
        assumeTrue("real LAZ not present", file.isFile)
        val options = LidarImportOptions(rasterResolution = 768).sanitized()
        val parallel = LazTerrainReader.read(file, options)!!
        assertEquals(true, parallel.note.contains("parallel chunk decode"))
        val sequential = try {
            LazTerrainReader.parallelDecodeEnabled = false
            LazTerrainReader.read(file, options)!!
        } finally {
            LazTerrainReader.parallelDecodeEnabled = true
        }
        assertEquals(hash(sequential), hash(parallel))
    }

    @Test fun chunkIndexRefineEqualsSequentialInsideRectangle() {
        val file = File("/workspace/lazdata/autzen-classified.laz")
        assumeTrue("real LAZ not present", file.isFile)
        // Full pass records the chunk index (sidecar + memory) used by every later refine.
        LazTerrainReader.read(file, LidarImportOptions(rasterResolution = 512).sanitized())!!
        val focus = NormalizedRasterBounds(0.31, 0.27, 0.58, 0.61)
        val options = LidarImportOptions(rasterResolution = 1_024, focusBounds = focus).sanitized()
        assertEquals(true, LazTerrainReader.indexedFocusPlan(file, options) != null)
        val indexed = LazTerrainReader.read(file, options)!!
        assertEquals(true, indexed.note.contains("chunk-index refine"))
        val sequential = try {
            LazTerrainReader.parallelDecodeEnabled = false
            LazTerrainReader.read(file, options)!!
        } finally {
            LazTerrainReader.parallelDecodeEnabled = true
        }
        assertEquals(false, sequential.note.contains("chunk-index"))
        assertEquals(hash(sequential), hash(indexed))
    }
}
