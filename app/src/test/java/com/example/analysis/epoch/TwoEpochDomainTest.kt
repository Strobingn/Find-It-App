package com.example.analysis.epoch

import com.example.analysis.TerrainFeatureCandidate
import com.example.analysis.TerrainFeatureType
import com.example.data.ElevationGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoEpochDomainTest {

    private fun grid(w: Int, h: Int, elev: (Int, Int) -> Float): ElevationGrid {
        val bare = FloatArray(w * h) { i -> elev(i % w, i / w) }
        return ElevationGrid(w, h, bare, FloatArray(w * h), cellSizeMeters = 1f)
    }

    @Test
    fun demAligner_sameSizeRemovesBias() {
        val a = grid(16, 16) { _, _ -> 100f }
        val b = grid(16, 16) { _, _ -> 102f }
        val result = DemAligner.alignBToA(a, b)
        assertNotNull(result.alignedB)
        assertTrue(result.rmseMeters != null && result.rmseMeters!! < 0.2)
        assertTrue(result.confidence == DemAligner.AlignConfidence.GOOD ||
            result.confidence == DemAligner.AlignConfidence.FAIR)
    }

    @Test
    fun demAligner_resampleDifferentSize() {
        val a = grid(20, 20) { x, y -> x + y * 0.1f }
        val b = grid(10, 10) { x, y -> x * 2f + y * 0.2f + 1f }
        val result = DemAligner.alignBToA(a, b)
        assertNotNull(result.alignedB)
        assertEquals(20, result.alignedB!!.width)
        assertEquals(20, result.alignedB!!.height)
    }

    @Test
    fun surfaceChange_detectsRaisedBlock() {
        val a = grid(32, 32) { _, _ -> 50f }
        val b = grid(32, 32) { x, y -> if (x in 10..14 && y in 10..14) 51.5f else 50f }
        val align = DemAligner.alignBToA(a, b, removeMeanBias = false)
        val change = SurfaceChangeDetector.detect(a, align.alignedB!!, thresholdMeters = 0.4f)
        assertTrue(change.changedCellFraction > 0f)
        assertTrue(change.zones.isNotEmpty())
        assertTrue(change.honestyLine.contains("metal", ignoreCase = true))
    }

    @Test
    fun candidateDelta_appearedDisappearedScoreChanged() {
        fun c(type: TerrainFeatureType, x: Float, y: Float, score: Float) =
            TerrainFeatureCandidate(
                id = "$type-$x-$y",
                type = type,
                xPercent = x,
                yPercent = y,
                score = score,
                radiusMeters = 5f,
                evidence = emptyList(),
            )
        val a = listOf(
            c(TerrainFeatureType.CELLAR_HOLE, 20f, 20f, 0.8f),
            c(TerrainFeatureType.ROAD_TRAIL, 50f, 50f, 0.6f),
        )
        val b = listOf(
            c(TerrainFeatureType.CELLAR_HOLE, 21f, 20f, 0.5f), // score changed
            c(TerrainFeatureType.FOUNDATION, 80f, 80f, 0.7f), // appeared
        )
        val r = CandidateDelta.compare(a, b)
        assertEquals(1, r.appeared)
        assertEquals(1, r.disappeared)
        assertEquals(1, r.scoreChanged)
        assertTrue(r.note.contains("Appeared"))
    }
}
