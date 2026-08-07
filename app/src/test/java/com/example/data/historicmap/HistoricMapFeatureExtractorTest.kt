package com.example.data.historicmap

import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricMapFeatureExtractorTest {

    @Test
    fun extract_findsDarkInkLineAsRoadOrWall() {
        val w = 80
        val h = 80
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() } // white paper
        // Dark horizontal ink stroke
        for (x in 10 until 70) {
            for (t in -1..1) {
                val y = 40 + t
                pixels[y * w + x] = 0xFF101010.toInt()
            }
        }
        // Identity-ish transform: lon = x/1000, lat = y/1000 (far from poles)
        val transform = GeoReferenceTransform(
            a = 0.001, b = 0.0, c = -74.0,
            d = 0.0, e = -0.001, f = 42.0,
        )
        val result = HistoricMapFeatureExtractor.extract(
            pixels = pixels,
            width = w,
            height = h,
            mapId = "map-1",
            transform = transform,
            maxFeatures = 8,
            nowMillis = 1_000L,
        )
        assertTrue(result.componentCount >= 1)
        assertTrue(result.features.isNotEmpty())
        assertTrue(
            result.features.any {
                it.type == MapFeatureType.ROAD || it.type == MapFeatureType.WALL
            },
        )
        assertTrue(result.features.all { it.mapId == "map-1" })
        assertTrue(result.features.all { it.points.isNotEmpty() })
        assertTrue(result.note.contains("Extracted", ignoreCase = true))
    }

    @Test
    fun extract_emptyInk_returnsFriendlyNote() {
        val w = 40
        val h = 40
        val pixels = IntArray(w * h) { 0xFFF0F0F0.toInt() }
        val transform = GeoReferenceTransform(0.001, 0.0, -74.0, 0.0, -0.001, 42.0)
        val result = HistoricMapFeatureExtractor.extract(
            pixels, w, h, "m", transform, maxFeatures = 4, nowMillis = 1L,
        )
        assertTrue(result.features.isEmpty())
        assertTrue(result.note.contains("No ink", ignoreCase = true) || result.note.contains("threshold", ignoreCase = true))
    }
}
