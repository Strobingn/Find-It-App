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
}
