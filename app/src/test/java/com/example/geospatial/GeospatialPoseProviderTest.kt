package com.example.geospatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeospatialPoseProviderTest {

    @Test
    fun decideMode_geospatialWhenAllReady() {
        val d = GeospatialPoseProvider.decideMode(
            arCoreInstalled = true,
            geospatialApiAvailable = true,
            vpsCoverageLikely = true,
            hasGps = true,
            hasHeading = true,
        )
        assertEquals(GeospatialPoseProvider.TrackingMode.GEOSPATIAL, d.mode)
        assertTrue(d.useGeospatialApi)
    }

    @Test
    fun decideMode_worldAnchorWhenNoVps() {
        val d = GeospatialPoseProvider.decideMode(
            arCoreInstalled = true,
            geospatialApiAvailable = true,
            vpsCoverageLikely = false,
            hasGps = true,
            hasHeading = true,
        )
        assertEquals(GeospatialPoseProvider.TrackingMode.WORLD_ANCHOR, d.mode)
        assertFalse(d.useGeospatialApi)
    }

    @Test
    fun decideMode_compassWhenNoGps() {
        val d = GeospatialPoseProvider.decideMode(
            arCoreInstalled = false,
            geospatialApiAvailable = false,
            hasGps = false,
            hasHeading = true,
        )
        assertEquals(GeospatialPoseProvider.TrackingMode.COMPASS_FALLBACK, d.mode)
    }

    @Test
    fun sample_labelsMode() {
        val d = GeospatialPoseProvider.decideMode(
            arCoreInstalled = false,
            geospatialApiAvailable = false,
            hasGps = true,
            hasHeading = true,
        )
        val s = GeospatialPoseProvider.sample(d, 42.0, -74.0, 10f, 8f)
        assertTrue(s.label.contains("World anchor") || s.mode == GeospatialPoseProvider.TrackingMode.WORLD_ANCHOR)
        assertEquals(GeospatialPoseProvider.TrackingMode.WORLD_ANCHOR, s.mode)
    }
}
