package com.example.data.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyBoundaryTest {
    private fun squareBoundary(): SurveyBoundary = SurveyBoundary(
        id = "b1",
        terrainKey = "terrain-1",
        displayName = "North field",
        vertices = listOf(
            BoundaryVertex(0.0, 0.0),
            BoundaryVertex(0.0, 4.0),
            BoundaryVertex(4.0, 4.0),
            BoundaryVertex(4.0, 0.0),
        ),
        createdAtMillis = 1_000L,
    )

    @Test
    fun containsClassifiesInsideAndOutsidePoints() {
        val boundary = squareBoundary()

        assertTrue(boundary.contains(2.0, 2.0))
        assertTrue(boundary.contains(0.5, 0.5))
        assertFalse(boundary.contains(5.0, 2.0))
        assertFalse(boundary.contains(-1.0, 2.0))
        assertFalse(boundary.contains(2.0, 5.0))
        assertFalse(boundary.contains(2.0, -5.0))
    }

    @Test
    fun degenerateBoundaryContainsNothing() {
        val boundary = squareBoundary().copy(
            vertices = listOf(BoundaryVertex(0.0, 0.0), BoundaryVertex(1.0, 1.0)),
        )

        assertFalse(boundary.contains(0.5, 0.5))
    }

    @Test
    fun triangleExcludesPointsBeyondHypotenuse() {
        val triangle = squareBoundary().copy(
            vertices = listOf(
                BoundaryVertex(0.0, 0.0),
                BoundaryVertex(0.0, 4.0),
                BoundaryVertex(4.0, 0.0),
            ),
        )

        assertTrue(triangle.contains(0.5, 0.5))
        assertTrue(triangle.contains(1.0, 1.0))
        assertFalse(triangle.contains(3.5, 3.5))
    }

    @Test
    fun vertexCodecRoundTrips() {
        val boundary = squareBoundary()

        val restored = squareBoundary().copy(
            vertices = boundaryVerticesFromStorage(boundary.verticesToStorage()),
        )

        assertEquals(boundary.vertices, restored.vertices)
    }

    @Test
    fun vertexCodecSkipsMalformedAndOutOfRangeEntries() {
        val restored = boundaryVerticesFromStorage(
            "1.0,2.0;garbage;95.0,2.0;3.0,4.0;1.0;181.0,5.0",
        )

        assertEquals(
            listOf(BoundaryVertex(1.0, 2.0), BoundaryVertex(3.0, 4.0)),
            restored,
        )
    }
}
