package com.example.data

import com.example.geospatial.GeoSpatialLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoaaCoastalLidarCatalogTest {
    @Test
    fun davQueryAsksForLidarFootprintsInWgs84() {
        val url = NoaaCoastalLidarCatalog.davQueryUrl(
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
        val datasets = NoaaCoastalLidarCatalog.davDatasets(
            """{"features":[{"attributes":{"id":33,"title":"2005 USACE NCMP Topobathy Lidar: Atlantic Coast (NY to VA)","data_type":"Lidar"}}]}""",
        )

        assertEquals(1, datasets.size)
        assertEquals(33, datasets.single().id)
        assertTrue(datasets.single().title.contains("Atlantic Coast"))
    }

    @Test
    fun filenameGridEstimatesNeighboringTileBoxes() {
        val origin = "20230413_1000010000.copc" to GeoSpatialLibrary.GeographicBounds(
            minLat = 40.0,
            maxLat = 40.01,
            minLon = -74.02,
            maxLon = -74.00,
        )
        val east = "20230413_1015010000.copc" to GeoSpatialLibrary.GeographicBounds(
            minLat = 40.0,
            maxLat = 40.01,
            minLon = -74.00,
            maxLon = -73.98,
        )
        val estimator = NoaaCopcGrid.fit(listOf(origin, east))
        val predicted = estimator.boundsFor("20230413_1015010000.copc")
            ?: error("expected bounds")

        assertEquals(east.second.minLat, predicted.minLat, 1e-9)
        assertEquals(east.second.minLon, predicted.minLon, 1e-9)
        assertEquals(east.second.maxLon, predicted.maxLon, 1e-9)
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
