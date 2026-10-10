package com.example.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerrainPerformanceTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun stageProfilerSeparatesDecodeGridAndMesh() {
        TerrainStageProfiler.reset()
        TerrainStageProfiler.stage("decode") { Thread.sleep(2) }
        TerrainStageProfiler.stage("grid") { Thread.sleep(1) }
        TerrainStageProfiler.stage("mesh") { 1 + 1 }
        val names = TerrainStageProfiler.snapshot().map { it.name }
        assertTrue(names.containsAll(listOf("decode", "grid", "mesh")))
        assertTrue(TerrainStageProfiler.formatReport().contains("decode"))
    }

    @Test
    fun packedSinkBinsWithoutPerPointObjects() {
        val rasterizer = LidarRasterizer(
            minX = 0.0, maxX = 10.0, minY = 0.0, maxY = 10.0,
            options = LidarImportOptions(rasterResolution = 32, groundMode = GroundSurfaceMode.AUTO_LOWEST),
            declaredPointCount = 4,
        )
        val packed = PackedPointRasterizer.allocate(4)
        repeat(4) { i ->
            packed.putFloat(i * 2f)
            packed.putFloat(i * 2f)
            packed.putFloat(10f + i)
            packed.put(2)
            packed.put(0)
            packed.putShort(0)
        }
        packed.flip()
        assertTrue(PackedPointRasterizer.addAll(rasterizer, packed, 4))
        val result = requireNotNull(rasterizer.finish(0, "packed"))
        assertEquals(4L, rasterizer.pointsDecoded)
        assertTrue(result.grid.validData.any { it })
    }

    @Test
    fun cdlodKeepsFullResolutionNearTheViewCenter() {
        val nodes = HeightmapCdlod.select(
            width = 128,
            height = 128,
            rangeInCells = 24f,
            maxLevel = 4,
            rootSize = 16,
        )
        assertTrue(nodes.isNotEmpty())
        val center = nodes.filter {
            val cx = it.originX + it.sizeCells / 2
            val cy = it.originY + it.sizeCells / 2
            kotlin.math.abs(cx - 64) < 24 && kotlin.math.abs(cy - 64) < 24
        }
        assertTrue("center of the DEM must have a level-0 (full-res) node", center.any { it.isFullResolution })
        val grid = sample(64, 64)
        val packed = HeightmapCdlod.pack(grid)
        assertEquals(64 * 64, packed.floats.size)
        val near = CdlodNode(level = 0, originX = 0, originY = 0, sizeCells = 16)
        val (vertices, indices) = HeightmapCdlod.meshNode(packed, near)
        assertEquals(17 * 17 * TerrainGpuBatch.FLOATS_PER_VERTEX, vertices.size)
        assertTrue(indices.isNotEmpty())
        assertEquals(1, HeightmapCdlod.vertexStep(near))
    }

    @Test
    fun hornComputeFallbackNeverPaintsNanAsZero() {
        val elev = FloatArray(9) { if (it == 4) 10f else Float.NaN }
        val valid = BooleanArray(9) { it == 4 }
        val shade = GpuAnalysisKernels.hornShadeCpu(elev, valid, 3, 3, 1f)
        assertTrue(shade[0].isNaN())
        assertTrue(shade[4] in 0.18f..1f)
        assertTrue(GpuAnalysisKernels.HORN_HILLSHADE_COMPUTE.contains("310 es"))
        assertTrue(GpuAnalysisKernels.HORN_HILLSHADE_COMPUTE.contains("isnan"))
    }

    @Test
    fun decoderBenchmarkTimesLaszipAndCacheReopen() {
        val file = LasFixture.write(folder.newFile("bench.las"), pointCount = 4_000, extentMeters = 200.0)
        val result = TerrainDecoderBenchmark.run(
            file = file,
            options = LidarImportOptions(rasterResolution = 128, groundMode = GroundSurfaceMode.AUTO_LOWEST),
            cacheDir = folder.newFolder("cache"),
        )
        assertFalse(result.nativeAvailable)
        assertTrue(result.laszip4jMs > 0.0)
        assertTrue(result.rasterizeMs > 0.0)
        assertTrue(result.meshMs > 0.0)
        assertTrue(result.cacheReadMs >= 0.0)
        assertTrue(result.cacheWriteMs >= 0.0)
        assertEquals("laszip4j (native unavailable)", result.winner)
        assertTrue(result.points >= 1)
        println(result.format())
    }

    @Test
    fun intIndexTilesExceedUnsignedShortAndStillBuild() {
        val grid = sample(512, 256)
        val scene = TerrainGpuSceneBuilder.build(
            source = grid,
            maxFinestDimension = 512,
            tileSize = 400,
            useIntIndices = true,
            attachHeightmap = true,
        )
        assertTrue(scene.heightmap != null)
        assertTrue(scene.cdlodNodes.isNotEmpty())
        assertTrue(scene.levels.flatMap { it.batches }.any { it.usesIntIndices })
        TerrainMemoryMonitor.noteGpuScene(scene)
        assertTrue(TerrainMemoryMonitor.snapshot().estimatedGpuBytes > 0L)
    }

    @Test
    fun rasterizerHotPathAndFullResMeshHaveHostNumbers() {
        val rasterizer = LidarRasterizer(
            minX = 0.0, maxX = 400.0, minY = 0.0, maxY = 400.0,
            options = LidarImportOptions(rasterResolution = 256, groundMode = GroundSurfaceMode.AUTO_LOWEST),
            declaredPointCount = 200_000,
        )
        val binMs = kotlin.system.measureNanoTime {
            repeat(200_000) { i ->
                rasterizer.addPoint(
                    x = (i % 400).toDouble(),
                    y = (i / 400 % 400).toDouble(),
                    z = 10f,
                    classification = 2,
                )
            }
        } / 1_000_000.0
        val grid = requireNotNull(rasterizer.finish(0, "throughput")).grid
        val meshMs = kotlin.system.measureNanoTime {
            TerrainGpuSceneBuilder.build(
                source = grid,
                maxFinestDimension = 256,
                tileSize = 128,
                useIntIndices = true,
                attachHeightmap = true,
            )
        } / 1_000_000.0
        println(String.format(java.util.Locale.US, "hotpath bin 200k pts %.2f ms (%.1f kpts/s)", binMs, 200_000.0 / binMs))
        println(String.format(java.util.Locale.US, "mesh+heightmap 256² %.2f ms", meshMs))
        assertTrue(binMs > 0.0)
        assertTrue(meshMs > 0.0)
    }

    @Test
    fun decoderPreferenceFallsBackWhenNativeMissing() {
        LazDecoderSettings.preferred = LazDecoderBackend.NATIVE_LAZ_RS
        assertEquals(LazDecoderBackend.LASZIP4J, LazDecoderSettings.resolve())
        LazDecoderSettings.preferred = LazDecoderBackend.LASZIP4J
        assertEquals(LazDecoderBackend.LASZIP4J, LazDecoderSettings.resolve())
        assertFalse(LazRsNativeDecoder.isAvailable)
    }

    private fun sample(width: Int, height: Int): ElevationGrid {
        val size = width * height
        return ElevationGrid(
            width = width,
            height = height,
            bareEarth = FloatArray(size) { (it % width + it / width).toFloat() },
            canopySpikes = FloatArray(size),
            validData = BooleanArray(size) { true },
        )
    }
}
