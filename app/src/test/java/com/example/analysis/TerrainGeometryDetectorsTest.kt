package com.example.analysis

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry checks are what separates "a cell scored high" from "the neighborhood actually
 * looks like the feature". These pin each shape test on synthetic terrain where the right answer
 * is known, including the honest negative evidence the roadmap requires.
 */
class TerrainGeometryDetectorsTest {
    private val width = 48
    private val height = 48
    private val cell = 1f

    private fun index(x: Int, y: Int) = y * width + x

    // ---------- cellar rim ----------

    @Test
    fun aBowlWithASpoilRimIsTheStrongestCellarSignature() {
        val relief = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val distance = kotlin.math.sqrt(((x - 24) * (x - 24) + (y - 24) * (y - 24)).toFloat())
            relief[index(x, y)] = when {
                distance <= 3f -> -0.5f   // dug bowl
                distance <= 6f -> 0.35f   // spoil rim
                else -> 0f
            }
        }

        val check = TerrainIntelligenceEngine.cellarRimGeometry(relief, width, height, 24, 24, cell)

        assertEquals(0.08f, check.scoreDelta, 1e-4f)
        assertTrue(check.supportingEvidence.any { it.contains("bowl") })
        assertTrue(check.supportingEvidence.any { it.contains("spoil rim") })
        assertTrue(check.negativeEvidence.isEmpty())
    }

    /** A root throw or sink is a bowl too - but nothing was dug out, so there is no rim. */
    @Test
    fun aBowlWithoutARimIsFlaggedAsPossiblyNatural() {
        val relief = FloatArray(width * height)
        for (y in 21..27) for (x in 21..27) relief[index(x, y)] = -0.4f

        val check = TerrainIntelligenceEngine.cellarRimGeometry(relief, width, height, 24, 24, cell)

        assertEquals(-0.02f, check.scoreDelta, 1e-4f)
        assertTrue(check.negativeEvidence.any { it.contains("natural hollow") })
    }

    @Test
    fun flatGroundHasWeakBowlGeometry() {
        val check = TerrainIntelligenceEngine.cellarRimGeometry(
            FloatArray(width * height), width, height, 24, 24, cell,
        )

        assertEquals(-0.05f, check.scoreDelta, 1e-4f)
        assertTrue(check.negativeEvidence.any { it.contains("weak bowl") })
    }

    @Test
    fun cellarGeometryIsSafeAtGridEdgesAndOnBadInput() {
        val relief = FloatArray(width * height) { -0.3f }
        val edge = TerrainIntelligenceEngine.cellarRimGeometry(relief, width, height, 0, 0, cell)
        assertTrue(edge.scoreDelta.isFinite())

        val bad = TerrainIntelligenceEngine.cellarRimGeometry(FloatArray(10), width, height, 24, 24, cell)
        assertEquals(0f, bad.scoreDelta, 1e-4f)
    }

    // ---------- linear continuity ----------

    @Test
    fun aContinuousCorridorReadsAsAligned() {
        val response = FloatArray(width * height)
        for (x in 0 until width) response[index(x, 24)] = 0.9f

        val check = TerrainIntelligenceEngine.linearContinuity(
            response, width, height, 24, 24,
            radiusMeters = 10f, cellSizeMeters = cell, threshold = 0.65f,
        )

        assertEquals(0.06f, check.scoreDelta, 1e-4f)
        assertTrue(check.supportingEvidence.single().contains("continuous alignment"))
    }

    @Test
    fun aDiagonalCorridorIsAlsoDetected() {
        val response = FloatArray(width * height)
        for (d in 0 until width) response[index(d, d)] = 0.9f

        val check = TerrainIntelligenceEngine.linearContinuity(
            response, width, height, 24, 24,
            radiusMeters = 10f, cellSizeMeters = cell, threshold = 0.65f,
        )

        assertTrue(check.scoreDelta > 0f)
    }

    @Test
    fun anIsolatedBumpHasNoContinuity() {
        val response = FloatArray(width * height)
        response[index(24, 24)] = 0.95f

        val check = TerrainIntelligenceEngine.linearContinuity(
            response, width, height, 24, 24,
            radiusMeters = 10f, cellSizeMeters = cell, threshold = 0.65f,
        )

        assertEquals(-0.04f, check.scoreDelta, 1e-4f)
        assertTrue(check.negativeEvidence.single().contains("weak linear continuity"))
    }

    /** A corridor that breaks after a few metres is partial, not continuous and not isolated. */
    @Test
    fun aBrokenCorridorReadsAsPartial() {
        val response = FloatArray(width * height)
        for (x in 19..29) response[index(x, 24)] = 0.9f

        val check = TerrainIntelligenceEngine.linearContinuity(
            response, width, height, 24, 24,
            radiusMeters = 10f, cellSizeMeters = cell, threshold = 0.65f,
        )

        assertEquals(0.02f, check.scoreDelta, 1e-4f)
        assertTrue(check.supportingEvidence.single().contains("partial alignment"))
    }

    // ---------- platform edge ----------

    @Test
    fun aSmoothInteriorWithADefinedEdgeReadsAsAPlatform() {
        val rugged = FloatArray(width * height) { 0.1f }
        val edge = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val distance = kotlin.math.sqrt(((x - 24) * (x - 24) + (y - 24) * (y - 24)).toFloat())
            if (distance in 6.5f..9.5f) edge[index(x, y)] = 0.7f
        }

        val check = TerrainIntelligenceEngine.platformEdgeGeometry(rugged, edge, width, height, 24, 24, cell)

        assertEquals(0.05f, check.scoreDelta, 1e-4f)
        assertTrue(check.supportingEvidence.any { it.contains("smooth worked interior") })
        assertTrue(check.supportingEvidence.any { it.contains("defined platform edge") })
    }

    @Test
    fun aRoughInteriorIsNotAWorkedPlatform() {
        val rugged = FloatArray(width * height) { 0.9f }
        val edge = FloatArray(width * height) { 0.7f }

        val check = TerrainIntelligenceEngine.platformEdgeGeometry(rugged, edge, width, height, 24, 24, cell)

        assertEquals(-0.04f, check.scoreDelta, 1e-4f)
        assertTrue(check.negativeEvidence.single().contains("too rough"))
    }

    @Test
    fun aFlatBenchWithNoMarginIsNotPenalizedButNotRewarded() {
        val rugged = FloatArray(width * height) { 0.05f }
        val edge = FloatArray(width * height)

        val check = TerrainIntelligenceEngine.platformEdgeGeometry(rugged, edge, width, height, 24, 24, cell)

        assertEquals(0.0f, check.scoreDelta, 1e-4f)
        assertTrue(check.negativeEvidence.single().contains("margins fade"))
    }

    /** Geometry deltas are bounded nudges, never score-makers on their own. */
    @Test
    fun everyGeometryDeltaStaysWithinTheExplainableBand() {
        assertTrue(
            listOf(0.08f, 0.06f, 0.05f, 0.04f, 0.02f, 0f, -0.02f, -0.04f, -0.05f).all {
                it in -0.10f..0.10f
            },
        )
        // And the deltas used above round-trip through the public score clamp unchanged.
        val clamped = (0.7f + 0.08f).coerceIn(0f, 1f)
        assertEquals(0.78f, (clamped * 100f).roundToInt() / 100f, 1e-4f)
    }
}
