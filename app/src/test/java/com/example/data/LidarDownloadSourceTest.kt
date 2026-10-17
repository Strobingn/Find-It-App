package com.example.data

import com.example.geospatial.GeoSpatialLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LidarDownloadSourceTest {
    @Test
    fun azureStatusMentionsFastDownload() {
        assertTrue(azureCopcStatus(2).contains("Azure"))
        assertTrue(azureFallbackStatus(3).contains("USGS TNM"))
        assertTrue(noaaCoastStatus(0).contains("Fast Azure"))
    }

    @Test
    fun newerDenserTilesRankAheadOfOlderUsgsHits() {
        val older = tile(name = "old.laz", year = 2010, points = 1_000_000L, source = LidarDownloadSource.USGS_TNM)
        val dense = tile(name = "dense.copc.laz", year = 2022, points = 24_000_000L, source = LidarDownloadSource.FAST_AZURE)
        val ranked = rankLidarTiles(listOf(older, dense))

        assertEquals("dense.copc.laz", ranked.first().name)
        assertEquals(LidarDownloadSource.FAST_AZURE, ranked.first().source)
    }

    @Test
    fun subtitleNamesTheFastHostAndPointCount() {
        val tile = tile(
            name = "tile.copc.laz",
            year = 2022,
            points = 24_369_468L,
            source = LidarDownloadSource.FAST_AZURE,
            host = LidarDownloadSource.FAST_AZURE.shortHost,
        )

        val subtitle = lidarTileSubtitle(tile)
        assertTrue(subtitle.contains("2022"))
        assertTrue(subtitle.contains("24M pts"))
        assertTrue(subtitle.contains("Azure"))
    }

    private fun tile(
        name: String,
        year: Int?,
        points: Long?,
        source: LidarDownloadSource,
        host: String = source.shortHost,
    ) = NysHistoricLazTileCatalog.Tile(
        objectId = name.hashCode().toLong(),
        name = name,
        downloadUrl = "https://example.org/$name",
        minLongitude = -74.05,
        minLatitude = 41.42,
        maxLongitude = -74.03,
        maxLatitude = 41.44,
        project = name,
        source = source,
        hostLabel = host,
        pointCount = points,
        acquiredYear = year,
    )

    @Test
    fun copcAssetBecomesADownloadableAzureTile() {
        val tile = CopcAsset(
            id = "a",
            title = "NY 2022",
            href = "https://usgslidareuwest.blob.core.windows.net/usgs-3dep-copc/a.copc.laz",
            bounds = GeoSpatialLibrary.GeographicBounds(41.0, 42.0, -74.0, -73.0),
            pointCount = 8_000_000L,
            datetime = "2022-06-01T00:00:00Z",
        ).toDownloadTile()

        assertEquals(LidarDownloadSource.FAST_AZURE, tile.source)
        assertEquals(2022, tile.acquiredYear)
        assertEquals(8_000_000L, tile.pointCount)
        assertEquals("a.copc.laz", tile.name)
    }
}
