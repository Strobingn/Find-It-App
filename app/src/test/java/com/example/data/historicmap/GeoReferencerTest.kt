package com.example.data.historicmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoReferencerTest {
    // Known transform: lon = 0.001x + 0.0002y - 74.0 ; lat = 0.0001x - 0.0009y + 41.5
    private fun knownTransformPoints(): List<HistoricMapControlPoint> = listOf(
        HistoricMapControlPoint(0f, 0f, 41.5, -74.0),
        HistoricMapControlPoint(100f, 0f, 41.51, -73.9),
        HistoricMapControlPoint(0f, 100f, 41.41, -73.98),
        HistoricMapControlPoint(100f, 100f, 41.42, -73.88),
    )

    @Test
    fun affineFitRecoversKnownTransform() {
        val fit = GeoReferencer.fit(knownTransformPoints())
        val transform = requireNotNull(fit.transform)

        assertEquals(0.001, transform.a, 1e-9)
        assertEquals(0.0002, transform.b, 1e-9)
        assertEquals(-74.0, transform.c, 1e-9)
        assertEquals(0.0001, transform.d, 1e-9)
        assertEquals(-0.0009, transform.e, 1e-9)
        assertEquals(41.5, transform.f, 1e-9)
        assertEquals(GeoReferenceConfidence.GOOD, fit.confidence)
        assertTrue("rmse ${fit.rmseMeters} should be ~0", (fit.rmseMeters ?: 1.0) < 0.01)
    }

    @Test
    fun worldToImageInvertsImageToWorld() {
        val transform = requireNotNull(GeoReferencer.fit(knownTransformPoints()).transform)
        val (latitude, longitude) = transform.imageToWorld(37.5f, 62.5f)
        val (imageX, imageY) = requireNotNull(transform.worldToImage(latitude, longitude))

        assertEquals(37.5f, imageX, 0.001f)
        assertEquals(62.5f, imageY, 0.001f)
    }

    @Test
    fun twoPointFitIsExactButLowConfidence() {
        val fit = GeoReferencer.fit(
            listOf(
                HistoricMapControlPoint(0f, 0f, 41.0, -74.0),
                HistoricMapControlPoint(200f, 0f, 41.0, -73.8),
            ),
        )
        val transform = requireNotNull(fit.transform)

        val (lat1, lon1) = transform.imageToWorld(0f, 0f)
        assertEquals(41.0, lat1, 1e-9)
        assertEquals(-74.0, lon1, 1e-9)
        val (lat2, lon2) = transform.imageToWorld(200f, 0f)
        assertEquals(41.0, lat2, 1e-9)
        assertEquals(-73.8, lon2, 1e-9)
        assertEquals(GeoReferenceConfidence.LOW_CONFIDENCE, fit.confidence)
        assertTrue(fit.note.contains("third point"))
        assertEquals(0.0, fit.rmseMeters ?: 1.0, 0.001)
    }

    @Test
    fun singlePointCannotFit() {
        val fit = GeoReferencer.fit(listOf(HistoricMapControlPoint(0f, 0f, 41.0, -74.0)))

        assertNull(fit.transform)
        assertEquals(GeoReferenceConfidence.INSUFFICIENT_POINTS, fit.confidence)
    }

    @Test
    fun collinearPointsAreRejectedAsDegenerate() {
        val fit = GeoReferencer.fit(
            listOf(
                HistoricMapControlPoint(0f, 0f, 41.0, -74.0),
                HistoricMapControlPoint(50f, 50f, 41.001, -73.999),
                HistoricMapControlPoint(100f, 100f, 41.002, -73.998),
            ),
        )

        assertNull(fit.transform)
        assertEquals(GeoReferenceConfidence.INSUFFICIENT_POINTS, fit.confidence)
        assertTrue(fit.note.contains("collinear") || fit.note.contains("duplicate"))
    }

    @Test
    fun noisyControlPointsProduceMeterScaleResiduals() {
        // Same known transform plus ~1 m of jitter (about 0.00001 degrees).
        val jitter = listOf(0.0, 0.00001, -0.00001, 0.00002, -0.00002)
        val base = knownTransformPoints() + HistoricMapControlPoint(50f, 50f, 41.46, -73.94)
        val noisy = base.mapIndexed { index, point ->
            point.copy(
                latitude = point.latitude + jitter[index],
                longitude = point.longitude - jitter[index],
            )
        }

        val fit = GeoReferencer.fit(noisy)

        requireNotNull(fit.transform)
        val rmse = requireNotNull(fit.rmseMeters)
        assertTrue("rmse $rmse should be meter-scale", rmse in 0.01..GeoReferencer.GOOD_RMSE_METERS)
        assertEquals(GeoReferenceConfidence.GOOD, fit.confidence)
        assertTrue((fit.maxResidualMeters ?: 0.0) >= rmse - 1e-9)
    }

    @Test
    fun badlyMisplacedPointYieldsLowConfidence() {
        val points = knownTransformPoints().mapIndexed { index, point ->
            // Move one control point ~0.002 degrees (~170 m) away from the true transform.
            if (index == 3) point.copy(latitude = point.latitude + 0.002) else point
        }

        val fit = GeoReferencer.fit(points)

        requireNotNull(fit.transform)
        assertEquals(GeoReferenceConfidence.LOW_CONFIDENCE, fit.confidence)
        assertTrue((fit.rmseMeters ?: 0.0) >= GeoReferencer.FAIR_RMSE_METERS)
    }
}
