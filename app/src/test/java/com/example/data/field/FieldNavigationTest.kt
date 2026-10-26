package com.example.data.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldNavigationTest {
    @Test
    fun northboundTargetHasNorthBearingAndAboutOneKilometerDistance() {
        val solution = FieldNavigation.solve(
            currentLatitude = 42.0,
            currentLongitude = -74.0,
            targetLatitude = 42.009,
            targetLongitude = -74.0,
            headingDegrees = 0f,
        )

        assertEquals(0f, solution.targetBearingDegrees, 0.1f)
        assertTrue(solution.distanceMeters in 950.0..1_050.0)
        assertEquals(0f, requireNotNull(solution.turnDegrees), 0.1f)
    }

    @Test
    fun distanceMetersHandlesExtremeLatitudeRoundoff() {
        val distance = FieldNavigation.distanceMeters(-82.0, -180.0, 82.0, 0.0)

        assertTrue(distance.isFinite())
        assertTrue(distance > 0.0)
        assertTrue(distance < 20_000_000.0)
    }

    @Test
    fun turnInstructionWrapsAcrossNorth() {
        assertEquals(20f, FieldNavigation.signedTurnDegrees(350f, 10f), 0.1f)
        assertEquals(-20f, FieldNavigation.signedTurnDegrees(10f, 350f), 0.1f)
        assertEquals("Turn 20° right", FieldNavigation.turnInstruction(20f))
        assertEquals("Turn 20° left", FieldNavigation.turnInstruction(-20f))
    }
}
