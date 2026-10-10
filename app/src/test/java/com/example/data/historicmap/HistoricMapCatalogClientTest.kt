package com.example.data.historicmap

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoricMapCatalogClientTest {
    private val bbox = listOf(-75.5, 43.0, -75.2, 43.2)
    private val client = HistoricMapCatalogClient(opener = { null })

    @Test
    fun parsesUsgsEditions() {
        val items = JSONArray().put(
            JSONObject()
                .put("sourceId", "htmc-123")
                .put("title", "Port Leyden 1903")
                .put("downloadURL", "https://example.test/1903.tif")
                .put("publicationDate", "1903-01-01")
                .put("minY", 43.0).put("maxY", 43.2)
                .put("minX", -75.5).put("maxX", -75.2),
        )
        val records = client.parseUsgs(items, bbox)
        assertEquals(1, records.size)
        assertEquals(HistoricMapSource.USGS_HISTORICAL, records[0].source)
        assertEquals(1903, records[0].editionYear)
        assertTrue(records[0].georeferenced)
    }

    @Test
    fun parsesLocSanbornAsNonGeoreferenced() {
        val body = JSONObject().put(
            "results",
            JSONArray().put(
                JSONObject()
                    .put("id", "sanborn-albany-1888")
                    .put("title", "Albany 1888")
                    .put("date", "1888")
                    .put("image_url", "https://example.test/sanborn.jpg"),
            ),
        ).toString()
        val records = client.parseLoc(body, bbox, sanbornOnly = true)
        assertEquals(HistoricMapSource.SANBORN, records.single().source)
        assertEquals(1888, records.single().editionYear)
        assertEquals(false, records.single().georeferenced)
    }

    @Test
    fun userAgentIsDescriptive() {
        assertTrue(HistoricMapCatalogClient.USER_AGENT.contains("FindIt"))
        assertTrue(HistoricMapCatalogClient.USER_AGENT.contains("historic"))
    }

    @Test
    fun locResultsFarFromBboxAreDroppedWhenCoordinatesExist() {
        val body = JSONObject().put(
            "results",
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "near")
                        .put("title", "Near")
                        .put("date", "1900")
                        .put("image_url", "https://example.test/near.jpg")
                        .put("latlong", "43.1,-75.35"),
                )
                .put(
                    JSONObject()
                        .put("id", "far")
                        .put("title", "Far")
                        .put("date", "1900")
                        .put("image_url", "https://example.test/far.jpg")
                        .put("latlong", "10.0,10.0"),
                ),
        ).toString()
        val records = client.parseLoc(body, bbox, sanbornOnly = false)
        assertEquals(1, records.size)
        assertTrue(records.single().title.contains("Near"))
    }
}
