package com.example.analysis.ml

import com.example.analysis.TerrainDerivedLayer
import com.example.analysis.TerrainDerivedLayers
import com.example.analysis.TerrainFeatureCandidate
import com.example.analysis.TerrainFeatureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MlRankerTest {
    private fun sampleCandidate() = TerrainFeatureCandidate(
        id = "c1",
        type = TerrainFeatureType.CELLAR_HOLE,
        xPercent = 50f,
        yPercent = 50f,
        score = 0.8f,
        radiusMeters = 6f,
        evidence = listOf("depression", "rim"),
    )

    @Test
    fun featureExtractionSamplesLayersAtCandidateCell() {
        val width = 10
        val height = 10
        val slope = FloatArray(width * height) { 0f }
        slope[5 * width + 5] = 12.5f
        val layers = TerrainDerivedLayers(
            width = width,
            height = height,
            cellSizeMeters = 1f,
            values = mapOf(TerrainDerivedLayer.SLOPE to slope),
        )

        val vector = CandidateFeatures.extract(sampleCandidate(), layers)

        assertEquals(CandidateFeatures.SCHEMA_VERSION, vector.schemaVersion)
        assertEquals(CandidateFeatures.FEATURE_NAMES, vector.featureNames)
        assertEquals(0.8f, vector.values[0], 0.0001f)
        assertEquals(6f, vector.values[1], 0.0001f)
        assertEquals(2f, vector.values[2], 0.0001f)
        assertEquals(12.5f, vector.values[3], 0.0001f)
        // Layers not present in the map contribute zero rather than failing.
        assertEquals(0f, vector.values[4], 0.0001f)
    }

    @Test
    fun rankerCodecRoundTripsAndRejectsMalformed() {
        val ranker = ExplainableRanker(
            modelVersion = "v1-test",
            featureNames = listOf("a", "b"),
            weights = floatArrayOf(0.5f, -1.25f),
            bias = 0.1f,
            featureMeans = floatArrayOf(0.3f, 4f),
            featureStds = floatArrayOf(0.2f, 2f),
            calibrationA = 1.1f,
            calibrationB = -0.2f,
        )

        assertEquals(ranker, ExplainableRanker.fromStorage(ranker.toStorage()))
        assertNull(ExplainableRanker.fromStorage("garbage"))
        assertNull(ExplainableRanker.fromStorage("FINDIT_RANKER_V1\nversion\tx"))
    }

    @Test
    fun contributionsSumWithBiasToRawScore() {
        val ranker = ExplainableRanker(
            modelVersion = "v1",
            featureNames = listOf("a", "b", "c"),
            weights = floatArrayOf(1f, -2f, 0.5f),
            bias = 0.25f,
            featureMeans = floatArrayOf(0f, 0f, 0f),
            featureStds = floatArrayOf(1f, 1f, 1f),
        )
        val values = floatArrayOf(0.5f, 0.25f, -1f)

        val contributionSum = ranker.contributions(values).sumOf { it.contribution.toDouble() }

        assertEquals(
            (ranker.rawScore(values) - ranker.bias).toDouble(),
            contributionSum,
            0.0001,
        )
        // Sorted by magnitude: |(-2)*0.25| = 0.5 ties |1*0.5| = 0.5, then |0.5*-1| = 0.5 — all tie,
        // so just verify probabilities stay in range.
        assertTrue(ranker.probability(values) in 0f..1f)
    }

    @Test
    fun trainerSeparatesProductiveFromRejected() {
        val featureNames = listOf("rule_score", "radius_meters")
        val examples = ArrayList<RankerTrainingExample>()
        for (index in 0 until 20) {
            examples += RankerTrainingExample(
                features = floatArrayOf(0.7f + index * 0.01f, 5f + index * 0.1f),
                productive = true,
            )
            examples += RankerTrainingExample(
                features = floatArrayOf(0.1f + index * 0.01f, 1f + index * 0.1f),
                productive = false,
            )
        }

        val result = RankerTrainer.train(examples, modelVersion = "v2", featureNames = featureNames)

        assertEquals(1.0f, result.accuracy, 0.0001f)
        assertTrue(result.finalLogLoss < 0.5f)
        val positiveProbability = result.ranker.probability(floatArrayOf(0.85f, 6f))
        val negativeProbability = result.ranker.probability(floatArrayOf(0.15f, 1f))
        assertTrue(positiveProbability > 0.5f)
        assertTrue(negativeProbability < 0.5f)
        assertTrue(positiveProbability > negativeProbability)
        // The trained model round-trips through its codec with identical behavior.
        val restored = ExplainableRanker.fromStorage(result.ranker.toStorage())
        assertNotNull(restored)
        assertEquals(
            result.ranker.probability(floatArrayOf(0.5f, 3f)),
            restored!!.probability(floatArrayOf(0.5f, 3f)),
            0.0001f,
        )
    }

    @Test
    fun trainedRankerExplainsItsScores() {
        val featureNames = listOf("rule_score", "radius_meters")
        val examples = listOf(
            RankerTrainingExample(floatArrayOf(0.9f, 6f), productive = true),
            RankerTrainingExample(floatArrayOf(0.8f, 5f), productive = true),
            RankerTrainingExample(floatArrayOf(0.1f, 1f), productive = false),
            RankerTrainingExample(floatArrayOf(0.2f, 2f), productive = false),
        )
        val ranker = RankerTrainer.train(examples, "v3", featureNames).ranker

        val contributions = ranker.contributions(floatArrayOf(0.9f, 6f))

        assertEquals(featureNames.toSet(), contributions.map { it.featureName }.toSet())
        assertTrue(contributions.zipWithNext().all { (first, second) ->
            kotlin.math.abs(first.contribution) >= kotlin.math.abs(second.contribution)
        })
    }
}
