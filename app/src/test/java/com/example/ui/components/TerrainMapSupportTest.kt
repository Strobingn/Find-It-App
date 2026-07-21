package com.example.ui.components

import com.example.geospatial.GeoSpatialLibrary.GeographicBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainMapSupportTest {
    @Test
    fun geographicMapRequiresBoundsAndARealKey() {
        val bounds = GeographicBounds(43.0, 44.0, -125.0, -124.0)

        assertTrue(shouldUseGeographicMap(bounds, "live-key"))
        assertFalse(shouldUseGeographicMap(null, "live-key"))
        assertFalse(shouldUseGeographicMap(bounds, ""))
        assertFalse(shouldUseGeographicMap(bounds, "YOUR_API_KEY"))
        assertFalse(shouldUseGeographicMap(bounds, "DEFAULT_API_KEY"))
        assertFalse(shouldUseGeographicMap(GeographicBounds(44.0, 43.0, -125.0, -124.0), "live-key"))
    }

    @Test
    fun visibleGeographicBoundsMapBackToTheTerrainFootprint() {
        val layer = GeographicBounds(0.0, 10.0, 20.0, 30.0)
        val visible = GeographicBounds(2.0, 8.0, 22.0, 28.0)

        val (viewport, zoom) = normalizedViewportFor(layer, visible)

        assertEquals(0.2, viewport.left, 0.0001)
        assertEquals(0.2, viewport.top, 0.0001)
        assertEquals(0.8, viewport.right, 0.0001)
        assertEquals(0.8, viewport.bottom, 0.0001)
        assertEquals(1.6667f, zoom, 0.001f)
    }

    @Test
    fun mapViewportOutsideTheLayerFallsBackToTheWholeFootprint() {
        val layer = GeographicBounds(0.0, 10.0, 20.0, 30.0)
        val visible = GeographicBounds(-5.0, 15.0, 15.0, 35.0)

        val (viewport, zoom) = normalizedViewportFor(layer, visible)

        assertEquals(0.0, viewport.left, 0.0)
        assertEquals(0.0, viewport.top, 0.0)
        assertEquals(1.0, viewport.right, 0.0)
        assertEquals(1.0, viewport.bottom, 0.0)
        assertEquals(1f, zoom, 0f)
    }
}
