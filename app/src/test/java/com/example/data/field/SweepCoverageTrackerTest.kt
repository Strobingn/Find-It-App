package com.example.data.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepCoverageTrackerTest {

    private fun point(latitude: Double, longitude: Double, at: Long = 0L) =
        BreadcrumbPoint(latitude, longitude, accuracyMeters = 5f, recordedAtMillis = at)

    private fun track(vararg points: BreadcrumbPoint) = BreadcrumbTrack(
        id = "t1",
        terrainKey = "terrain",
        displayName = "Track",
        points = points.toList(),
        isRecording = false,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    // Extent roughly 111 m (N-S) x 84 m (E-W) at 41°N.
    private val minLat = 41.0
    private val maxLat = 41.001
    private val minLon = -74.001
    private val maxLon = -74.0

    @Test
    fun straightTrackSweepsACorridor() {
        val coverage = SweepCoverageTracker.build(
            tracks = listOf(track(point(41.0005, -74.0009), point(41.0005, -74.0001, 10_000L))),
            minLatitude = minLat,
            maxLatitude = maxLat,
            minLongitude = minLon,
            maxLongitude = maxLon,
        )
        // Corridor is ~67 m long and ~2 m wide over ~4 m² cells → a few dozen cells.
        assertTrue(coverage.coveredCells in 15..80)
        assertTrue(coverage.coverageRatio > 0f)
        assertTrue(coverage.coverageRatio < 0.5f)
        assertEquals(
            coverage.coveredCells * coverage.cellWidthMeters * coverage.cellHeightMeters,
            coverage.coveredAreaSquareMeters,
            1e-3f,
        )
        // A cell on the track line is covered; a corner far away is not.
        val midRow = (coverage.height / 2) * coverage.width
        var anyMidRowCovered = false
        for (x in 0 until coverage.width) anyMidRowCovered = anyMidRowCovered || coverage.covered[midRow + x]
        assertTrue(anyMidRowCovered)
        assertTrue(!coverage.covered[0])
    }

    @Test
    fun emptyTracksCoverNothing() {
        val coverage = SweepCoverageTracker.build(
            tracks = emptyList(),
            minLatitude = minLat,
            maxLatitude = maxLat,
            minLongitude = minLon,
            maxLongitude = maxLon,
        )
        assertEquals(0, coverage.coveredCells)
        assertEquals(0f, coverage.coverageRatio, 1e-6f)
    }

    @Test
    fun singlePointInsideStampsItsCellButOutsideDoesNot() {
        val inside = SweepCoverageTracker.build(
            tracks = listOf(track(point(41.0005, -74.0005))),
            minLatitude = minLat,
            maxLatitude = maxLat,
            minLongitude = minLon,
            maxLongitude = maxLon,
        )
        assertTrue(inside.coveredCells >= 1)

        val outside = SweepCoverageTracker.build(
            tracks = listOf(track(point(42.0, -75.0))),
            minLatitude = minLat,
            maxLatitude = maxLat,
            minLongitude = minLon,
            maxLongitude = maxLon,
        )
        assertEquals(0, outside.coveredCells)
    }

    @Test
    fun widerSweepCoversStrictlyMore() {
        val tracks = listOf(track(point(41.0005, -74.0009), point(41.0005, -74.0001, 10_000L)))
        val narrow = SweepCoverageTracker.build(
            tracks, minLat, maxLat, minLon, maxLon, sweepWidthMeters = 1f,
        )
        val wide = SweepCoverageTracker.build(
            tracks, minLat, maxLat, minLon, maxLon, sweepWidthMeters = 6f,
        )
        assertTrue(wide.coveredCells > narrow.coveredCells)
    }

    @Test
    fun segmentDistanceClampsToEndpoints() {
        // Perpendicular point above the segment middle.
        assertEquals(
            5.0,
            SweepCoverageTracker.distanceToSegmentMeters(5.0, 5.0, 0.0, 0.0, 10.0, 0.0),
            1e-9,
        )
        // Beyond the far end → distance to the endpoint, not the line.
        assertEquals(
            4.0,
            SweepCoverageTracker.distanceToSegmentMeters(14.0, 0.0, 0.0, 0.0, 10.0, 0.0),
            1e-9,
        )
        // Degenerate segment behaves like a point.
        assertEquals(
            3.0,
            SweepCoverageTracker.distanceToSegmentMeters(3.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            1e-9,
        )
    }
}
