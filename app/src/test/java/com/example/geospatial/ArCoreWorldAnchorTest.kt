package com.example.geospatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArCoreWorldAnchorTest {

    @Test
    fun localOffset_eastTargetHasPositiveEast() {
        // ~111 m north per 0.001 deg lat; ~85 m east per 0.001 deg lon at mid-lat approx
        val off = ArCoreWorldAnchor.localOffset(
            deviceLat = 42.0,
            deviceLon = -74.0,
            targetLat = 42.0,
            targetLon = -73.999,
        )
        assertTrue(off.east > 0.0)
        assertTrue(off.distanceMeters > 50.0)
    }

    @Test
    fun projectToScreen_aheadIsNearCenter() {
        val offset = ArCoreWorldAnchor.LocalOffsetMeters(
            east = 0.0,
            north = 40.0,
            up = 0.0,
            distanceMeters = 40.0,
            bearingTrueDegrees = 0f,
        )
        val proj = ArCoreWorldAnchor.projectToScreen(offset, headingDegrees = 0f)
        assertTrue(proj.inFront)
        assertTrue(kotlin.math.abs(proj.x) < 0.15f)
    }

    @Test
    fun projectToScreen_rightTurnIsPositiveX() {
        val offset = ArCoreWorldAnchor.LocalOffsetMeters(
            east = 30.0,
            north = 30.0,
            up = 0.0,
            distanceMeters = 42.0,
            bearingTrueDegrees = 45f,
        )
        val proj = ArCoreWorldAnchor.projectToScreen(offset, headingDegrees = 0f)
        assertTrue(proj.inFront)
        assertTrue(proj.x > 0.1f)
    }

    @Test
    fun compute_waitingForGps() {
        val state = ArCoreWorldAnchor.compute(
            deviceLat = null,
            deviceLon = null,
            targetLat = 42.0,
            targetLon = -74.0,
            headingDegrees = 10f,
            arCoreAvailable = true,
        )
        assertTrue(state.instruction.contains("GPS", ignoreCase = true))
        assertEquals(null, state.offset)
    }

    @Test
    fun compute_arrivedNearTarget() {
        val state = ArCoreWorldAnchor.compute(
            deviceLat = 42.0,
            deviceLon = -74.0,
            targetLat = 42.00005,
            targetLon = -74.0,
            headingDegrees = 0f,
            arCoreAvailable = false,
        )
        // ~5.5 m north
        assertTrue(state.offset!!.distanceMeters < 15.0)
        assertTrue(state.isArrived)
        assertFalse(state.modeLabel.contains("ARCore available") && state.modeLabel.contains("installed").not())
        assertTrue(state.honestyLine.contains("LiDAR", ignoreCase = true))
    }
}
