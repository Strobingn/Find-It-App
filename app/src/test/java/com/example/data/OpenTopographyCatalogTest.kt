package com.example.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenTopographyCatalogTest {
    private val utahCatalog = """
        {"Datasets":[{"Dataset":{
          "name":"State of Utah Acquired Lidar Data - Wasatch Front",
          "identifier":{"@type":"PropertyValue","propertyID":"opentopoID","value":"OTLAS.122014.26912.1"},
          "alternateName":"UGS_Wasatch",
          "fileFormat":"Point Cloud Data",
          "dateCreated":"2015-01-29",
          "temporalCoverage":"2013-10-18 / 2014-05-31",
          "spatialCoverage":{"@type":"Place","geo":{"@type":"GeoShape","geojson":{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-111.9,40.7],[-111.8,40.7],[-111.8,40.8],[-111.9,40.8],[-111.9,40.7]]]}}]}}}
        }}]}
    """.trimIndent()

    @Test
    fun catalogUrlAsksForPointCloudsInWgs84() {
        val url = OpenTopographyCatalog.catalogUrl(
            west = -111.90,
            south = 40.77,
            east = -111.88,
            north = 40.78,
        )

        assertTrue(url.startsWith(OpenTopographyCatalog.CATALOG_URL))
        assertTrue(url.contains("productFormat=PointCloud"))
        assertTrue(url.contains("minx="))
        assertTrue(url.contains("detail=true"))
        assertFalse(url.contains("include_federated=true"))
    }

    @Test
    fun parserReadsShortNameYearAndCropUrl() {
        val tiles = OpenTopographyCatalog.tilesFromCatalogJson(
            json = utahCatalog,
            west = -111.8899,
            south = 40.7760,
            east = -111.8867,
            north = 40.7798,
        )

        assertEquals(1, tiles.size)
        val tile = tiles.single()
        assertEquals(LidarDownloadSource.OPEN_TOPOGRAPHY, tile.source)
        assertEquals("UGS_Wasatch_crop.laz", tile.name)
        assertEquals(2014, tile.acquiredYear)
        assertTrue(tile.downloadUrl.startsWith(OpenTopographyCatalog.POINT_CLOUD_URL))
        assertTrue(tile.downloadUrl.contains("datasetName=UGS_Wasatch"))
        assertTrue(tile.downloadUrl.contains("outputFormat=LAZ"))
        assertFalse(tile.downloadUrl.contains("API_Key="))
        assertEquals(-111.8899, tile.minLongitude!!, 1e-9)
        assertEquals(40.7798, tile.maxLatitude!!, 1e-9)
    }

    @Test
    fun cropBoxClipsToDatasetFootprint() {
        val tiles = OpenTopographyCatalog.tilesFromCatalogJson(
            json = utahCatalog,
            west = -112.5,
            south = 40.0,
            east = -111.0,
            north = 41.0,
        )

        val tile = tiles.single()
        assertEquals(-111.9, tile.minLongitude!!, 1e-9)
        assertEquals(-111.8, tile.maxLongitude!!, 1e-9)
        assertEquals(40.7, tile.minLatitude!!, 1e-9)
        assertEquals(40.8, tile.maxLatitude!!, 1e-9)
    }

    @Test
    fun rasterHitsAreIgnored() {
        val tiles = OpenTopographyCatalog.tilesFromCatalogJson(
            json = """{"Datasets":[{"Dataset":{"name":"SRTM","alternateName":"SRTMGL1","fileFormat":"Raster"}}]}""",
            west = -112.0,
            south = 40.0,
            east = -111.0,
            north = 41.0,
        )

        assertTrue(tiles.isEmpty())
    }

    @Test
    fun cropUrlNeedsApiKeyUntilAttached() {
        val url = OpenTopographyCatalog.cropUrl("UGS_Wasatch", -111.89, 40.77, -111.88, 40.78)

        assertTrue(OpenTopographyCatalog.needsApiKey(url))
        val signed = OpenTopographyCatalog.attachApiKeyIfNeeded(url, "abc-key")
        assertTrue(signed.contains("API_Key=abc-key"))
        assertFalse(OpenTopographyCatalog.needsApiKey(signed))
        assertEquals(signed, OpenTopographyCatalog.attachApiKeyIfNeeded(signed, "other"))
        assertThrows(IOException::class.java) {
            OpenTopographyCatalog.attachApiKeyIfNeeded(url, "  ")
        }
    }

    @Test
    fun laterSurveysRankFirst() {
        val json = """
            {"Datasets":[
              {"Dataset":{"name":"Old","alternateName":"OLD","fileFormat":"Point Cloud Data","temporalCoverage":"2010"}},
              {"Dataset":{"name":"New","alternateName":"NEW","fileFormat":"Point Cloud Data","temporalCoverage":"2025-02-20"}}
            ]}
        """.trimIndent()
        val tiles = OpenTopographyCatalog.tilesFromCatalogJson(
            json = json,
            west = -74.05,
            south = 41.42,
            east = -74.03,
            north = 41.44,
        )

        assertEquals("NEW_crop.laz", tiles.first().name)
        assertEquals(2025, tiles.first().acquiredYear)
    }

    @Test
    fun placeholderApiKeysAreIgnored() {
        assertEquals("", OpenTopographyApiKey.sanitize("YOUR_API_KEY"))
        assertEquals("ot-real-key", OpenTopographyApiKey.sanitize(" ot-real-key "))
    }
}
