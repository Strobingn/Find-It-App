package com.example.analysis

import java.util.EnumMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetalDetectingTargetRefinerTest {
    @Test
    fun positiveCalibrationBiasLowersEffectiveThreshold() {
        val baseline = MetalDetectingTargetRefiner.calibratedThreshold(0.66f, 0f)
        val calibrated = MetalDetectingTargetRefiner.calibratedThreshold(0.66f, 0.12f)
        assertTrue("A type the user keeps confirming should need a lower score to surface", calibrated < baseline)
        assertEquals(0.54f, calibrated, 1e-6f)
    }

    @Test
    fun negativeCalibrationBiasRaisesEffectiveThreshold() {
        val baseline = MetalDetectingTargetRefiner.calibratedThreshold(0.66f, 0f)
        val calibrated = MetalDetectingTargetRefiner.calibratedThreshold(0.66f, -0.12f)
        assertTrue("A type the user keeps rejecting should need a higher score to surface", calibrated > baseline)
        assertEquals(0.78f, calibrated, 1e-6f)
    }

    @Test
    fun calibratedThresholdIsClampedToASaneRange() {
        assertEquals(0.95f, MetalDetectingTargetRefiner.calibratedThreshold(0.66f, -1f), 1e-6f)
        assertEquals(0.3f, MetalDetectingTargetRefiner.calibratedThreshold(0.66f, 1f), 1e-6f)
    }

    @Test
    fun historicProfileFindsStructuredOccupationAndTravelFeatures() {
        val targets = MetalDetectingTargetRefiner.refine(structuredHistoricResult())

        assertFalse("Structured historic terrain should produce ranked targets", targets.isEmpty())
        assertTrue("All target scores must be finite and normalized", targets.all { it.score.isFinite() && it.score in 0f..1f })
        assertTrue("All target coordinates must remain inside the raster", targets.all { it.xPercent in 0f..100f && it.yPercent in 0f..100f })
        assertTrue("Targets must be returned in descending score order", targets.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun rankedTargetsCarryStructuredLayerEvidence() {
        val targets = MetalDetectingTargetRefiner.refine(structuredHistoricResult())

        assertFalse(targets.isEmpty())
        targets.forEach { target ->
            assertEquals("Every target reports all nine analysis layers", 9, target.layerEvidence.size)
            target.layerEvidence.forEach { layer ->
                assertTrue("Measurement is a percentage", layer.measurement.endsWith("%"))
            }
        }
        // Canopy roughness is inverted: heavy canopy argues against confidence. Keep
        // clear of rounding edges and pin the mapping at both ends of the scale.
        targets.forEach { target ->
            val canopy = target.layerEvidence.first { it.layer == "Canopy roughness" }
            val roughness = canopy.measurement.removeSuffix("%").toInt()
            if (roughness <= 30) assertEquals(LayerVerdict.SUPPORTS, canopy.verdict)
            if (roughness >= 70) assertEquals(LayerVerdict.DISAGREES, canopy.verdict)
        }
    }

    private fun structuredHistoricResult(): TerrainIntelligenceResult {
        val width = 64
        val height = 64
        val size = width * height
        val slope = FloatArray(size) { 0.08f }
        val curvature = FloatArray(size)
        val relief = FloatArray(size)
        val hillshade = FloatArray(size)
        val positiveOpen = FloatArray(size) { 0.82f }
        val negativeOpen = FloatArray(size) { 0.82f }
        val skyView = FloatArray(size) { 0.85f }
        val depression = FloatArray(size)
        val rugged = FloatArray(size) { 0.08f }
        val linearity = FloatArray(size)
        val stream = FloatArray(size)

        fun index(x: Int, y: Int) = y * width + x

        // Rectangular building platform with a persistent two-cell perimeter.
        for (y in 12..27) {
            for (x in 9..29) {
                slope[index(x, y)] = 0.01f
                rugged[index(x, y)] = 0.01f
                val onPerimeter = x <= 10 || x >= 28 || y <= 13 || y >= 26
                if (onPerimeter) {
                    linearity[index(x, y)] = 1f
                    curvature[index(x, y)] = if ((x + y) % 2 == 0) 1f else -1f
                    hillshade[index(x, y)] = 1f
                    relief[index(x, y)] = 0.55f
                }
            }
        }

        // Continuous low-gradient wagon-road corridor.
        for (x in 4..59) {
            for (y in 37..41) {
                linearity[index(x, y)] = 1f
                slope[index(x, y)] = 0.005f
                rugged[index(x, y)] = 0.005f
                relief[index(x, y)] = if (y in 39..40) -0.35f else 0.25f
                hillshade[index(x, y)] = 1f
            }
        }

        // Deep compact cellar hole with a broad raised rim.
        for (y in 43..53) {
            for (x in 12..22) {
                val dx = x - 17
                val dy = y - 48
                val distanceSquared = dx * dx + dy * dy
                when {
                    distanceSquared <= 8 -> {
                        depression[index(x, y)] = 1f
                        curvature[index(x, y)] = 1f
                        relief[index(x, y)] = -1f
                        positiveOpen[index(x, y)] = 0.15f
                    }
                    distanceSquared <= 24 -> {
                        relief[index(x, y)] = 1f
                        linearity[index(x, y)] = 0.75f
                        hillshade[index(x, y)] = 0.9f
                    }
                }
            }
        }

        // Shallower irregular refuse/privy-style pit near occupation evidence.
        for (y in 24..32) {
            for (x in 28..36) {
                val dx = x - 32
                val dy = y - 28
                val distanceSquared = dx * dx + dy * dy
                when {
                    distanceSquared <= 8 -> {
                        depression[index(x, y)] = 0.55f
                        curvature[index(x, y)] = 0.65f
                        relief[index(x, y)] = -0.5f
                        rugged[index(x, y)] = if ((x + y) % 2 == 0) 0.75f else 0.45f
                    }
                    distanceSquared <= 20 -> {
                        relief[index(x, y)] = 0.45f
                        linearity[index(x, y)] = 0.45f
                        hillshade[index(x, y)] = 0.6f
                    }
                }
            }
        }

        val values = EnumMap<TerrainDerivedLayer, FloatArray>(TerrainDerivedLayer::class.java).apply {
            put(TerrainDerivedLayer.SLOPE, slope)
            put(TerrainDerivedLayer.ASPECT, FloatArray(size))
            put(TerrainDerivedLayer.CURVATURE, curvature)
            put(TerrainDerivedLayer.LOCAL_RELIEF, relief)
            put(TerrainDerivedLayer.HILLSHADE_COMPARISON, hillshade)
            put(TerrainDerivedLayer.POSITIVE_OPENNESS, positiveOpen)
            put(TerrainDerivedLayer.NEGATIVE_OPENNESS, negativeOpen)
            put(TerrainDerivedLayer.SKY_VIEW_FACTOR, skyView)
            put(TerrainDerivedLayer.DEPRESSION_DEPTH, depression)
            put(TerrainDerivedLayer.RUGGEDNESS, rugged)
            put(TerrainDerivedLayer.LINEARITY, linearity)
            put(TerrainDerivedLayer.ANCIENT_STREAM, stream)
        }
        val result = TerrainIntelligenceResult(
            datasetKey = "historic-test",
            sourceWidth = width,
            sourceHeight = height,
            layers = TerrainDerivedLayers(width, height, 1f, values),
            candidates = emptyList(),
            recommendation = "test",
            cacheHit = TerrainDerivedLayerCache.Hit.MISS,
        )

        return result
    }

    @Test
    fun flatFeaturelessTerrainDoesNotProduceHistoricTargets() {
        val width = 32
        val height = 32
        val size = width * height
        val values = EnumMap<TerrainDerivedLayer, FloatArray>(TerrainDerivedLayer::class.java).apply {
            TerrainDerivedLayer.entries.forEach { layer ->
                put(
                    layer,
                    when (layer) {
                        TerrainDerivedLayer.POSITIVE_OPENNESS,
                        TerrainDerivedLayer.NEGATIVE_OPENNESS,
                        TerrainDerivedLayer.SKY_VIEW_FACTOR -> FloatArray(size) { 0.9f }
                        else -> FloatArray(size)
                    },
                )
            }
        }
        val result = TerrainIntelligenceResult(
            datasetKey = "blank-test",
            sourceWidth = width,
            sourceHeight = height,
            layers = TerrainDerivedLayers(width, height, 1f, values),
            candidates = emptyList(),
            recommendation = "test",
            cacheHit = TerrainDerivedLayerCache.Hit.MISS,
        )

        assertTrue(MetalDetectingTargetRefiner.refine(result).isEmpty())
    }
}
