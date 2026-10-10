package com.example.analysis

import com.example.geospatial.GeoSpatialLibrary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmModernLoaderTest {
    @Test
    fun parsesRoadsAndBuildingsIntoNormalizedPercents() {
        val bounds = GeoSpatialLibrary.GeographicBounds(43.0, 43.2, -75.5, -75.2)
        val body = JSONObject().put(
            "elements",
            JSONArray()
                .put(
                    JSONObject()
                        .put("tags", JSONObject().put("highway", "primary"))
                        .put("center", JSONObject().put("lat", 43.1).put("lon", -75.35)),
                )
                .put(
                    JSONObject()
                        .put("tags", JSONObject().put("building", "yes"))
                        .put("center", JSONObject().put("lat", 43.05).put("lon", -75.25)),
                ),
        ).toString()
        val context = OsmModernLoader.parse(body, bounds)
        assertEquals(2, context.features.size)
        assertTrue(context.hitsModern(50f, 50f, 8f))
    }
}
