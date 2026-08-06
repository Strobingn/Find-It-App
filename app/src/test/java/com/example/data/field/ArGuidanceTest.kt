package com.example.data.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ArGuidanceTest {

    @Test
    fun reticleOffsetX_clampsAt90Degrees() {
        assertEquals(0f, ArGuidance.reticleOffsetX(0f), 1e-4f)
        assertEquals(1f, ArGuidance.reticleOffsetX(90f), 1e-4f)
        assertEquals(-1f, ArGuidance.reticleOffsetX(-90f), 1e-4f)
        assertEquals(1f, ArGuidance.reticleOffsetX(180f), 1e-4f)
        assertEquals(0.5f, ArGuidance.reticleOffsetX(45f), 1e-4f)
    }

    @Test
    fun reticleOffsetY_nearerIsLowerInFrame() {
        val far = ArGuidance.reticleOffsetY(200.0)
        val near = ArGuidance.reticleOffsetY(5.0)
        assertTrue(near > far)
    }

    @Test
    fun compute_arrivedWhenWithin15m() {
        val state = ArGuidance.compute(
            distanceMeters = 10.0,
            trueTargetBearing = 0f,
            magneticTargetBearing = 0f,
            headingDegrees = 0f,
        )
        assertTrue(state.isArrived)
        assertTrue(state.instruction.contains("Arrived", ignoreCase = true))
        assertTrue(state.honestyLine.contains("LiDAR", ignoreCase = true))
    }

    @Test
    fun compute_turnRightWhenHeadingWestOfTarget() {
        // Heading north (0), target east (90 magnetic) → turn right ~90
        val state = ArGuidance.compute(
            distanceMeters = 80.0,
            trueTargetBearing = 90f,
            magneticTargetBearing = 90f,
            headingDegrees = 0f,
        )
        assertFalse(state.isArrived)
        assertTrue(state.turnDegrees != null && state.turnDegrees!! > 80f)
        assertTrue(state.reticleOffsetX > 0.8f)
        assertTrue(state.instruction.contains("right", ignoreCase = true))
    }

    @Test
    fun compute_waitingForGpsWithoutFix() {
        val state = ArGuidance.compute(
            distanceMeters = null,
            trueTargetBearing = null,
            magneticTargetBearing = null,
            headingDegrees = 10f,
        )
        assertFalse(state.hasGps)
        assertTrue(state.instruction.contains("GPS", ignoreCase = true))
    }

    @Test
    fun compute_accuracyWarningWhenLoose() {
        val state = ArGuidance.compute(
            distanceMeters = 50.0,
            trueTargetBearing = 0f,
            magneticTargetBearing = 0f,
            headingDegrees = 0f,
            accuracyMeters = 30f,
        )
        assertTrue(state.accuracyWarning?.contains("30") == true)
    }

    @Test
    fun turnArrowUnit_aheadIsUp() {
        val (dx, dy) = ArGuidance.turnArrowUnit(0f)
        assertTrue(abs(dx) < 1e-4f)
        assertTrue(dy < -0.99f)
    }
}
