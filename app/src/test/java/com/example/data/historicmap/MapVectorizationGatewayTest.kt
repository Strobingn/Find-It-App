package com.example.data.historicmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapVectorizationGatewayTest {

    @Test
    fun parseMapFeatureLines_readsRoadPolyline() {
        val text = """
            Drafts:
            MAP_FEATURE type=ROAD conf=0.72 note=old track 42.10000,-74.20000;42.10010,-74.19950
            MAP_FEATURE type=STRUCTURE conf=0.55 note=dot 42.10100,-74.20100
        """.trimIndent()
        val features = MapVectorizationGateway.parseMapFeatureLines(text, "map-1", 99L)
        assertEquals(2, features.size)
        assertEquals(MapFeatureType.ROAD, features[0].type)
        assertEquals(2, features[0].points.size)
        assertTrue(features[0].note.contains("Cloud"))
        assertEquals("map-1", features[0].mapId)
    }

    @Test
    fun extractLocal_tagsLocalInk() {
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (x in 5 until 55) {
            for (t in -1..1) pixels[(32 + t) * w + x] = 0xFF111111.toInt()
        }
        val transform = GeoReferenceTransform(0.001, 0.0, -74.0, 0.0, -0.001, 42.0)
        val result = MapVectorizationGateway.extractLocal(pixels, w, h, "m", transform, 1L)
        assertEquals(MapVectorizationGateway.Mode.LOCAL, result.mode)
        assertTrue(result.features.isNotEmpty())
        assertTrue(result.features.all { it.note.contains("Local", ignoreCase = true) })
    }
}
