package com.example.analysis.eval

import com.example.analysis.FeatureDisplayBucket
import com.example.analysis.TerrainFeatureCandidate
import com.example.analysis.TerrainFeatureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureDetectionEvalTest {
    @Test
    fun holdOutWholeAreasAndFitsPrecisionThreshold() {
        val areas = FeatureDetectionEval.bundledAreas()
        assertTrue(areas.all { it.id.startsWith("holdout-") })
        assertTrue(areas.flatMap { it.sites }.none { it.source.name.contains("MAP") })

        val detections = areas.associate { area ->
            area.id to area.sites.map { site ->
                TerrainFeatureCandidate(
                    id = "det-${site.id}",
                    type = site.type,
                    xPercent = site.xPercent,
                    yPercent = site.yPercent,
                    score = 0.93f,
                    radiusMeters = 8f,
                    evidence = listOf("hold-out fixture"),
                    displayBucket = FeatureDisplayBucket.AUTO,
                )
            }
        }
        val report = FeatureDetectionEval.runHoldOut(areas, detections)
        assertEquals(areas.map { it.id }.toSet(), report.holdOutAreas.toSet())
        assertTrue(report.overallPrecision + 1e-5f >= FeatureDetectionEval.TARGET_PRECISION)
        assertTrue(report.autoShowThreshold >= 0.7f)
        assertTrue(report.notes.any { it.contains("Old maps") })
    }

    @Test
    fun falsePositiveOnModernRoadLowersPrecision() {
        val area = FeatureDetectionEval.bundledAreas().first()
        val detections = area.sites.map {
            TerrainFeatureCandidate(
                id = "ok-${it.id}",
                type = it.type,
                xPercent = it.xPercent,
                yPercent = it.yPercent,
                score = 0.9f,
                radiusMeters = 8f,
                evidence = emptyList(),
                displayBucket = FeatureDisplayBucket.AUTO,
            )
        } + TerrainFeatureCandidate(
            id = "fp-road",
            type = TerrainFeatureType.ROAD_TRAIL,
            xPercent = 5f,
            yPercent = 5f,
            score = 0.99f,
            radiusMeters = 5f,
            evidence = emptyList(),
            displayBucket = FeatureDisplayBucket.AUTO,
        )
        val score = FeatureDetectionEval.scoreArea(area, detections)
        assertTrue(score.falsePositives >= 1)
        assertTrue(score.precision < 1f)
    }
}
