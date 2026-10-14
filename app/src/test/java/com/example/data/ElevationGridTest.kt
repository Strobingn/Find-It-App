package com.example.data

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ElevationGridTest {
    @Test
    fun flatGroundGetsBrighterAsSunRises() {
        val grid = ElevationGrid(
            width = 3,
            height = 3,
            bareEarth = FloatArray(9) { 10f },
            canopySpikes = FloatArray(9),
        )

        val lowSun = grid.renderHillshade(315f, 10f, 1f, palette = 0, contrast = 1f)
        val highSun = grid.renderHillshade(315f, 80f, 1f, palette = 0, contrast = 1f)

        assertTrue(Color.red(highSun.getPixel(1, 1)) > Color.red(lowSun.getPixel(1, 1)))
    }

    @Test
    fun changingLightDirectionChangesDirectionalRelief() {
        val elevations = FloatArray(25) { index -> (index % 5).toFloat() }
        val grid = ElevationGrid(5, 5, elevations, FloatArray(25))

        val lightFromWest = grid.renderHillshade(270f, 30f, 1f, palette = 0, contrast = 1f)
        val lightFromEast = grid.renderHillshade(90f, 30f, 1f, palette = 0, contrast = 1f)

        assertNotEquals(lightFromWest.getPixel(2, 2), lightFromEast.getPixel(2, 2))
    }

    @Test
    fun disturbanceModeHighlightsLocalGroundChange() {
        val elevations = FloatArray(81) { 10f }
        elevations[4 * 9 + 4] = 11.5f
        val grid = ElevationGrid(9, 9, elevations, FloatArray(81), cellSizeMeters = 1f)

        val disturbance = grid.renderHillshade(
            sunAzimuth = 315f,
            sunAltitude = 35f,
            vegetationFilter = 1f,
            palette = 0,
            visualizationMode = 5,
            featureScaleMeters = 3f,
            analysisSensitivity = 2f,
        )

        assertNotEquals(disturbance.getPixel(0, 0), disturbance.getPixel(4, 4))
    }

    @Test
    fun aspectElevationAndCanopyModesExposeDifferentTerrainSignals() {
        val elevations = FloatArray(25) { index -> (index % 5 + index / 5).toFloat() }
        val canopy = FloatArray(25).also { it[12] = 8f }
        val grid = ElevationGrid(5, 5, elevations, canopy, cellSizeMeters = 1f)

        val aspect = grid.renderHillshade(315f, 35f, 0f, palette = 2, visualizationMode = 6)
        val elevation = grid.renderHillshade(315f, 35f, 0f, palette = 2, visualizationMode = 7)
        val canopyHeight = grid.renderHillshade(315f, 35f, 0f, palette = 2, visualizationMode = 8)

        assertNotEquals(aspect.getPixel(2, 2), elevation.getPixel(2, 2))
        assertNotEquals(canopyHeight.getPixel(2, 2), canopyHeight.getPixel(0, 0))
    }

    @Test
    fun invalidCellsRenderTransparentNeverBlack() {
        val valid = BooleanArray(9) { it == 4 }
        val elevations = FloatArray(9) { if (it == 4) 10f else Float.NaN }
        val grid = ElevationGrid(3, 3, elevations, FloatArray(9), cellSizeMeters = 1f, validData = valid)

        val bitmap = grid.renderHillshade(315f, 45f, 1f, palette = 0, contrast = 1f)

        val hole = bitmap.getPixel(0, 0)
        assertEquals(Color.TRANSPARENT, hole)
        assertEquals(0, Color.alpha(hole))
        assertNotEquals(Color.BLACK, hole)

        val center = bitmap.getPixel(1, 1)
        assertTrue(Color.alpha(center) > 0)
        assertNotEquals(Color.BLACK, center)
        assertTrue(Color.red(center) > 0 || Color.green(center) > 0 || Color.blue(center) > 0)
    }

    @Test
    fun hornSubstitutesNanNeighborsInsteadOfBlack() {
        val elevations = floatArrayOf(
            Float.NaN, Float.NaN, Float.NaN,
            Float.NaN, 12f, Float.NaN,
            Float.NaN, Float.NaN, Float.NaN,
        )
        val valid = BooleanArray(9) { it == 4 }
        val grid = ElevationGrid(3, 3, elevations, FloatArray(9), 1f, valid)
        val bitmap = grid.renderHillshade(315f, 35f, 1f, palette = 0, contrast = 1f)
        val center = bitmap.getPixel(1, 1)
        assertTrue("NaN neighbours must not collapse Horn shade to black", Color.alpha(center) == 255)
        assertNotEquals(Color.BLACK, center)
        assertTrue(Color.red(center) > 20)
    }

    @Test
    fun eastWestFoundationShowsWhenTheSunRunsAlongIt() {
        // A one-cell wall running east-west is flat on top, so light from the east
        // (along the wall) produces the same Horn shade as undisturbed ground.
        val flat = FloatArray(81) { 10f }
        val wall = FloatArray(81) { 10f }
        for (x in 0 until 9) wall[4 * 9 + x] = 10.35f
        val flatBmp = ElevationGrid(9, 9, flat, FloatArray(81), cellSizeMeters = 1f)
            .renderHillshade(90f, 35f, 1f, palette = 0, contrast = 1f, visualizationMode = 3, featureScaleMeters = 3f)
        val wallBmp = ElevationGrid(9, 9, wall, FloatArray(81), cellSizeMeters = 1f)
            .renderHillshade(90f, 35f, 1f, palette = 0, contrast = 1f, visualizationMode = 3, featureScaleMeters = 3f)

        assertTrue(
            Color.red(wallBmp.getPixel(4, 4)) > Color.red(flatBmp.getPixel(4, 4)),
        )
    }

    @Test
    fun wideFoundationOnASlopeStaysBrighterThanTheSameElevation() {
        // The middle of a foundation wider than one cell matches the hillside slope,
        // so a 3×3 shade cannot see it. Same absolute elevation, so the palette matches too.
        val side = 31
        val elevations = FloatArray(side * side) { index ->
            10f + (index % side) * 0.05f
        }
        for (y in 12..16) {
            for (x in 12..16) elevations[y * side + x] += 0.25f
        }
        val bitmap = ElevationGrid(side, side, elevations, FloatArray(elevations.size), cellSizeMeters = 1f)
            .renderHillshade(90f, 40f, 1f, palette = 0, contrast = 1f, visualizationMode = 3, featureScaleMeters = 8f)

        assertTrue(Color.red(bitmap.getPixel(14, 14)) > Color.red(bitmap.getPixel(19, 1)))
    }

    private fun nanDem(): ElevationGrid {
        val w = 24
        val h = 24
        val z = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            10f + 0.05f * x + 0.8f * kotlin.math.sin(x * 0.7f) * kotlin.math.cos(y * 0.5f)
        }
        val valid = BooleanArray(w * h) { true }
        for (i in listOf(0, 5, 3 * w + 7, 10 * w + 10, 10 * w + 11, 15 * w + 3, w * h - 1)) {
            z[i] = Float.NaN
            valid[i] = false
        }
        // Some NaN cells still flagged valid to mimic decoder holes.
        z[20 * w + 20] = Float.NaN
        return ElevationGrid(w, h, z, FloatArray(w * h), cellSizeMeters = 1f, validData = valid)
    }

    private fun assertModeRendersWithNaN(mode: Int) {
        val grid = nanDem()
        val bmp = grid.renderHillshade(
            sunAzimuth = 315f,
            sunAltitude = 35f,
            vegetationFilter = 1f,
            palette = 0,
            visualizationMode = mode,
            featureScaleMeters = 3f,
        )
        val colors = HashSet<Int>()
        var opaque = 0
        for (y in 0 until 24) for (x in 0 until 24) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) == 255) {
                opaque++
                colors += c
            }
        }
        assertEquals(Color.TRANSPARENT, bmp.getPixel(10, 10))
        assertTrue("mode $mode: most cells must render", opaque >= 24 * 24 - 8)
        assertTrue("mode $mode: output must not be constant, got ${colors.size}", colors.size > 8)
    }

    @Test
    fun localReliefSurvivesNaNCells() = assertModeRendersWithNaN(3)

    @Test
    fun curvatureSurvivesNaNCells() = assertModeRendersWithNaN(4)

    @Test
    fun disturbanceSurvivesNaNCells() = assertModeRendersWithNaN(5)
}
