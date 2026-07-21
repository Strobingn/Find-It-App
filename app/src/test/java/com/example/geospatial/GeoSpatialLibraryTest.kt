package com.example.geospatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoSpatialLibraryTest {
    @Test
    fun localGridDoesNotInventCoordinates() {
        val local = GeoSpatialLibrary.localGrid("Imported", 80, 60, 2.0)

        assertNull(GeoSpatialLibrary.gridToGeographic(50f, 50f, local))
        assertTrue(!local.isGeoreferenced)
    }

    @Test
    fun utmZoneIsSelectedFromLongitude() {
        val oregon = GeoSpatialLibrary.geographicToUtm(43.12, -124.40)
        val washingtonDc = GeoSpatialLibrary.geographicToUtm(38.90, -77.04)

        assertEquals(10, oregon.zone)
        assertEquals(18, washingtonDc.zone)
        assertEquals('N', oregon.hemisphere)
        assertTrue(oregon.easting in 100_000.0..900_000.0)
        assertTrue(oregon.northing > 0)
    }
}
