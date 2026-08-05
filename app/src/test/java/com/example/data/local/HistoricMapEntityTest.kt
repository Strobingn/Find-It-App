package com.example.data.local

import com.example.data.field.BoundaryVertex
import com.example.data.historicmap.GeoReferenceConfidence
import com.example.data.historicmap.GeoReferenceTransform
import com.example.data.historicmap.GeoReferencedMap
import com.example.data.historicmap.HistoricMapControlPoint
import com.example.data.historicmap.HistoricMapFeature
import com.example.data.historicmap.MapFeatureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricMapEntityTest {
    private fun sampleMap(): GeoReferencedMap = GeoReferencedMap(
        id = "map-1",
        terrainKey = "terrain-1",
        displayName = "1867 Beers atlas sheet",
        imageUri = "content://maps/beers1867",
        sourceAttribution = "Beers, F.W. 1867 county atlas",
        controlPoints = listOf(
            HistoricMapControlPoint(0f, 0f, 41.5, -74.0),
            HistoricMapControlPoint(100f, 0f, 41.51, -73.9),
            HistoricMapControlPoint(0f, 100f, 41.41, -73.98),
        ),
        transform = GeoReferenceTransform(0.001, 0.0002, -74.0, 0.0001, -0.0009, 41.5),
        rmseMeters = 4.2,
        maxResidualMeters = 7.5,
        confidence = GeoReferenceConfidence.GOOD,
        createdAtMillis = 1_000L,
        updatedAtMillis = 2_000L,
    )

    @Test
    fun geoReferencedMapRoundTripsThroughEntity() {
        val map = sampleMap()

        assertEquals(map, map.toEntity().toDomain())
    }

    @Test
    fun mapWithoutTransformRoundTrips() {
        val map = sampleMap().copy(
            transform = null,
            rmseMeters = null,
            maxResidualMeters = null,
            confidence = GeoReferenceConfidence.INSUFFICIENT_POINTS,
        )

        val restored = map.toEntity().toDomain()

        assertEquals(map, restored)
        assertTrue(!restored.isReliable)
    }

    @Test
    fun unknownConfidenceNameFallsBackToInsufficientPoints() {
        val entity = sampleMap().toEntity().copy(confidence = "FUTURE_CONFIDENCE")

        assertEquals(GeoReferenceConfidence.INSUFFICIENT_POINTS, entity.toDomain().confidence)
    }

    @Test
    fun transformCodecRoundTripsAndRejectsMalformed() {
        val transform = GeoReferenceTransform(0.001, 0.0002, -74.0, 0.0001, -0.0009, 41.5)

        assertEquals(transform, GeoReferenceTransform.fromStorage(transform.toStorage()))
        assertNull(GeoReferenceTransform.fromStorage("1,2,3"))
        assertNull(GeoReferenceTransform.fromStorage("1,2,3,4,5,NaN"))
    }

    @Test
    fun historicMapFeatureRoundTripsThroughEntity() {
        val feature = HistoricMapFeature(
            id = "feat-1",
            mapId = "map-1",
            type = MapFeatureType.WALL,
            points = listOf(
                BoundaryVertex(41.4, -74.0),
                BoundaryVertex(41.4, -73.99),
                BoundaryVertex(41.41, -73.99),
            ),
            confidence = 0.8f,
            note = "stone wall along ridge",
            createdAtMillis = 3_000L,
        )

        assertEquals(feature, feature.toEntity().toDomain())
    }

    @Test
    fun unknownFeatureTypeFallsBackToRoad() {
        val entity = HistoricMapFeatureEntity(
            id = "feat-2",
            mapId = "map-1",
            type = "FUTURE_TYPE",
            pointsText = "41.4,-74.0;41.4,-73.99",
            confidence = 0.5f,
            note = "",
            createdAtMillis = 1L,
        )

        assertEquals(MapFeatureType.ROAD, entity.toDomain().type)
        assertEquals(2, entity.toDomain().points.size)
    }
}
