package com.example.ui.components

import com.example.data.field.FieldWaypoint
import com.example.data.survey.SurveyCoordinate
import com.example.data.survey.SurveyFeature
import com.example.data.survey.SurveyGeometryType
import com.mapbox.geojson.Polygon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapboxOverlayGeometryTest {
    private val metersPerDegree = 111_320.0

    @Test
    fun unrotatedCornersAreTopLeftClockwiseAroundCenter() {
        val corners = MapboxOverlayGeometry.overlayCorners(
            centerLat = 0.0,
            centerLon = 0.0,
            widthMeters = 200.0,
            heightMeters = 100.0,
            bearingDegrees = 0.0,
        )
        val halfLon = 100.0 / metersPerDegree
        val halfLat = 50.0 / metersPerDegree
        assertCorner(listOf(-halfLon, halfLat), corners[0]) // top-left
        assertCorner(listOf(halfLon, halfLat), corners[1]) // top-right
        assertCorner(listOf(halfLon, -halfLat), corners[2]) // bottom-right
        assertCorner(listOf(-halfLon, -halfLat), corners[3]) // bottom-left
    }

    @Test
    fun ninetyDegreeBearingTurnsTopEdgeToFaceEast() {
        val corners = MapboxOverlayGeometry.overlayCorners(
            centerLat = 0.0,
            centerLon = 0.0,
            widthMeters = 200.0,
            heightMeters = 100.0,
            bearingDegrees = 90.0,
        )
        // Rotated clockwise: the image's top edge now lies on the east side.
        val halfLon = 50.0 / metersPerDegree
        val halfLat = 100.0 / metersPerDegree
        assertCorner(listOf(halfLon, halfLat), corners[0])
        assertCorner(listOf(halfLon, -halfLat), corners[1])
        assertCorner(listOf(-halfLon, -halfLat), corners[2])
        assertCorner(listOf(-halfLon, halfLat), corners[3])
    }

    @Test
    fun longitudeSpanWidensAwayFromEquator() {
        val corners = MapboxOverlayGeometry.overlayCorners(43.0, -74.0, 1_000.0, 1_000.0, 0.0)
        val lonSpan = corners[1][0] - corners[0][0]
        val latSpan = corners[0][1] - corners[3][1]
        assertTrue("longitude span should exceed latitude span at 43°N", lonSpan > latSpan)
    }

    @Test
    fun surveyPolygonsAreClosedAndShortGeometriesDropped() {
        val features = listOf(
            SurveyFeature(
                id = "poly",
                name = "Field",
                geometryType = SurveyGeometryType.POLYGON,
                coordinates = listOf(
                    SurveyCoordinate(0.0, 0.0),
                    SurveyCoordinate(0.0, 1.0),
                    SurveyCoordinate(1.0, 1.0),
                ),
            ),
            SurveyFeature("line", null, SurveyGeometryType.LINE, listOf(SurveyCoordinate(0.0, 0.0))),
        )
        val polygons = MapboxOverlayGeometry.surveyPolygons(features).features().orEmpty()
        assertEquals(1, polygons.size)
        val ring = (polygons.single().geometry() as Polygon).outer()!!.coordinates()
        assertEquals(4, ring.size)
        assertEquals(ring.first(), ring.last())
        assertTrue(MapboxOverlayGeometry.surveyLines(features).features().orEmpty().isEmpty())
    }

    @Test
    fun routeNeedsTwoStopsForALine() {
        val one = listOf(FieldWaypoint("a", 1.0, 2.0, "A"))
        assertTrue(MapboxOverlayGeometry.routeLine(one).features().orEmpty().isEmpty())
        assertEquals(1, MapboxOverlayGeometry.routeStops(one).features().orEmpty().size)
        val two = one + FieldWaypoint("b", 1.1, 2.1, "B")
        assertEquals(1, MapboxOverlayGeometry.routeLine(two).features().orEmpty().size)
    }

    private fun assertCorner(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected[0], actual[0], 1e-9)
        assertEquals(expected[1], actual[1], 1e-9)
    }
}
