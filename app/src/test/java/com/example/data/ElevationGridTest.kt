package com.example.data

import android.graphics.Color
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
}
