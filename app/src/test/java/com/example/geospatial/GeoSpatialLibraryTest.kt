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

    @Test
    fun geographicToGridReturnsNullWithoutBounds() {
        val local = GeoSpatialLibrary.localGrid("Imported", 80, 60, 2.0)

        assertNull(GeoSpatialLibrary.geographicToGrid(43.12, -124.40, local))
    }

    @Test
    fun geographicToGridReturnsNullOutsideMappedArea() {
        val metadata = GeoSpatialLibrary.SITES_METADATA[0]
        val bounds = requireNotNull(metadata.bounds)

        // Well south-west of the site's bounds.
        assertNull(GeoSpatialLibrary.geographicToGrid(bounds.minLat - 1.0, bounds.minLon - 1.0, metadata))
    }

    @Test
    fun geographicToGridRoundTripsWithGridToGeographic() {
        val metadata = GeoSpatialLibrary.SITES_METADATA[0]
        val (lat, lon) = requireNotNull(GeoSpatialLibrary.gridToGeographic(30f, 70f, metadata))

        val grid = requireNotNull(GeoSpatialLibrary.geographicToGrid(lat, lon, metadata))

        assertEquals(30f, grid.first, 0.01f)
        assertEquals(70f, grid.second, 0.01f)
    }

    @Test
    fun formatDmsNeverPrintsSixtySeconds() {
        // 43.19999999° is 43°11'59.99996", which rounded to one decimal as "60.0" and printed
        // 43°11'60.0"N. The carry has to happen before the number is formatted.
        assertEquals("43\u00b012\u203200.0\u2033N", GeoSpatialLibrary.formatDms(43.19999999, isLatitude = true))
        // Same carry, one level up: 59'59.98" rounds to a whole degree.
        assertEquals("44\u00b000\u203200.0\u2033N", GeoSpatialLibrary.formatDms(43.99999999, isLatitude = true))
    }

    @Test
    fun formatDmsSplitsDegreesMinutesSeconds() {
        // 43° + 7'/60 + 30.0"/3600 = 43.125°
        assertEquals("43\u00b007\u203230.0\u2033N", GeoSpatialLibrary.formatDms(43.125, isLatitude = true))
        assertEquals("124\u00b024\u203231.2\u2033W", GeoSpatialLibrary.formatDms(-124.408667, isLatitude = false))
    }
}
