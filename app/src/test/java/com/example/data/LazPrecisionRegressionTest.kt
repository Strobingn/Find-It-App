package com.example.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LazPrecisionRegressionTest {
    @Test fun withheldAndNoiseCannotContaminateAnySurfaceMode() {
        for (mode in GroundSurfaceMode.entries) {
            val rasterizer = LidarRasterizer(0.0, 127.0, 0.0, 127.0,
                LidarImportOptions(groundMode = mode, rasterResolution = 128), 127L * 127 + 3)
            for (y in 0..127) for (x in 0..127) {
                rasterizer.addPoint(x.toDouble(), y.toDouble(), 40f, 2)
            }
            rasterizer.addPoint(64.0, 64.0, -100f, 2, isWithheld = true)
            rasterizer.addPoint(64.0, 64.0, -100f, 7)
            rasterizer.addPoint(64.0, 64.0, 1000f, 18)
            val result = rasterizer.finish(0, "fixture")!!
            assertEquals(mode, result.appliedGroundMode)
            assertEquals(40f, result.grid.bareEarth[63 * 128 + 64], 0f)
            assertEquals(0f, result.grid.canopySpikes[64 * 128 + 64], 0f)
            assertEquals(128 * 128, result.sampledPoints)
            assertEquals(128 * 128 + 3, result.totalPointsRead)
        }
    }

    @Test fun classifiedCellarBottomIsNotMedianReplaced() {
        val rasterizer = LidarRasterizer(0.0, 127.0, 0.0, 127.0,
            LidarImportOptions(rasterResolution = 128), 128L * 128)
        for (y in 0..127) for (x in 0..127) {
            rasterizer.addPoint(x.toDouble(), y.toDouble(), if (x == 64 && y == 64) 30f else 40f, 2)
        }
        val result = rasterizer.finish(0, "fixture")!!
        assertEquals(GroundSurfaceMode.SOURCE_CLASSIFIED, result.appliedGroundMode)
        assertEquals(30f, result.grid.bareEarth[63 * 128 + 64], 0f)
    }

    @Test fun class8FollowsRecordEncodingIncludingLegacyLas14Payloads() {
        assertTrue(isSourceGround(8, versionMajor = 1, versionMinor = 4, pointFormat = 0))
        assertFalse(isSourceGround(8, versionMajor = 1, versionMinor = 4, pointFormat = 6))
        assertTrue(isSourceGround(8, versionMajor = 1, versionMinor = 3, pointFormat = 0))
    }

    @Test fun packedDecoderHonoursWithheldAbiAndRejectsOverflow() {
        val rasterizer = LidarRasterizer(0.0, 10.0, 0.0, 10.0,
            LidarImportOptions(rasterResolution = 128), 2)
        val packed = PackedPointRasterizer.allocate(2)
        for (flags in listOf(0, 2)) {
            packed.putFloat(5f).putFloat(5f).putFloat(if (flags == 0) 40f else -100f)
                .put(2).put(flags.toByte()).putShort(0)
        }
        assertTrue(PackedPointRasterizer.addAll(rasterizer, packed, 2))
        assertEquals(1, rasterizer.finish(0, "packed")!!.sampledPoints)
        assertThrows(IllegalArgumentException::class.java) { PackedPointRasterizer.allocate(Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { PackedPointRasterizer.addAll(rasterizer, packed, 3) }
    }

    @Test fun rawLasStopsBeforeTrailingMetadataAndRejectsShortPayload() {
        val root = Files.createTempDirectory("las-count").toFile()
        try {
            val file = LasFixture.writeLattice(File(root, "points.las"), 5, 5)
            file.appendBytes(file.readBytes().copyOfRange(227, 227 + 20))
            val result = file.inputStream().use {
                DemGenerator.parseLasStreamDetailed(it, LidarImportOptions(rasterResolution = 128))
            }!!
            assertEquals(25, result.totalPointsRead)
            val short = file.readBytes().copyOf(227 + 24 * 20)
            assertNull(DemGenerator.parseLasStreamDetailed(short.inputStream(), LidarImportOptions(rasterResolution = 128)))
        } finally { root.deleteRecursively() }
    }

    @Test fun rawLasWithheldFlagsWorkInLegacyAndExtendedFormats() {
        for (format in listOf(0, 6)) {
            val headerSize = if (format == 0) 227 else 375
            val recordLength = if (format == 0) 20 else 30
            val bytes = ByteArray(headerSize + recordLength * 2)
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            "LASF".toByteArray().copyInto(bytes)
            b.put(24, 1).put(25, if (format == 0) 2 else 4)
            b.putInt(96, headerSize).put(104, format.toByte()).putShort(105, recordLength.toShort())
            if (format == 0) b.putInt(107, 2) else b.putLong(247, 2)
            for (offset in listOf(131, 139, 147)) b.putDouble(offset, 0.01)
            b.putDouble(179, 10.0).putDouble(195, 10.0)
            for (i in 0..1) {
                val start = headerSize + recordLength * i
                b.putInt(start, 500).putInt(start + 4, 500).putInt(start + 8, if (i == 0) 4000 else -10000)
                if (format == 0) b.put(start + 15, if (i == 0) 2 else 0x82.toByte())
                else { b.put(start + 16, 2); b.put(start + 15, if (i == 0) 0 else 4) }
            }
            val result = DemGenerator.parseLasStreamDetailed(bytes.inputStream(), LidarImportOptions(rasterResolution = 128))!!
            assertEquals(1, result.sampledPoints)
            assertEquals(2, result.totalPointsRead)
            assertTrue(result.grid.bareEarth.filter(Float::isFinite).all { it == 40f })
        }
    }

    @Test fun diskCachePreservesProvenanceAndSupportsMaximumRefineGrid() {
        val root = Files.createTempDirectory("precision-cache").toFile()
        try {
            val source = File(root, "source.laz").apply { writeText("fixture") }
            val cache = LazTerrainDiskCache(File(root, "cache"), 80L * 1024 * 1024)
            val options = LidarImportOptions(rasterResolution = 2048, focusBounds = NormalizedRasterBounds.Full)
            val size = 2048 * 2048
            val interpolated = BooleanArray(size) { it % 2 == 0 }
            val grid = ElevationGrid(2048, 2048, FloatArray(size) { 40f }, FloatArray(size),
                interpolatedData = interpolated, cellSizeXMeters = 0.5f, cellSizeYMeters = 0.75f)
            cache.put(source, options, DemGenerator.TerrainLoadResult(grid, "fixture", true))
            val restored = cache.get(source, options)!!.grid
            assertArrayEquals(interpolated, restored.interpolatedData)
            assertEquals(2048, restored.width)
            assertEquals(0.5f, restored.cellSizeXMeters, 0f)
            assertEquals(0.75f, restored.cellSizeYMeters, 0f)
        } finally { root.deleteRecursively() }
    }

    @Test fun parallelAndSequentialReadersAreIdenticalAcrossRingWraps() {
        val root = Files.createTempDirectory("chunk-ring").toFile()
        val oldParallel = LazTerrainReader.parallelDecodeEnabled
        val oldLowMemory = AppMemoryBudget.lowMemoryOverride
        try {
            val file = LasFixture.write(File(root, "points.las"), 450_123)
            val options = LidarImportOptions(rasterResolution = 128)
            AppMemoryBudget.lowMemoryOverride = true // 2 workers, a four-slot ring wraps twice.
            RandomAccessFile(file, "rw").use { raw ->
                // Invalid returns at a chunk boundary and the short final chunk.
                for ((point, classification) in listOf(49_999 to 7, 50_000 to 0x82, 450_122 to 18)) {
                    raw.seek(227L + point * 20L + 15)
                    raw.writeByte(classification)
                }
            }
            val compressed = File(root, "points.laz")
            requireNotNull(javaClass.getResourceAsStream("/laz/precision-chunks.laz")).use { input ->
                compressed.outputStream().use { output -> input.copyTo(output) }
            }
            for (source in listOf(file, compressed)) {
                LazTerrainReader.parallelDecodeEnabled = false
                val sequential = LazTerrainReader.read(source, options)!!
                LazTerrainReader.parallelDecodeEnabled = true
                val parallel = LazTerrainReader.read(source, options)!!
                assertEquals(450_123, parallel.totalPointsRead)
                assertEquals(450_120, parallel.sampledPoints)
                if (Runtime.getRuntime().availableProcessors() > 1) assertTrue(parallel.note.contains("parallel chunk decode"))
                assertEquals(sequential.totalPointsRead, parallel.totalPointsRead)
                assertArrayEquals(sequential.grid.bareEarth, parallel.grid.bareEarth, 0f)
                assertArrayEquals(sequential.grid.validData, parallel.grid.validData)
                assertArrayEquals(sequential.grid.interpolatedData, parallel.grid.interpolatedData)
                assertNull(LazTerrainReader.read(source, options, shouldContinue = { false }))
            }
        } finally {
            LazTerrainReader.parallelDecodeEnabled = oldParallel
            AppMemoryBudget.lowMemoryOverride = oldLowMemory
            root.deleteRecursively()
        }
    }
}
