package com.example.analysis

import java.util.EnumMap
import org.junit.Assert.assertTrue
import org.junit.Test

class MetalDetectingTargetRefinerTest {
    @Test
    fun historicProfileFindsStructuredOccupationAndTravelFeatures() {
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

        // Rectangular building platform with a persistent perimeter.
        for (y in 13..25) {
            for (x in 10..27) {
                slope[index(x, y)] = 0.01f
                rugged[index(x, y)] = 0.01f
                if (x == 10 || x == 27 || y == 13 || y == 25) {
                    linearity[index(x, y)] = 1f
                    curvature[index(x, y)] = if ((x + y) % 2 == 0) 0.9f else -0.9f
                    hillshade[index(x, y)] = 1f
                    relief[index(x, y)] = 0.35f
                }
            }
        }

        // Continuous low-gradient wagon-road corridor.
        for (x in 5..58) {
            for (y in 38..40) {
                linearity[index(x, y)] = 1f
                slope[index(x, y)] = 0.01f
                rugged[index(x, y)] = 0.01f
                relief[index(x, y)] = if (y == 39) -0.25f else 0.12f
                hillshade[index(x, y)] = 0.75f
            }
        }

        // Deep compact cellar hole with a raised rim.
        for (y in 45..51) {
            for (x in 14..20) {
                val dx = x - 17
                val dy = y - 48
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared <= 5) {
                    depression[index(x, y)] = 1f
                    curvature[index(x, y)] = 1f
                    relief[index(x, y)] = -1f
                    positiveOpen[index(x, y)] = 0.25f
                } else if (distanceSquared <= 10) {
                    relief[index(x, y)] = 0.8f
                    linearity[index(x, y)] = 0.55f
                }
            }
        }

        // Shallower irregular refuse/privy-style pit near the foundation.
        for (y in 25..30) {
            for (x in 29..34) {
                val dx = x - 31
                val dy = y - 27
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared <= 5) {
                    depression[index(x, y)] = 0.45f
                    curvature[index(x, y)] = 0.55f
                    relief[index(x, y)] = -0.42f
                    rugged[index(x, y)] = if ((x + y) % 2 == 0) 0.6f else 0.35f
                } else if (distanceSquared <= 10) {
                    relief[index(x, y)] = 0.3f
                    linearity[index(x, y)] = 0.3f
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

        val targets = MetalDetectingTargetRefiner.refine(result)
        val types = targets.map { it.type }.toSet()

        assertTrue("Expected a foundation/platform candidate", MetalDetectingTargetType.FOUNDATION in types)
        assertTrue("Expected a road/trail candidate", MetalDetectingTargetType.ROAD_TRAIL in types)
        assertTrue("Expected a cellar-hole candidate", MetalDetectingTargetType.CELLAR_HOLE in types)
        assertTrue("Expected a homesite-context candidate", MetalDetectingTargetType.OLD_HOMESITE in types)
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
