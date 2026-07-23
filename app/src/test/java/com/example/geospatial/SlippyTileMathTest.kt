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

    @Test
    fun fractionalCoordinateFloorsToTheSameIntegerTile() {
        val zoom = 12
        val lon = -124.408
        val lat = 43.121

        assertEquals(SlippyTileMath.lonToTileX(lon, zoom), Math.floor(SlippyTileMath.lonToTileXFraction(lon, zoom)).toInt())
        assertEquals(SlippyTileMath.latToTileY(lat, zoom), Math.floor(SlippyTileMath.latToTileYFraction(lat, zoom)).toInt())
    }

    @Test
    fun fractionalCoordinatesPlaceBoundsStrictlyInsideTheirTileRange() {
        // A small bounds box (site-sized), not aligned to any tile boundary.
        val bounds = GeoSpatialLibrary.GeographicBounds(minLat = 43.1201, maxLat = 43.1209, minLon = -124.4087, maxLon = -124.4077)
        val zoom = 17
        val range = SlippyTileMath.boundsToTileRange(bounds, zoom)

        val xMinFrac = SlippyTileMath.lonToTileXFraction(bounds.minLon, zoom) - range.minX
        val xMaxFrac = SlippyTileMath.lonToTileXFraction(bounds.maxLon, zoom) - range.minX
        val yMinFrac = SlippyTileMath.latToTileYFraction(bounds.maxLat, zoom) - range.minY
        val yMaxFrac = SlippyTileMath.latToTileYFraction(bounds.minLat, zoom) - range.minY

        val tileSpanX = (range.maxX - range.minX + 1).toDouble()
        val tileSpanY = (range.maxY - range.minY + 1).toDouble()
        assertTrue(xMinFrac in 0.0..tileSpanX)
        assertTrue(xMaxFrac in 0.0..tileSpanX)
        assertTrue(yMinFrac in 0.0..tileSpanY)
        assertTrue(yMaxFrac in 0.0..tileSpanY)
        // The crop region should be narrower than the full stitched tile range whenever the
        // bounds don't happen to exactly fill it — otherwise cropping would be a no-op.
        assertTrue((xMaxFrac - xMinFrac) <= tileSpanX)
        assertTrue((yMaxFrac - yMinFrac) <= tileSpanY)
    }
}
