package com.example.analysis

import java.util.EnumMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigDepthEstimatorTest {

    @Test
    fun `deeper cellar bowl estimates deeper dig`() {
        val shallow = estimateAt(depressionMeters = 0.3f, type = MetalDetectingTargetType.CELLAR_HOLE)
        val deep = estimateAt(depressionMeters = 1.2f, type = MetalDetectingTargetType.CELLAR_HOLE)
        assertTrue(deep.maxCm > shallow.maxCm)
        assertEquals(75, shallow.maxCm) // 30 cm bowl + 45 fill allowance
        assertEquals(165, deep.maxCm)
    }

    @Test
    fun `flat ground still gives a minimum band`() {
        val estimate = estimateAt(depressionMeters = 0f, type = MetalDetectingTargetType.TRASH_PIT)
        assertTrue(estimate.minCm >= 5)
        assertTrue(estimate.maxCm >= 35)
    }

    @Test
    fun `band reads out in inches, not centimetres`() {
        // The app quotes every other length in US customary units; a 30 cm bowl plus the 45 cm
        // fill allowance is a 10-75 cm band, which is 4-30 in.
        val estimate = estimateAt(depressionMeters = 0.3f, type = MetalDetectingTargetType.CELLAR_HOLE)
        assertEquals(10, estimate.minCm)
        assertEquals(75, estimate.maxCm)
        assertEquals("4–30 in", estimate.label)
        assertTrue("basis should quote inches, got: ${estimate.basis}", estimate.basis.contains(" in"))
    }

    @Test
    fun `surface feature types return no estimate`() {
        assertNull(DigDepthEstimator.estimate(target(MetalDetectingTargetType.ROAD_TRAIL), layers(0.8f)))
        assertNull(DigDepthEstimator.estimate(target(MetalDetectingTargetType.STONE_WALL), layers(0.8f)))
    }

    @Test
    fun `missing depression layer degrades to shallow band`() {
        val target = target(MetalDetectingTargetType.FOUNDATION)
        val layers = layers(0f, includeDepression = false)
        val estimate = DigDepthEstimator.estimate(target, layers)
        assertEquals(5, estimate!!.minCm)
        assertEquals(25, estimate.maxCm)
    }

    private fun estimateAt(depressionMeters: Float, type: MetalDetectingTargetType): DigDepthEstimate =
        DigDepthEstimator.estimate(target(type), layers(depressionMeters))!!

    private fun target(type: MetalDetectingTargetType) = MetalDetectingTarget(
        type = type,
        xPercent = 50f,
        yPercent = 50f,
        score = 0.8f,
        radiusMeters = 5f,
        evidence = emptyList(),
    )

    private fun layers(depressionMeters: Float, includeDepression: Boolean = true): TerrainDerivedLayers {
        val width = 16
        val height = 16
        val size = width * height
        val values = EnumMap<TerrainDerivedLayer, FloatArray>(TerrainDerivedLayer::class.java).apply {
            put(TerrainDerivedLayer.SLOPE, FloatArray(size))
            if (includeDepression) {
                put(TerrainDerivedLayer.DEPRESSION_DEPTH, FloatArray(size) { depressionMeters })
            }
        }
        return TerrainDerivedLayers(width, height, 1f, values)
    }
}
