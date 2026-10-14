package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unclassified lowest-return grids keep a roof or a tree wherever that cell never hit the ground.
 * Those cells are what read as blocky blobs. The morphological clear must drop them and leave pits.
 */
class SmrfGroundTest {
    @Test
    fun buildingAndTreeAreClearedPitAndSlopeStay() {
        val width = 40
        val height = 40
        val elevations = FloatArray(width * height) { 10f }
        val measured = BooleanArray(width * height) { true }
        for (y in 16..21) {
            for (x in 16..21) {
                elevations[y * width + x] = 22f
            }
        }
        for (y in 4..6) {
            for (x in 4..6) {
                elevations[y * width + x] = 8f
            }
        }
        elevations[30 * width + 8] = 28f

        val removed = rejectRaisedObjects(elevations, measured, width, height, cellSizeMeters = 1f)

        assertEquals(36 + 1, removed)
        assertFalse(measured[18 * width + 18])
        assertTrue(elevations[18 * width + 18].isNaN())
        assertFalse(measured[30 * width + 8])
        assertTrue(measured[5 * width + 5])
        assertEquals(8f, elevations[5 * width + 5])
        assertTrue(measured[5 * width + 6])

        val filled = interpolateBareEarth(
            measuredElevations = elevations,
            measuredMask = measured,
            width = width,
            height = height,
            cellSizeMeters = 1f,
            totalPoints = measured.count { it }.toLong(),
            groundPoints = measured.count { it },
        )
        val roof = filled.elevations[18 * width + 18]
        assertTrue("roof should be rebuilt near the ground, was $roof", roof in 9f..12f)
        assertEquals(8f, filled.elevations[5 * width + 5])
        assertFalse(filled.interpolatedData[5 * width + 5])
    }

    @Test
    fun gentleHillIsNotStripped() {
        val width = 48
        val height = 24
        val elevations = FloatArray(width * height) { index -> 100f + (index % width) * 0.2f }
        val measured = BooleanArray(elevations.size) { true }
        rejectRaisedObjects(elevations, measured, width, height, cellSizeMeters = 1f)
        val mid = 12 * width + 24
        assertTrue(measured[mid])
        assertEquals(100f + 24 * 0.2f, elevations[mid], 0.01f)
    }
}
