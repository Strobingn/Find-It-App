package com.example.data

import com.example.geospatial.GeoSpatialLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CopcStacCatalogTest {
    @Test
    fun searchUsesVisibleBoundsAndCopcCollection() {
        val body = CopcStacCatalog().buildSearchBody(
            GeoSpatialLibrary.GeographicBounds(41.0, 42.0, -74.0, -73.0),
            50,
        )

        assertEquals("3dep-lidar-copc", body.getJSONArray("collections").getString(0))
        assertEquals(-74.0, body.getJSONArray("bbox").getDouble(0), 0.0)
        assertEquals(42.0, body.getJSONArray("bbox").getDouble(3), 0.0)
        val sort = body.getJSONArray("sortby").getJSONObject(0)
        assertEquals("datetime", sort.getString("field"))
        assertEquals("desc", sort.getString("direction"))
    }

    @Test
    fun parserReturnsCopcAssetAndSixValueBounds() {
        val result = CopcStacCatalog().parseSearchResponse(
            """{"features":[{"id":"tile-1","bbox":[-74,41,10,-73,42,200],"properties":{"title":"USGS tile"},"assets":{"data":{"href":"https://example.org/tile.copc.laz"}}}]}""",
        )

        assertEquals(1, result.size)
        assertTrue(result.single().href.endsWith(".copc.laz"))
        assertEquals(42.0, result.single().bounds?.maxLat ?: 0.0, 0.0)
    }

    @Test
    fun parserKeepsPointCountAndDatetimeForDenserNewerRanking() {
        val result = CopcStacCatalog().parseSearchResponse(
            """{"features":[{"id":"tile-1","bbox":[-74,41,-73,42],"properties":{"datetime":"2022-04-01T00:00:00Z","pc:count":24369468},"assets":{"data":{"href":"https://usgslidareuwest.blob.core.windows.net/usgs-3dep-copc/tile.copc.laz"}}}]}""",
        )

        assertEquals(24_369_468L, result.single().pointCount)
        assertEquals("2022-04-01T00:00:00Z", result.single().datetime)
        val tile = result.single().toDownloadTile()
        assertEquals(LidarDownloadSource.FAST_AZURE, tile.source)
        assertEquals(2022, tile.acquiredYear)
        assertTrue(CopcStacCatalog().needsSigning(tile.downloadUrl))
    }

    @Test
    fun alreadySignedAzureUrlsAreLeftAlone() {
        assertTrue(
            !CopcStacCatalog().needsSigning(
                "https://usgslidareuwest.blob.core.windows.net/usgs-3dep-copc/tile.copc.laz?sig=abc",
            ),
        )
    }

    @Test
    fun invalidCatalogBoundsDoNotReachViewportMath() {
        val result = CopcStacCatalog().parseSearchResponse(
            """{"features":[{"id":"tile-1","bbox":[-73,42,-74,41],"assets":{"data":{"href":"https://example.org/tile.copc.laz"}}}]}""",
        )

        assertNull(result.single().bounds)
    }

    @Test
    fun appendsSasTokenWithoutDroppingExistingQuery() {
        val catalog = CopcStacCatalog()

        assertEquals(
            "https://example.test/file.copc.laz?sig=abc",
            catalog.appendToken("https://example.test/file.copc.laz", "sig=abc"),
        )
        assertEquals(
            "https://example.test/file.copc.laz?x=1&sig=abc",
            catalog.appendToken("https://example.test/file.copc.laz?x=1", "sig=abc"),
        )
    }
}
