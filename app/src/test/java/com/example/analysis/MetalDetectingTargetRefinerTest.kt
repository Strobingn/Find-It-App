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

        // Rectangular building platform with a strong, persistent two-cell perimeter.
        for (y in 12..27) {
            for (x in 9..29) {
                slope[index(x, y)] = 0.005f
                rugged[index(x, y)] = 0.005f
                val edgeDistance = minOf(x - 9, 29 - x, y - 12, 27 - y)
                if (edgeDistance <= 2) {
                    linearity[index(x, y)] = 1f
                    curvature[index(x, y)] = if ((x + y) % 2 == 0) 1f else -1f
                    hillshade[index(x, y)] = 1f
                    relief[index(x, y)] = 0.55f
                } else {
                    // Interior remains flat but retains weak multidirectional persistence.
                    linearity[index(x, y)] = 0.35f
                    hillshade[index(x, y)] = 0.35f
                }
            }
        }

        // Continuous low-gradient wagon-road corridor.
        for (x in 4..59) {
            for (y in 37..41) {
                linearity[index(x, y)] = 1f
                slope[index(x, y)] = 0.005f
                rugged[index(x, y)] = 0.005f
                relief[index(x, y)] = if (y == 39) -0.35f else 0.22f
                hillshade[index(x, y)] = 0.9f
            }
        }

        // Deep compact cellar hole with a raised rim.
        for (y in 43..53) {
            for (x in 12..22) {
                val dx = x - 17
                val dy = y - 48
                val distanceSquared = dx * dx + dy * dy
                when {
                    distanceSquared <= 9 -> {
                        depression[index(x, y)] = 1f
                        curvature[index(x, y)] = 1f
                        relief[index(x, y)] = -1f
                        positiveOpen[index(x, y)] = 0.18f
                    }
                    distanceSquared <= 22 -> {
                        relief[index(x, y)] = 1f
                        linearity[index(x, y)] = 0.8f
                        hillshade[index(x, y)] = 0.8f
                    }
                }
            }
        }

        // Shallower irregular refuse/privy-style pit beside the occupation platform.
        for (y in 24..32) {
            for (x in 29..37) {
                val dx = x - 33
                val dy = y - 28
                val distanceSquared = dx * dx + dy * dy
                when {
                    distanceSquared <= 8 -> {
                        depression[index(x, y)] = 0.55f
                        curvature[index(x, y)] = 0.65f
                        relief[index(x, y)] = -0.5f
                        rugged[index(x, y)] = if ((x + y) % 2 == 0) 0.7f else 0.4f
                    }
                    distanceSquared <= 18 -> {
                        relief[index(x, y)] = 0.45f
                        linearity[index(x, y)] = 0.5f
                        hillshade[index(x, y)] = 0.5f
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

        val targets = MetalDetectingTargetRefiner.refine(result)
        val types = targets.map { it.type }.toSet()

        assertTrue("Expected a foundation/platform candidate; got $types", MetalDetectingTargetType.FOUNDATION in types)
        assertTrue("Expected a road/trail candidate; got $types", MetalDetectingTargetType.ROAD_TRAIL in types)
        assertTrue("Expected a cellar-hole candidate; got $types", MetalDetectingTargetType.CELLAR_HOLE in types)
        assertTrue("Expected a homesite-context candidate; got $types", MetalDetectingTargetType.OLD_HOMESITE in types)
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
