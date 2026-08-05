package com.example.data.historicmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTerrainAgreementTest {
    private fun ridgeEvidence(width: Int, height: Int, ridgeRow: Int): FloatArray =
        FloatArray(width * height) { index -> if (index / width == ridgeRow) 1f else 0f }

    @Test
    fun featureOnEvidenceRidgeScoresFullAgreement() {
        val width = 10
        val height = 10
        val evidence = ridgeEvidence(width, height, ridgeRow = 5)
        val valid = BooleanArray(width * height) { true }
        val feature = MapTerrainAgreement.rasterizePolyline(
            listOf(0f to 5f, 9f to 5f),
            width,
            height,
        )

        val agreement = MapTerrainAgreement.score(feature, evidence, valid, supportThreshold = 0.5f)

        assertEquals(10, agreement.featureCells)
        assertEquals(10, agreement.supportingCells)
        assertEquals(1f, agreement.score, 0.0001f)
    }

    @Test
    fun featureOffEvidenceScoresZero() {
        val width = 10
        val height = 10
        val evidence = ridgeEvidence(width, height, ridgeRow = 5)
        val valid = BooleanArray(width * height) { true }
        val feature = MapTerrainAgreement.rasterizePolyline(
            listOf(0f to 0f, 9f to 0f),
            width,
            height,
        )

        val agreement = MapTerrainAgreement.score(feature, evidence, valid, supportThreshold = 0.5f)

        assertEquals(0, agreement.supportingCells)
        assertEquals(0f, agreement.score, 0.0001f)
    }

    @Test
    fun belowThresholdEvidenceEarnsContrastButNoSupport() {
        val width = 10
        val height = 10
        val evidence = FloatArray(width * height) { index -> if (index / width == 5) 0.5f else 0f }
        val valid = BooleanArray(width * height) { true }
        val feature = MapTerrainAgreement.rasterizePolyline(
            listOf(0f to 5f, 9f to 5f),
            width,
            height,
        )

        val agreement = MapTerrainAgreement.score(feature, evidence, valid, supportThreshold = 0.6f)

        assertEquals(0, agreement.supportingCells)
        assertEquals(0.4f, agreement.score, 0.001f)
    }

    @Test
    fun featureOutsideUsableDataScoresZero() {
        val evidence = FloatArray(100) { 1f }
        val valid = BooleanArray(100) { false }

        val agreement = MapTerrainAgreement.score(
            featureCells = setOf(0, 1, 2),
            evidence = evidence,
            validData = valid,
            supportThreshold = 0.5f,
        )

        assertEquals(0f, agreement.score, 0.0001f)
        assertEquals(0, agreement.featureCells)
    }

    @Test
    fun rankingAdjustmentIsBoundedAndNeutralAtHalf() {
        assertEquals(0.1f, MapTerrainAgreement.rankingAdjustment(1f), 0.0001f)
        assertEquals(-0.1f, MapTerrainAgreement.rankingAdjustment(0f), 0.0001f)
        assertEquals(0f, MapTerrainAgreement.rankingAdjustment(0.5f), 0.0001f)
        // Out-of-range input is clamped, never exceeding the cap.
        assertEquals(0.1f, MapTerrainAgreement.rankingAdjustment(1.5f), 0.0001f)
        assertTrue(MapTerrainAgreement.MAX_RANKING_ADJUSTMENT <= 0.1f)
    }

    @Test
    fun polylineRasterizationFollowsDiagonalAndDilates() {
        val diagonal = MapTerrainAgreement.rasterizePolyline(
            listOf(0f to 0f, 9f to 9f),
            width = 10,
            height = 10,
        )
        assertTrue(diagonal.contains(0))
        assertTrue(diagonal.contains(5 * 10 + 5))
        assertTrue(diagonal.contains(9 * 10 + 9))
        assertTrue("diagonal should cover >= 10 cells, got ${diagonal.size}", diagonal.size >= 10)

        val dilated = MapTerrainAgreement.rasterizePolyline(
            listOf(0f to 0f, 9f to 9f),
            width = 10,
            height = 10,
            halfWidthCells = 1,
        )
        assertTrue(dilated.size > diagonal.size)
        assertTrue(dilated.containsAll(diagonal))
    }
}
