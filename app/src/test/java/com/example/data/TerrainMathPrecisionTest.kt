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

    @Test fun triangulationReproducesAnAffinePlaneAcrossTileSeams() {
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
        assertTrue(result.diagnostics.tilesTriangulated > 1)
        for (y in 8..88) for (x in 8..88) {
            val index = y * width + x
            assertTrue("valid at $x,$y", result.validData[index])
            assertEquals("plane at $x,$y", plane(x, y), result.elevations[index], 0.0001f)
            if (mask[index]) assertFalse(result.interpolatedData[index])
        }
    }

    @Test fun idwWeightsUsePhysicalDistanceOnRectangularCells() {
        val elevations = FloatArray(25) { Float.NaN }
        val valid = BooleanArray(25)
        for ((index, z) in listOf(10 to 10f, 2 to 20f, 14 to 40f)) {
            elevations[index] = z
            valid[index] = true
        }
        val interpolated = BooleanArray(25)
        fillBoundedIdw(elevations, valid, interpolated, BooleanArray(25) { true },
            5, 5, 2, 2, 3, 3, 4, scaleX = 1.0, scaleY = 0.5)
        // Centre-to-left/right distance = 2; centre-to-top = 1 in physical units.
        assertEquals((130.0 / 6.0).toFloat(), elevations[12], 0.00001f)
        assertTrue(interpolated[12])
    }

    @Test fun lodRetainsIndependentAxisSpacing() {
        val source = ElevationGrid(128, 64, FloatArray(128 * 64), FloatArray(128 * 64),
            cellSizeMeters = 2f, cellSizeXMeters = 1f, cellSizeYMeters = 2f)
        val coarse = TerrainLodPyramid.downsample2x(source)
        assertEquals(2f, coarse.cellSizeXMeters, 0f)
        assertEquals(4f, coarse.cellSizeYMeters, 0f)
    }
}
