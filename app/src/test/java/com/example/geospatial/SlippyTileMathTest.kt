package com.example.geospatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlippyTileMathTest {

    @Test
    fun zoomZeroIsAlwaysTheOnlyTile() {
        assertEquals(0, SlippyTileMath.lonToTileX(0.0, zoom = 0))
        assertEquals(0, SlippyTileMath.latToTileY(0.0, zoom = 0))
    }

    @Test
    fun originMapsToTheCenterTileAtAModerateZoom() {
        val zoom = 4
        val tilesAcross = 1 shl zoom
        assertEquals(tilesAcross / 2, SlippyTileMath.lonToTileX(0.0, zoom))
        assertEquals(tilesAcross / 2, SlippyTileMath.latToTileY(0.0, zoom))
    }

    @Test
    fun boundsToTileRangeOrdersMinAndMaxCorrectly() {
        val bounds = GeoSpatialLibrary.GeographicBounds(minLat = 43.1201, maxLat = 43.1209, minLon = -124.4087, maxLon = -124.4077)
        val range = SlippyTileMath.boundsToTileRange(bounds, zoom = 15)

        assertTrue(range.minX <= range.maxX)
        assertTrue(range.minY <= range.maxY)
        assertTrue(range.tileCount >= 1)
    }

    @Test
    fun chooseZoomForBoundsRespectsTheTileBudget() {
        val bounds = GeoSpatialLibrary.GeographicBounds(minLat = 40.0, maxLat = 45.0, minLon = -125.0, maxLon = -120.0)
        val zoom = SlippyTileMath.chooseZoomForBounds(bounds, maxTiles = 36, maxZoom = 17)

        val range = SlippyTileMath.boundsToTileRange(bounds, zoom)
        assertTrue(range.tileCount <= 36)
    }
}
