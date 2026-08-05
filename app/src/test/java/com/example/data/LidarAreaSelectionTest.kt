package com.example.data

import com.example.geospatial.GeoSpatialLibrary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LidarAreaSelectionTest {
    @Test
    fun radiusRejectsATileInItsBoundingBoxButOutsideTheCircle() {
        val selection = LidarAreaSelection.Radius(
            center = LidarAreaSelection.Point(latitude = 41.43, longitude = -74.04),
            radiusMeters = 1_000.0,
        )
        val cornerTile = GeoSpatialLibrary.GeographicBounds(
            minLat = 41.438,
            maxLat = 41.440,
            minLon = -74.032,
            maxLon = -74.030,
        )

        assertFalse(selection.intersects(cornerTile))
    }

    @Test
    fun radiusKeepsATileTouchedByTheCircle() {
        val selection = LidarAreaSelection.Radius(
            center = LidarAreaSelection.Point(latitude = 41.43, longitude = -74.04),
            radiusMeters = 1_000.0,
        )
        val nearbyTile = GeoSpatialLibrary.GeographicBounds(
            minLat = 41.429,
            maxLat = 41.431,
            minLon = -74.045,
            maxLon = -74.043,
        )

        assertTrue(selection.intersects(nearbyTile))
    }

    @Test
    fun polygonRejectsATileInsideItsEnvelopeButOutsideTheTriangle() {
        val selection = LidarAreaSelection.Polygon(
            listOf(
                LidarAreaSelection.Point(41.42, -74.05),
                LidarAreaSelection.Point(41.42, -74.03),
                LidarAreaSelection.Point(41.44, -74.04),
            ),
        )
        val outsideTriangle = GeoSpatialLibrary.GeographicBounds(41.438, 41.439, -74.050, -74.048)
        val crossingTriangle = GeoSpatialLibrary.GeographicBounds(41.421, 41.423, -74.041, -74.039)

        assertFalse(selection.intersects(outsideTriangle))
        assertTrue(selection.intersects(crossingTriangle))
    }

    @Test
    fun polygonProjectDescriptionRetainsEachSelectedVertex() {
        val selection = LidarAreaSelection.Polygon(
            listOf(
                LidarAreaSelection.Point(41.42, -74.05),
                LidarAreaSelection.Point(41.42, -74.03),
                LidarAreaSelection.Point(41.44, -74.04),
            ),
        )

        assertTrue(selection.projectAreaDescription().contains("41.44, -74.04"))
    }

    @Test
    fun selfCrossingPolygonIsRejectedBeforeItCanResolveTiles() {
        val invalid = runCatching {
            LidarAreaSelection.Polygon(
                listOf(
                    LidarAreaSelection.Point(41.42, -74.05),
                    LidarAreaSelection.Point(41.44, -74.03),
                    LidarAreaSelection.Point(41.42, -74.03),
                    LidarAreaSelection.Point(41.44, -74.05),
                ),
            )
        }

        assertTrue(invalid.isFailure)
    }
}
