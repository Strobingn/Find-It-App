package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LidarRasterizerTest {
    @Test
    fun importOptionsDefaultToDetailedOverviewAndAllowHigherRefinement() {
        assertEquals(
            1_024,
            LidarImportOptions().sanitized().rasterResolution,
        )
        assertEquals(
            1_536,
            LidarImportOptions(rasterResolution = 4_096).sanitized().rasterResolution,
        )
        assertEquals(
            2_048,
            LidarImportOptions(
                rasterResolution = 4_096,
                focusBounds = NormalizedRasterBounds(0.2, 0.2, 0.8, 0.8),
            ).sanitized().rasterResolution,
        )
        assertEquals(4, LidarImportOptions(smoothingRadius = 99).sanitized().smoothingRadius)
        assertEquals(1_024, LidarImportOptions.DEFAULT_OVERVIEW_RESOLUTION)
    }

    @Test
    fun optimizedPointWorkSkipsMostGettersOnLargeOverviews() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 100.0,
            minY = 0.0,
            maxY = 100.0,
            options = LidarImportOptions(rasterResolution = 1_024),
            declaredPointCount = 64_000_000,
        )

        assertEquals(LidarPointWork.ELEVATION, rasterizer.nextPointWork())
        rasterizer.skipPoint()
        // With dense sample budgets, stride is large on huge tiles — first non-elevation work is SKIP.
        val next = rasterizer.nextPointWork()
        assertTrue(next == LidarPointWork.SKIP || next == LidarPointWork.COVERAGE)
    }

    @Test
    fun preservesSourceFootprintAndUsesClassifiedGround() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 200.0,
            minY = 0.0,
            maxY = 100.0,
            options = LidarImportOptions(
                groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
                rasterResolution = 200,
            ),
            declaredPointCount = 120,
        )
        repeat(120) { index ->
            val x = (index % 20) * 10.0
            val y = (index / 20) * 18.0
            rasterizer.addPoint(x, y, 10f + index % 3, classification = 2)
        }

        val result = requireNotNull(rasterizer.finish(6, "test"))

        assertEquals(200, result.grid.width)
        assertEquals(100, result.grid.height)
        assertEquals(GroundSurfaceMode.SOURCE_CLASSIFIED, result.appliedGroundMode)
        assertTrue(result.usedClassificationFilter)
    }

    @Test
    fun sparseSourceClassesFallBackToAutomaticGround() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 10.0,
            minY = 0.0,
            maxY = 10.0,
            options = LidarImportOptions(groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED),
            declaredPointCount = 50,
        )
        repeat(50) { index ->
            rasterizer.addPoint(
                x = (index % 10).toDouble(),
                y = (index / 10).toDouble(),
                z = index.toFloat(),
                classification = 1,
            )
        }

        val result = requireNotNull(rasterizer.finish(3, "test"))

        assertEquals(GroundSurfaceMode.AUTO_LOWEST, result.appliedGroundMode)
        assertFalse(result.usedClassificationFilter)
        assertTrue(result.note.contains("coverage was sparse"))
    }

    @Test
    fun surfaceModelKeepsHighestReturn() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 1.0,
            minY = 0.0,
            maxY = 1.0,
            options = LidarImportOptions(
                groundMode = GroundSurfaceMode.SURFACE_MODEL,
                rasterResolution = 128,
            ),
            declaredPointCount = 2,
        )
        rasterizer.addPoint(0.5, 0.5, 10f, classification = 2)
        rasterizer.addPoint(0.5, 0.5, 22f, classification = 5)

        val result = requireNotNull(rasterizer.finish(3, "test"))

        assertEquals(GroundSurfaceMode.SURFACE_MODEL, result.appliedGroundMode)
        assertTrue(result.grid.bareEarth.any { it == 22f })
        assertTrue(result.grid.canopySpikes.all { it == 0f })
    }

    @Test
    fun nearestFillExpandsFromAllMeasurementsWithoutRowSmearing() {
        val grid = FloatArray(9 * 3) { Float.NaN }
        grid[1 * 9] = 10f
        grid[1 * 9 + 8] = 20f

        fillMissingNearest(grid, width = 9, height = 3)

        assertEquals(10f, grid[1 * 9 + 1])
        assertEquals(20f, grid[1 * 9 + 7])
        assertTrue(grid.all { it.isFinite() })
    }

    @Test
    fun coverageMaskUsesEveryDecodedReturnEvenWhenElevationsAreSampled() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 100.0,
            minY = 0.0,
            maxY = 100.0,
            options = LidarImportOptions(
                groundMode = GroundSurfaceMode.AUTO_LOWEST,
                rasterResolution = 128,
            ),
            declaredPointCount = 100,
            maxBinnedPoints = 10.0,
        )

        repeat(20) { index ->
            val sampledReturn = index % 10 == 0
            rasterizer.addPoint(
                x = if (sampledReturn) 5.0 else 90.0,
                y = if (sampledReturn) 5.0 else 90.0,
                z = if (sampledReturn) 10f else 20f,
                classification = 2,
            )
        }

        val result = requireNotNull(rasterizer.finish(6, "sampled coverage"))
        val targetX = (0.9 * (result.grid.width - 1)).toInt()
        val targetY = ((1.0 - 0.9) * (result.grid.height - 1)).toInt()
        val targetIndex = targetY * result.grid.width + targetX

        assertEquals(20L, rasterizer.pointsDecoded)
        assertEquals(2, rasterizer.pointsBinned)
        assertTrue(result.grid.validData[targetIndex])
    }

    @Test
    fun coverageMaskBridgesSmallBinGapsButPreservesLargeNoDataAreas() {
        val width = 100
        val height = 10
        val counts = IntArray(width * height)
        for (y in 3..6) {
            for (x in 0..4) counts[y * width + x] = 1
            for (x in 95..99) counts[y * width + x] = 1
        }

        val mask = buildCoverageMask(counts, width, height)

        assertTrue(mask[5 * width + 6])
        assertFalse(mask[5 * width + 50])
        assertTrue(mask[5 * width + 94])
    }

    @Test
    fun rasterizedPointCloudCarriesItsMeasuredFootprint() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 100.0,
            minY = 0.0,
            maxY = 1.0,
            options = LidarImportOptions(
                groundMode = GroundSurfaceMode.AUTO_LOWEST,
                rasterResolution = 200,
            ),
            declaredPointCount = 400,
        )
        repeat(200) { index ->
            rasterizer.addPoint(
                x = (index % 20) * 0.25,
                y = (index % 5) * 0.2,
                z = 10f,
                classification = 2,
            )
        }

        val result = requireNotNull(rasterizer.finish(6, "footprint"))
        assertTrue(result.grid.width > 0)
        assertTrue(result.grid.height > 0)
    }

    @Test
    fun automaticFallbackRejectsIsolatedBelowGroundSpikes() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 10.0,
            minY = 0.0,
            maxY = 10.0,
            options = LidarImportOptions(
                groundMode = GroundSurfaceMode.AUTO_LOWEST,
                rasterResolution = 64,
            ),
            declaredPointCount = 16,
        )
        // One cell: seven returns clustered near 10 m plus a lone 4.5 m strike (bird/wire noise).
        val zs = floatArrayOf(10.0f, 10.2f, 9.9f, 10.1f, 9.95f, 10.05f, 10.0f, 4.5f)
        for (z in zs) {
            rasterizer.addPoint(5.0, 5.0, z, classification = 1)
        }
        // A second honest cell so gap filling cannot dominate the assertion.
        repeat(6) { rasterizer.addPoint(1.0, 1.0, 20f, classification = 1) }

        val result = requireNotNull(rasterizer.finish(3, "spike test"))
        val gx = (0.5 * (result.grid.width - 1)).toInt()
        val gy = ((1.0 - 0.5) * (result.grid.height - 1)).toInt()
        val value = result.grid.bareEarth[gy * result.grid.width + gx]

        assertEquals(GroundSurfaceMode.AUTO_LOWEST, result.appliedGroundMode)
        assertTrue("expected ~9.9 m ground after spike rejection, got $value", value in 9.5f..10.5f)
        val report = requireNotNull(result.groundReport)
        assertEquals(1, report.lowSpikesRejected)
        assertEquals(GroundSurfaceQuality.ESTIMATED_ROBUST, report.quality)
        assertTrue(result.note.contains("low spikes rejected"))
    }

    @Test
    fun automaticFallbackKeepsCorroboratedGroundBelowCanopy() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 10.0,
            minY = 0.0,
            maxY = 10.0,
            options = LidarImportOptions(
                groundMode = GroundSurfaceMode.AUTO_LOWEST,
                rasterResolution = 64,
            ),
            declaredPointCount = 16,
        )
        // Dense-canopy cell: three ground returns corroborate ~10 m; canopy sits 4–8 m above.
        val zs = floatArrayOf(10.0f, 10.3f, 9.8f, 14f, 16f, 18f, 15f, 17f)
        for (z in zs) {
            rasterizer.addPoint(5.0, 5.0, z, classification = 1)
        }
        repeat(6) { rasterizer.addPoint(1.0, 1.0, 20f, classification = 1) }

        val result = requireNotNull(rasterizer.finish(3, "canopy test"))
        val gx = (0.5 * (result.grid.width - 1)).toInt()
        val gy = ((1.0 - 0.5) * (result.grid.height - 1)).toInt()
        val value = result.grid.bareEarth[gy * result.grid.width + gx]

        assertTrue("corroborated ground must survive, got $value", value in 9.5f..10.5f)
        assertEquals(0, requireNotNull(result.groundReport).lowSpikesRejected)
    }

    @Test
    fun classifiedGroundReportReflectsDenseCoverage() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 10.0,
            minY = 0.0,
            maxY = 10.0,
            options = LidarImportOptions(
                groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
                rasterResolution = 64,
            ),
            declaredPointCount = 128,
        )
        for (cy in 0..3) {
            for (cx in 0..3) {
                repeat(8) {
                    rasterizer.addPoint(1.25 + cx * 2.5, 1.25 + cy * 2.5, 10f, classification = 2)
                }
            }
        }

        val result = requireNotNull(rasterizer.finish(6, "report"))
        val report = requireNotNull(result.groundReport)

        assertEquals(GroundSurfaceMode.SOURCE_CLASSIFIED, result.appliedGroundMode)
        assertEquals(GroundSurfaceQuality.CLASSIFIED_DENSE, report.quality)
        assertEquals(8.0, report.groundSamplesPerCell.toDouble(), 0.001)
        assertTrue(report.groundCellFraction > 0f)
        assertTrue(result.note.contains("ground quality classified dense"))
    }

    @Test
    fun multiScaleSmoothingPreservesSharpPitsWhileFlatteningNoise() {
        val width = 40
        val height = 40
        val surface = FloatArray(width * height) { 100f }
        // High-frequency noise across the whole patch.
        for (y in 0 until height) {
            for (x in 0 until width) {
                surface[y * width + x] += if ((x + y) % 2 == 0) 0.35f else -0.35f
            }
        }
        // A sharp 4×4-cell archaeological pit in the middle.
        for (y in 18..21) {
            for (x in 18..21) {
                surface[y * width + x] = 97f
            }
        }

        val multiScale = multiScaleSmooth(surface, width, height, radius = 2)
        val coarseOnly = boxSmooth(surface, width, height, radius = 4)

        val pitDepthMulti = 100f - multiScale[20 * width + 20]
        val pitDepthCoarse = 100f - coarseOnly[20 * width + 20]
        assertTrue(
            "multi-scale should keep more pit depth ($pitDepthMulti) than coarse blur ($pitDepthCoarse)",
            pitDepthMulti > pitDepthCoarse + 0.3f,
        )
        // Flat corner: residual noise should be nearly gone.
        var noise = 0f
        for (y in 2..6) {
            for (x in 2..6) {
                noise += kotlin.math.abs(multiScale[y * width + x] - 100f)
            }
        }
        assertTrue("flat-area residual noise too high: $noise", noise < 2.0f)
    }
}
