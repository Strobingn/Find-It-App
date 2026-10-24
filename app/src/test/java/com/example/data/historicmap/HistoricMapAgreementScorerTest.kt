package com.example.data.historicmap

import com.example.data.ElevationGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricMapAgreementScorerTest {

    private fun grid(width: Int, height: Int, elevation: (Int, Int) -> Float): ElevationGrid {
        val bareEarth = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bareEarth[y * width + x] = elevation(x, y)
            }
        }
        return ElevationGrid(
            width = width,
            height = height,
            bareEarth = bareEarth,
            canopySpikes = FloatArray(width * height),
            cellSizeMeters = 1f,
        )
    }

    private fun pixels(width: Int, height: Int, dark: Set<Pair<Int, Int>>): IntArray {
        val pixels = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        for ((x, y) in dark) pixels[y * width + x] = 0xFF000000.toInt()
        return pixels
    }

    private fun testInkCells(
        pixels: IntArray,
        imageWidth: Int = 10,
        imageHeight: Int = 10,
        centerLatitude: Double = 41.0,
        centerLongitude: Double = -74.0,
        widthMeters: Float = 100f,
        heightMeters: Float = 100f,
        bearingDegrees: Float = 0f,
        gridMinLatitude: Double = 40.999,
        gridMaxLatitude: Double = 41.001,
        gridMinLongitude: Double = -74.001,
        gridMaxLongitude: Double = -73.999,
        evidenceWidth: Int = 4,
        evidenceHeight: Int = 4,
        sampleStep: Int = 1,
    ): Set<Int> = HistoricMapAgreementScorer.inkCells(
        pixels = pixels, imageWidth = imageWidth, imageHeight = imageHeight,
        centerLatitude = centerLatitude, centerLongitude = centerLongitude,
        widthMeters = widthMeters, heightMeters = heightMeters, bearingDegrees = bearingDegrees,
        gridMinLatitude = gridMinLatitude, gridMaxLatitude = gridMaxLatitude,
        gridMinLongitude = gridMinLongitude, gridMaxLongitude = gridMaxLongitude,
        evidenceWidth = evidenceWidth, evidenceHeight = evidenceHeight, sampleStep = sampleStep
    )

    private fun testScoreOverlay(
        pixels: IntArray,
        evidence: HistoricMapAgreementScorer.EvidenceGrid,
        imageWidth: Int,
        imageHeight: Int,
        centerLatitude: Double = 41.0005,
        centerLongitude: Double = -74.0005,
        widthMeters: Float = 84f,
        heightMeters: Float = 111f,
        bearingDegrees: Float = 0f,
        gridMinLatitude: Double = 41.0,
        gridMaxLatitude: Double = 41.001,
        gridMinLongitude: Double = -74.001,
        gridMaxLongitude: Double = -74.0,
    ): MapFeatureAgreement = HistoricMapAgreementScorer.scoreOverlay(
        pixels = pixels, imageWidth = imageWidth, imageHeight = imageHeight,
        centerLatitude = centerLatitude, centerLongitude = centerLongitude,
        widthMeters = widthMeters, heightMeters = heightMeters, bearingDegrees = bearingDegrees,
        gridMinLatitude = gridMinLatitude, gridMaxLatitude = gridMaxLatitude,
        gridMinLongitude = gridMinLongitude, gridMaxLongitude = gridMaxLongitude,
        evidence = evidence
    )

    @Test
    fun reliefEvidenceHighlightsRoughBlocks() {
        // Left half perfectly flat, right half bumpy; 4 blocks per side at maxSide 4.
        val terrain = grid(8, 8) { x, y -> if (x < 4) 10f else 10f + ((x + y) % 3).toFloat() }
        val evidence = HistoricMapAgreementScorer.buildReliefEvidence(terrain, maxSide = 4)
        assertEquals(4, evidence.width)
        assertEquals(4, evidence.height)
        assertTrue(evidence.valid.all { it })
        // Flat blocks produce zero relief; rough blocks normalize to the maximum.
        assertEquals(0f, evidence.values[0], 1e-6f)
        assertEquals(0f, evidence.values[1], 1e-6f)
        assertEquals(1f, evidence.values[2], 1e-6f)
        assertEquals(1f, evidence.values.maxOrNull()!!, 1e-6f)
        assertTrue(evidence.supportThreshold in 0f..1f)
    }

    @Test
    fun reliefEvidenceMarksNoDataBlocksInvalid() {
        val terrain = grid(8, 8) { _, _ -> 10f }
        java.util.Arrays.fill(terrain.validData, 4 * 8, 8 * 8, false) // bottom half no-data
        val evidence = HistoricMapAgreementScorer.buildReliefEvidence(terrain, maxSide = 4)
        // Top blocks (source rows 0-3) stay valid, bottom blocks (rows 4-7) do not.
        assertTrue(evidence.valid[0])
        assertTrue(evidence.valid[2])
        assertTrue(!evidence.valid[8])
        assertTrue(!evidence.valid[15])
    }

    @Test
    fun inkCellsMapsCenterPixelToExpectedCell() {
        // One dark pixel at (5,5) of a 10x10 image over a 100 m overlay centered in the grid.
        val cells = testInkCells(
            pixels = pixels(10, 10, setOf(5 to 5)),
            gridMinLatitude = 40.9995,
            gridMaxLatitude = 41.0005,
            gridMinLongitude = -74.0005,
            gridMaxLongitude = -73.9995,
        )
        assertEquals(setOf(2 * 4 + 2), cells)
    }

    @Test
    fun inkCellsIgnoresPaperAndHonorsBearing() {
        // Dark pixel at top-center (5,0): 45 m north of center when unrotated.
        val base = testInkCells(pixels = pixels(10, 10, setOf(5 to 0)))
        // Same ink with the overlay rotated 90 degrees clockwise: the ink moves east.
        val rotated = testInkCells(
            pixels = pixels(10, 10, setOf(5 to 0)),
            bearingDegrees = 90f,
        )
        assertEquals(setOf(1 * 4 + 2), base)
        assertEquals(setOf(2 * 4 + 3), rotated)

        // Sepia paper (luminance well above threshold) yields no cells at all.
        val sepia = IntArray(100) { 0xFFB0A090.toInt() }
        val none = testInkCells(pixels = sepia)
        assertTrue(none.isEmpty())
    }

    @Test
    fun scoreOverlayRewardsInkOnEvidenceAndPenalizesInkOffIt() {
        val evidence = HistoricMapAgreementScorer.EvidenceGrid(
            width = 2,
            height = 2,
            values = floatArrayOf(0.9f, 0.1f, 0.1f, 0.1f),
            valid = booleanArrayOf(true, true, true, true),
            supportThreshold = 0.5f,
        )
        // 2x2 image, single dark pixel in the NW corner, overlay exactly covering the grid.
        val onEvidence = testScoreOverlay(
            pixels = pixels(2, 2, setOf(0 to 0)),
            imageWidth = 2,
            imageHeight = 2,
            evidence = evidence,
        )
        assertEquals(1f, onEvidence.score, 1e-4f)

        // Ink in the SE quadrant (pixel (2,2) of a 4x4 image, so the default step-2 sampling
        // still sees it) sits on the weakest evidence cell and scores zero.
        val offEvidence = testScoreOverlay(
            pixels = pixels(4, 4, setOf(2 to 2)),
            imageWidth = 4,
            imageHeight = 4,
            evidence = evidence,
        )
        assertEquals(0f, offEvidence.score, 1e-4f)
    }
}
