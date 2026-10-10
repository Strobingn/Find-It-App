package com.example.data.historicmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoGeoreferencerTest {
    @Test
    fun residualWidensMatchRadiusAndCapsPoorAlignment() {
        val result = AutoGeoreferencer.georeference(
            listOf(
                AutoGeoreferencer.ControlPair(0f, 0f, 43.0, -75.0),
                AutoGeoreferencer.ControlPair(100f, 0f, 43.0, -74.9),
                AutoGeoreferencer.ControlPair(0f, 100f, 42.9, -75.0),
            ),
        )
        assertTrue(result.matchRadiusMeters >= AutoGeoreferencer.BASE_MATCH_RADIUS_METERS)
        val poor = AutoGeoreferencer.boostForMatch(
            residualRmseMeters = 80.0,
            georeferenced = false,
            distanceMeters = 10.0,
            matchRadiusMeters = 140.0,
        )
        val good = AutoGeoreferencer.boostForMatch(
            residualRmseMeters = 6.0,
            georeferenced = true,
            distanceMeters = 10.0,
            matchRadiusMeters = 50.0,
        )
        assertTrue(good > poor)
        assertTrue(poor < 0.18f)
        assertEquals(GeoReferenceConfidence.GOOD, result.fit.confidence)
    }
}
