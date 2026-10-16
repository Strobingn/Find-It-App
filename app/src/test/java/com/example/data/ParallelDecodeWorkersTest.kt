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

    /** Refines decode only index-selected chunks in parallel; the grid must equal the sequential reader. */
    @Test fun focusedRefineParallelEqualsSequential() {
        val files = listOf(
            File("/workspace/lazdata/autzen-classified.laz"),
            File("/workspace/lazdata/USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz"),
        ).filter { it.isFile }
        assumeTrue("real LAZ not present", files.isNotEmpty())
        for (file in files) {
            // Full pass first so the chunk index exists, exactly like the app (overview, then Refine).
            LazTerrainReader.read(file, LidarImportOptions(rasterResolution = 512).sanitized())
            for (focus in listOf(NormalizedRasterBounds(0.45, 0.45, 0.55, 0.55), NormalizedRasterBounds(0.0, 0.7, 0.3, 1.0))) {
                val options = LidarImportOptions(rasterResolution = 2048, focusBounds = focus).sanitized()
                val parallel = LazTerrainReader.read(file, options)!!
                assertEquals(parallel.note, true, parallel.note.contains("chunk-index refine"))
                val sequential = try {
                    LazTerrainReader.parallelDecodeEnabled = false
                    LazTerrainReader.read(file, options)!!
                } finally {
                    LazTerrainReader.parallelDecodeEnabled = true
                }
                assertEquals("${file.name} $focus", hash(sequential), hash(parallel))
            }
        }
    }
}
