package com.example.analysis

import android.graphics.Color
import com.example.data.ElevationGrid
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the AI-tab crash on build 1000685: derived layers keep NaN in no-data cells,
 * and renderLayer's colour ramps called roundToInt() on NaN ("Cannot round NaN value").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RenderLayerNoDataTest {
    @Test
    fun everyLayerRendersWithNoDataBorder() = runBlocking {
        val w = 80
        val h = 80
        val valid = BooleanArray(w * h) { i -> (i % w) in 8..71 && (i / w) in 8..71 }
        val z = FloatArray(w * h) { i ->
            if (valid[i]) 100f + kotlin.math.sin((i % w) * 0.3f) * 2f + (i / w) * 0.1f else Float.NaN
        }
        val dir = Files.createTempDirectory("render-layer").toFile()
        try {
            val result = TerrainIntelligenceEngine(TerrainDerivedLayerCache(dir))
                .analyze(ElevationGrid(w, h, z, FloatArray(w * h), 1f, valid), "test")
            for (layer in result.layers.values.keys) {
                val bitmap = TerrainIntelligenceRenderer.renderLayer(result, layer)
                assertEquals(w, bitmap.width)
                val values = result.layers.values.getValue(layer)
                if (!values[0].isFinite()) assertEquals("$layer no-data pixel", Color.TRANSPARENT, bitmap.getPixel(0, 0))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
