package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricFeatureClassifierTest {
    @Test
    fun multiVisualizationAndHighScoreAutoShows() {
        val layers = layers(width = 8, height = 8) { index ->
            if (index == 4 * 8 + 4) 0.4f else 0.01f
        }
        val candidate = TerrainFeatureCandidate(
            id = "f1",
            type = TerrainFeatureType.FOUNDATION,
            xPercent = 50f,
            yPercent = 50f,
            score = 0.92f,
            radiusMeters = 7f,
            evidence = listOf("rectilinear"),
        )
        val refined = HistoricFeatureClassifier.refine(listOf(candidate), layers)
        assertEquals(1, refined.size)
        assertEquals(FeatureDisplayBucket.AUTO, refined.first().displayBucket)
        assertTrue(refined.first().visualizationHits >= 2)
    }

    @Test
    fun modernOsmRoadIsRejected() {
        val layers = layers(8, 8) { 0.3f }
        val candidate = TerrainFeatureCandidate(
            id = "road",
            type = TerrainFeatureType.ROAD_TRAIL,
            xPercent = 20f,
            yPercent = 80f,
            score = 0.95f,
            radiusMeters = 5f,
            evidence = listOf("linear"),
        )
        val modern = OsmModernContext(listOf(OsmModernFeature(20f, 80f, 12f, OsmModernFeature.Kind.ROAD)))
        val refined = HistoricFeatureClassifier.refine(listOf(candidate), layers, modern)
        assertTrue(refined.none { it.id == "road" })
    }

    @Test
    fun burrowScaleMoundIsRejected() {
        val width = 16
        val height = 16
        val relief = FloatArray(width * height)
        relief[8 * width + 8] = 0.2f
        val layers = TerrainDerivedLayers(
            width, height, 0.5f,
            mapOf(
                TerrainDerivedLayer.LOCAL_RELIEF to relief,
                TerrainDerivedLayer.SKY_VIEW_FACTOR to FloatArray(width * height) { 0.9f },
                TerrainDerivedLayer.SLOPE to FloatArray(width * height) { 4f },
                TerrainDerivedLayer.HILLSHADE_COMPARISON to FloatArray(width * height) { 0.2f },
                TerrainDerivedLayer.LINEARITY to FloatArray(width * height) { 0.2f },
                TerrainDerivedLayer.DEPRESSION_DEPTH to FloatArray(width * height) { 0.2f },
            ),
        )
        val candidate = TerrainFeatureCandidate(
            id = "mound",
            type = TerrainFeatureType.CELLAR_HOLE,
            xPercent = 50f,
            yPercent = 50f,
            score = 0.9f,
            radiusMeters = 3f,
            evidence = listOf("bowl"),
        )
        assertTrue(
            HistoricFeatureClassifier.looksLikeBurrowMound(width, height, 0.5f, 8, 8, relief),
        )
        val refined = HistoricFeatureClassifier.refine(listOf(candidate), layers)
        assertTrue(refined.none { it.id == "mound" })
    }

    @Test
    fun midScoreLandsOnMaybeList() {
        val layers = layers(8, 8) { 0.25f }
        val candidate = TerrainFeatureCandidate(
            id = "maybe",
            type = TerrainFeatureType.FOUNDATION,
            xPercent = 50f,
            yPercent = 50f,
            score = 0.72f,
            radiusMeters = 7f,
            evidence = listOf("edge"),
        )
        val refined = HistoricFeatureClassifier.refine(listOf(candidate), layers)
        assertEquals(FeatureDisplayBucket.MAYBE, refined.single().displayBucket)
        assertFalse(refined.single().autoShown)
    }

    private fun layers(width: Int, height: Int, fill: (Int) -> Float): TerrainDerivedLayers {
        val size = width * height
        return TerrainDerivedLayers(
            width, height, 1f,
            mapOf(
                TerrainDerivedLayer.LOCAL_RELIEF to FloatArray(size, fill),
                TerrainDerivedLayer.SKY_VIEW_FACTOR to FloatArray(size) { 0.7f },
                TerrainDerivedLayer.SLOPE to FloatArray(size) { 8f },
                TerrainDerivedLayer.HILLSHADE_COMPARISON to FloatArray(size, fill),
                TerrainDerivedLayer.LINEARITY to FloatArray(size, fill),
                TerrainDerivedLayer.DEPRESSION_DEPTH to FloatArray(size, fill),
            ),
        )
    }
}
