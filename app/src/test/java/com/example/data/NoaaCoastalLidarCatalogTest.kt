package com.example.data

import com.example.geospatial.GeoSpatialLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoaaCoastalLidarCatalogTest {
    @Test
    fun davQueryAsksForLidarFootprintsInWgs84() {
        val url = NoaaCoastalLidarCatalog().buildDavQueryUrl(
            west = -74.05,
            south = 40.55,
            east = -73.95,
            north = 40.65,
        )

        assertTrue(url.startsWith(NoaaCoastalLidarCatalog.DAV_FOOTPRINTS_QUERY))
        assertTrue(url.contains("data_type"))
        assertTrue(url.contains("esriGeometryEnvelope"))
        assertTrue(url.contains("inSR=4326"))
    }

    @Test
    fun davParserReadsDatasetIds() {
        val datasets = NoaaCoastalLidarCatalog().parseDavDatasets(
            """{"features":[{"attributes":{"id":33,"title":"2005 USACE NCMP Topobathy Lidar: Atlantic Coast (NY to VA)","data_type":"Lidar"}}]}""",
        )

        assertEquals(1, datasets.size)
        assertEquals(33, datasets.single().id)
        assertTrue(datasets.single().title.contains("Atlantic Coast"))
    }

    @Test
    fun filenameGridEstimatesNeighboringTileBoxes() {
        val origin = "20230413_2175078050.copc" to GeoSpatialLibrary.GeographicBounds(
            minLat = 43.12412326,
            maxLat = 43.13897811,
            minLon = -78.4740993,
            maxLon = -78.45382011,
        )
        val north = "20230413_2175078200.copc" to GeoSpatialLibrary.GeographicBounds(
            minLat = 43.13760658,
            maxLat = 43.1524596,
            minLon = -78.47486282,
            maxLon = -78.45457853,
        )
        val estimator = NoaaCopcGrid.fit(listOf(origin, north))
        val predicted = estimator.boundsFor("20230413_2175078200.copc")
            ?: error("expected bounds")

        assertEquals(north.second.minLat, predicted.minLat, 1e-6)
        assertEquals(north.second.minLon, predicted.minLon, 1e-6)
    }

    @Test
    fun copcItemHrefMapsOntoThePublicS3LazObject() {
        val laz = NoaaCoastalLidarCatalog.lazUrlForItem(
            "https://noaa-nos-coastal-lidar-pds.s3.amazonaws.com/laz/geoid18/10407/stac/20230413_2175078050.copc.json",
        )

        assertEquals(
            "https://noaa-nos-coastal-lidar-pds.s3.amazonaws.com/laz/geoid18/10407/20230413_2175078050.copc.laz",
            laz,
        )
        assertFalse(laz.contains("/stac/"))
    }
}
