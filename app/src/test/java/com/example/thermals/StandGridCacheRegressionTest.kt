package com.example.thermals

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StandGridCacheRegressionTest {
    private fun cached(lat: Double = 42.75, lon: Double = -75.5): JSONObject {
        val size = StandFinder.GRID_SIZE
        val values = JSONArray().apply { repeat(size * size) { put(300.0) } }
        return JSONObject().put("source", "Test terrain").put("grid", JSONObject()
            .put("lat", lat).put("lon", lon)
            .put("size", size).put("spacing", StandFinder.GRID_SPACING_M)
            .put("meters", values))
    }

    @Test
    fun nearbySearchesDoNotShareAnEntry() {
        assertNotEquals(
            StandFinderService.gridCacheName(42.7496, -75.5),
            StandFinderService.gridCacheName(42.7504, -75.5),
        )
        assertEquals(
            StandFinderService.gridCacheName(42.75, -75.5),
            StandFinderService.gridCacheName(42.75, -75.5),
        )
    }

    @Test
    fun readsMatchingGridAndRejectsDifferentCenters() {
        val body = cached().toString()
        val fetch = StandFinderService.parseCachedGrid(body, 42.75, -75.5)
        assertNotNull(fetch)
        assertEquals("Test terrain", fetch!!.sourceLabel)
        assertEquals(300.0, fetch.grid.meters[0], 0.0)
        assertNull(StandFinderService.parseCachedGrid(body, 42.7504, -75.5))
        assertNull(StandFinderService.parseCachedGrid(body, 42.75, -75.5004))
    }

    @Test
    fun ignoresTruncatedAndObsoleteGrids() {
        val truncated = cached()
        truncated.getJSONObject("grid").put("meters", JSONArray().put(300.0))
        assertNull(StandFinderService.parseCachedGrid(truncated.toString(), 42.75, -75.5))
        val obsolete = cached()
        obsolete.getJSONObject("grid").put("spacing", StandFinder.GRID_SPACING_M * 2)
        assertNull(StandFinderService.parseCachedGrid(obsolete.toString(), 42.75, -75.5))
        assertNull(StandFinderService.parseCachedGrid("{broken", 42.75, -75.5))
    }
}
