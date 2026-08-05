package com.example.data.historicmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricMapGeoreferenceTest {
    @Test
    fun placementFromTwoPointSimilarityIsNearExpectedCenterAndSize() {
        // 1000×1000 image mapped to a ~100 m east × ~100 m north square around 41.0 N, -74.0 E.
        val points = listOf(
            HistoricMapControlPoint(0f, 0f, 41.00045, -74.0006),
            HistoricMapControlPoint(1000f, 1000f, 40.99955, -73.9994),
        )
        val fit = GeoReferencer.fit(points)
        assertNotNull(fit.transform)

        val placement = HistoricMapGeoreference.placementFromFit(fit, 1000, 1000)
        assertNotNull(placement)
        val p = requireNotNull(placement)
        assertEquals(41.0, p.centerLatitude, 0.001)
        assertEquals(-74.0, p.centerLongitude, 0.001)
        assertTrue("width ${p.widthMeters}", p.widthMeters in 50f..200f)
        assertTrue("height ${p.heightMeters}", p.heightMeters in 50f..200f)
    }

    @Test
    fun placementFromThreePointAffineRoundTripsCornerNearControl() {
        val points = listOf(
            HistoricMapControlPoint(0f, 0f, 42.0, -75.0),
            HistoricMapControlPoint(500f, 0f, 42.0, -74.99),
            HistoricMapControlPoint(0f, 500f, 41.99, -75.0),
        )
        val fit = GeoReferencer.fit(points)
        val transform = requireNotNull(fit.transform)
        val placement = requireNotNull(HistoricMapGeoreference.placementFromFit(fit, 500, 500))

        // Center should sit near the middle of the three-point footprint.
        assertTrue(placement.centerLatitude in 41.99..42.005)
        assertTrue(placement.centerLongitude in -75.005..-74.99)
        assertTrue(placement.widthMeters > 10f)
        assertTrue(placement.heightMeters > 10f)

        // Transform still maps control pixels near their ground truth.
        val (lat0, lon0) = transform.imageToWorld(0f, 0f)
        assertEquals(42.0, lat0, 1e-6)
        assertEquals(-75.0, lon0, 1e-6)
    }

    @Test
    fun placementNullWhenFitHasNoTransform() {
        val fit = GeoReferencer.fit(emptyList())
        assertEquals(null, HistoricMapGeoreference.placementFromFit(fit, 100, 100))
    }
}
