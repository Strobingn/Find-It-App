package com.example.data

import com.example.gps.crs.WktCrsParser
import org.junit.Assert.*
import org.junit.Test

class TerrainMathPrecisionTest {
    @Test fun aspectReportsDownslopeCompassDirection() {
        assertEquals(270f, terrainAspectDegrees(1f, 0f), 0.001f) // rises east, faces west
        assertEquals(90f, terrainAspectDegrees(-1f, 0f), 0.001f)
        assertEquals(0f, terrainAspectDegrees(0f, 1f), 0.001f) // rises south, faces north
        assertEquals(180f, terrainAspectDegrees(0f, -1f), 0.001f)
    }

    @Test fun rasterizerUsesIndependentHorizontalAndVerticalUnits() {
        // XY in feet, Z in metres: a common mixed-unit survey.
        val mixed = LidarRasterizer(0.0, 127.0, 0.0, 12.7,
            LidarImportOptions(rasterResolution = 128), 1,
            horizontalUnitToMeters = 0.3048, verticalUnitToMeters = 1.0)
        mixed.addPoint(63.5, 6.35, 40f, 2)
        val grid = mixed.finish(0, "feet XY, metres Z")!!.grid
        assertEquals(0.3048f, grid.cellSizeXMeters, 0.000001f)
        assertEquals((12.7 * 0.3048 / (grid.height - 1)).toFloat(), grid.cellSizeYMeters, 0.000001f)
        assertTrue(grid.bareEarth.filter(Float::isFinite).all { it == 40f })
        val feetZ = LidarRasterizer(0.0, 127.0, 0.0, 127.0,
            LidarImportOptions(rasterResolution = 128), 1, verticalUnitToMeters = 0.3048)
        feetZ.addPoint(63.5, 63.5, 100f, 2)
        assertEquals(30.48f, feetZ.finish(0, "feet Z")!!.grid.bareEarth.first(Float::isFinite), 0.00001f)
    }

    @Test fun verticalWktUnitIsSeparateFromProjectedUnit() {
        val compound = """COMPD_CS["survey",PROJCS["feet XY",UNIT["Foot_US",0.3048006096012192]],VERT_CS["NAVD88",VERT_DATUM["NAVD88",2005],UNIT["metre",1.0],AXIS["Up",UP]]]"""
        assertEquals(0.3048006096012192, WktCrsParser.horizontalUnitToMeters(compound)!!, 0.0)
        assertEquals(1.0, WktCrsParser.verticalUnitToMeters(compound)!!, 0.0)
        assertNull(WktCrsParser.verticalUnitToMeters("""PROJCS["feet XY",UNIT["Foot_US",0.3048006096012192]]"""))
        assertEquals(0.3048, WktCrsParser.verticalUnitToMeters("""VERTCRS["height",VDATUM["NAVD88"],CS[vertical,1],AXIS["height",up,LENGTHUNIT["foot",0.3048]]]""")!!, 0.0)
    }

    @Test fun harmonicFillPreservesMeasuredAffinePlaneSamples() {
        val width = 97
        val height = 97
        val mask = BooleanArray(width * height)
        val values = FloatArray(mask.size) { Float.NaN }
        fun plane(x: Int, y: Int) = 100f + x * 0.25f - y * 0.125f
        for (y in 0 until height step 4) for (x in 0 until width step 4) {
            mask[y * width + x] = true
            values[y * width + x] = plane(x, y)
        }
        val result = interpolateBareEarth(values, mask, width, height, 1f, 625, 625)
        assertTrue(result.diagnostics.interpolatedCells > 0)
        for (y in 8..88) for (x in 8..88) {
            val index = y * width + x
            assertTrue("valid at $x,$y", result.validData[index])
            assertEquals("plane at $x,$y", plane(x, y), result.elevations[index], 0.06f)
            if (mask[index]) assertFalse(result.interpolatedData[index])
        }
    }

    @Test fun harmonicWeightsUsePhysicalDistanceOnRectangularCells() {
        val elevations = FloatArray(9) { 0f }
        elevations[1] = 20f; elevations[3] = 10f; elevations[5] = 40f; elevations[7] = 10f
        val valid = BooleanArray(9) { it != 4 }
        elevations[4] = Float.NaN
        val interpolated = BooleanArray(9)
        fillVoidsSmooth(elevations, valid, interpolated, BooleanArray(9) { true },
            3, 3, scaleX = 1.0, scaleY = 0.5)
        assertEquals(17f, elevations[4], 0.00001f) // Laplacian weights X=1, Y=4
        assertTrue(interpolated[4])
    }

    @Test fun distanceTransformMatchesBruteForcePhysicalDisc() {
        val width = 17; val height = 13
        val measured = BooleanArray(width * height) { it in listOf(0, 37, 101, 218) }
        for ((sx, sy) in listOf(1.0 to 1.0, 0.25 to 2.0, 3.0 to 0.5)) {
            val actual = dilateMask(measured, width, height, 3, sx, sy)
            for (y in 0 until height) for (x in 0 until width) {
                val expected = measured.indices.any { i ->
                    measured[i] && ((x - i % width) * sx).let { it * it } +
                        ((y - i / width) * sy).let { it * it } <= 9.0
                }
                assertEquals("disc at $x,$y ($sx,$sy)", expected, actual[y * width + x])
            }
        }
    }

    @Test fun lodRetainsIndependentAxisSpacing() {
        val source = ElevationGrid(128, 64, FloatArray(128 * 64), FloatArray(128 * 64),
            cellSizeMeters = 2f, cellSizeXMeters = 1f, cellSizeYMeters = 2f)
        val coarse = TerrainLodPyramid.downsample2x(source)
        assertEquals(2f, coarse.cellSizeXMeters, 0f)
        assertEquals(4f, coarse.cellSizeYMeters, 0f)
    }
}
