package com.example.data.historicmap

import com.example.analysis.TerrainFeatureCandidate
import com.example.analysis.TerrainFeatureType
import com.example.data.field.BoundaryVertex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricMapFindMatcherTest {
    @Test
    fun nearbyStructureRaisesConfidence() {
        val candidate = TerrainFeatureCandidate(
            id = "c1",
            type = TerrainFeatureType.FOUNDATION,
            xPercent = 50f,
            yPercent = 50f,
            score = 0.70f,
            radiusMeters = 8f,
            evidence = listOf("platform"),
        )
        val map = HistoricMapRecord(
            id = "usgs-1",
            source = HistoricMapSource.USGS_HISTORICAL,
            title = "sheet",
            editionYear = 1903,
            attribution = "USGS",
            remoteUrl = "https://example.test/a.tif",
            localFile = null,
            minLatitude = 43.0,
            maxLatitude = 43.2,
            minLongitude = -75.5,
            maxLongitude = -75.2,
            georeferenced = true,
            residualRmseMeters = 8.0,
        )
        val feature = HistoricMapFeature(
            id = "f1",
            mapId = "usgs-1",
            type = MapFeatureType.STRUCTURE,
            points = listOf(BoundaryVertex(43.10, -75.35)),
            confidence = 0.8f,
            note = "building",
            createdAtMillis = 0L,
        )
        val (updated, hits) = HistoricMapFindMatcher.match(
            candidate = candidate,
            latitude = 43.1002,
            longitude = -75.3501,
            maps = listOf(map),
            features = listOf(feature),
        )
        assertTrue(hits.isNotEmpty())
        assertTrue(updated.score > candidate.score)
        assertTrue(updated.mapBoost > 0f)
    }

    @Test
    fun poorlyAlignedMapCannotConfirmAlone() {
        val candidate = TerrainFeatureCandidate(
            id = "c2",
            type = TerrainFeatureType.FOUNDATION,
            xPercent = 50f,
            yPercent = 50f,
            score = 0.40f,
            radiusMeters = 8f,
            evidence = emptyList(),
        )
        val map = HistoricMapRecord(
            id = "loc-1",
            source = HistoricMapSource.LIBRARY_OF_CONGRESS,
            title = "scan",
            editionYear = 1888,
            attribution = "LOC",
            remoteUrl = "https://example.test/b.jpg",
            localFile = null,
            minLatitude = 43.0,
            maxLatitude = 43.2,
            minLongitude = -75.5,
            maxLongitude = -75.2,
            georeferenced = false,
            residualRmseMeters = 90.0,
            matchRadiusMeters = 140.0,
        )
        val feature = HistoricMapFeature(
            id = "f2",
            mapId = "loc-1",
            type = MapFeatureType.STRUCTURE,
            points = listOf(BoundaryVertex(43.10, -75.35)),
            confidence = 0.4f,
            note = "",
            createdAtMillis = 0L,
        )
        val (updated, _) = HistoricMapFindMatcher.match(
            candidate, 43.10, -75.35, listOf(map), listOf(feature),
        )
        assertTrue(updated.mapBoost <= 0.08f + 1e-4f)
        assertTrue(updated.score < 0.55f)
        assertEquals(false, updated.autoShown)
    }

    @Test
    fun applyAllBoostsCandidatesInsideTheTile() {
        val candidate = TerrainFeatureCandidate(
            id = "c3",
            type = TerrainFeatureType.FOUNDATION,
            xPercent = 50f,
            yPercent = 50f,
            score = 0.70f,
            radiusMeters = 8f,
            evidence = listOf("platform"),
            visualizationHits = 3,
        )
        val map = HistoricMapRecord(
            id = "usgs-1",
            source = HistoricMapSource.USGS_HISTORICAL,
            title = "sheet",
            editionYear = 1903,
            attribution = "USGS",
            remoteUrl = "https://example.test/a.tif",
            localFile = null,
            minLatitude = 43.0,
            maxLatitude = 43.2,
            minLongitude = -75.5,
            maxLongitude = -75.2,
            georeferenced = true,
            residualRmseMeters = 8.0,
        )
        val feature = HistoricMapFeature(
            id = "f1",
            mapId = "usgs-1",
            type = MapFeatureType.STRUCTURE,
            points = listOf(BoundaryVertex(43.10, -75.35)),
            confidence = 0.8f,
            note = "building",
            createdAtMillis = 0L,
        )
        val updated = HistoricMapFindMatcher.applyAll(
            candidates = listOf(candidate),
            minLatitude = 43.0,
            maxLatitude = 43.2,
            minLongitude = -75.5,
            maxLongitude = -75.2,
            maps = listOf(map),
            features = listOf(feature),
        )
        assertTrue(updated.single().score > candidate.score)
        assertTrue(updated.single().mapBoost > 0f)
    }
}
