package com.example.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LazViewportRefinementTest {
    private fun fixture(root: File): File = File(root, "strips.laz").also { file ->
        requireNotNull(javaClass.getResourceAsStream("/laz/viewport-chunks.laz")).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }
    private fun crop() = NormalizedRasterBounds(50.0 / 249, 1.0 - 499.0 / 1500, 100.0 / 249, 1.0 - 300.0 / 1500)

    @Test fun cropSeeksOneChunkAndMatchesFullScanWithoutSampling() {
        val root = Files.createTempDirectory("viewport-pruning").toFile()
        val parallel = LazTerrainReader.parallelDecodeEnabled
        val indexed = LazTerrainReader.chunkIndexEnabled
        try {
            val file = fixture(root)
            val full = LazTerrainReader.read(file, LidarImportOptions(rasterResolution = 128))!!
            assertEquals(250_123, full.totalPointsRead)
            assertTrue(File(file.path + ".fichunks").isFile) // recorded in the import, no second pass
            LazChunkIndex.clearMemory() // cold reopen must use the persisted bounds
            val index = LazChunkIndex.load(file, 250_123, 50_000)!!
            assertArrayEquals(intArrayOf(1), index.chunksOverlapping(500_050.0, 4_500_300.0, 500_100.0, 4_500_499.0))
            for (mode in GroundSurfaceMode.entries) for (filter in listOf(null, setOf(2))) {
                val options = LidarImportOptions(groundMode = mode, rasterResolution = 128, focusBounds = crop(), allowedClasses = filter)
                var decoded = 0L
                LazTerrainReader.parallelDecodeEnabled = true
                LazTerrainReader.chunkIndexEnabled = true
                val selected = LazTerrainReader.read(file, options, onProgress = { n, total ->
                    assertEquals(50_000L, total)
                    assertTrue(n >= decoded)
                    decoded = n
                })!!
                assertEquals(50_000L, decoded) // exactly one pass, not two corrective decodes
                assertEquals(LazTerrainReader.FocusWork(1, 6, 50_000), LazTerrainReader.lastFocusWork)
                LazTerrainReader.parallelDecodeEnabled = false
                LazTerrainReader.chunkIndexEnabled = false
                val scan = LazTerrainReader.read(file, options)!!
                assertEquals(10_200, selected.sampledPoints) // inclusive 51 x 200 nodes
                assertEquals(scan.sampledPoints, selected.sampledPoints)
                assertEquals(scan.groundPointsUsed, selected.groundPointsUsed)
                assertArrayEquals(scan.grid.bareEarth, selected.grid.bareEarth, 0f)
                assertArrayEquals(scan.grid.canopySpikes, selected.grid.canopySpikes, 0f)
                assertArrayEquals(scan.grid.validData, selected.grid.validData)
                assertArrayEquals(scan.grid.interpolatedData, selected.grid.interpolatedData)
            }
        } finally {
            LazTerrainReader.parallelDecodeEnabled = parallel
            LazTerrainReader.chunkIndexEnabled = indexed
            LazChunkIndex.clearMemory()
            root.deleteRecursively()
        }
    }

    @Test fun oldImportIndexesOnceAndRejectsStaleCorruptAndCancelledSidecars() {
        val root = Files.createTempDirectory("viewport-index").toFile()
        try {
            val file = fixture(root)
            val side = File(file.path + ".fichunks")
            assertNull(LazChunkIndex.ensure(file, shouldContinue = { false }))
            assertFalse(side.exists())
            var callbacks = 0
            val built = LazChunkIndex.ensure(file, onProgress = { _, _ -> callbacks++ })!!
            assertTrue(callbacks > 0)
            LazChunkIndex.clearMemory()
            callbacks = 0
            assertNotNull(LazChunkIndex.ensure(file, onProgress = { _, _ -> callbacks++ }))
            assertEquals(0, callbacks) // subsequent refinement does not index again
            val bytes = side.readBytes()
            bytes[80] = (bytes[80].toInt() xor 1).toByte()
            side.writeBytes(bytes)
            LazChunkIndex.clearMemory()
            assertNull(LazChunkIndex.load(file, 250_123, 50_000))
            LazChunkIndex.store(file, built)
            LazChunkIndex.clearMemory()
            assertTrue(file.setLastModified(file.lastModified() + 2_000))
            assertNull(LazChunkIndex.load(file, 250_123, 50_000))
            side.delete()
            var running = true
            assertNull(LazChunkIndex.ensure(file, shouldContinue = { running }, onProgress = { n, _ -> if (n >= 50_000) running = false }))
            assertFalse(side.exists())
        } finally { LazChunkIndex.clearMemory(); root.deleteRecursively() }
    }

    @Test fun boundaryTouchAndShortLastChunkCannotBePruned() {
        val root = Files.createTempDirectory("viewport-edges").toFile()
        try {
            val file = fixture(root)
            val index = LazChunkIndex.ensure(file)!!
            assertArrayEquals(intArrayOf(0), index.chunksOverlapping(500_249.0, 4_500_199.0, 500_249.0, 4_500_199.0))
            assertArrayEquals(intArrayOf(5), index.chunksOverlapping(500_000.0, 4_501_500.0, 500_122.0, 4_501_500.0))
            val options = LidarImportOptions(rasterResolution = 128,
                focusBounds = NormalizedRasterBounds(0.0, 0.0, 1.0, 0.002))
            val result = LazTerrainReader.read(file, options)!!
            assertEquals(123, result.sampledPoints)
            assertEquals(LazTerrainReader.FocusWork(1, 6, 123), LazTerrainReader.lastFocusWork)
        } finally { LazChunkIndex.clearMemory(); root.deleteRecursively() }
    }
}
