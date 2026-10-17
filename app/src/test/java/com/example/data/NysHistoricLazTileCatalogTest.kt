package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NysHistoricLazTileCatalogTest {
    @Test
    fun nationalMapLookupUsesPointBoundingBoxAndLidarDataset() {
        val url = NysHistoricLazTileCatalog().buildNationalMapUrl(
            west = -74.04,
            south = 41.43,
            east = -74.04,
            north = 41.43,
        )

        assertTrue(url.startsWith(NysHistoricLazTileCatalog.NATIONAL_MAP_PRODUCTS_URL))
        assertTrue(url.contains("Lidar+Point+Cloud+%28LPC%29"))
        assertTrue(url.contains("bbox="))
        assertTrue(url.contains("max=100"))
    }

    @Test
    fun contentRangeReportsTheCompleteRemoteFileSize() {
        assertTrue(NysHistoricLazTileCatalog().contentRangeLength("bytes 0-0/43782912") == 43_782_912L)
        assertTrue(NysHistoricLazTileCatalog().contentRangeLength("bytes 0-0/*") == null)
    }

    /**
     * Regression: results used to be discarded unless the title named the Hudson Valley
     * SE 4-county 2022 survey, so every coordinate outside that one project found nothing.
     */
    @Test
    fun nationalMapKeepsFileSizeAndYearForFastEstimates() {
        val tiles = NysHistoricLazTileCatalog().parseNationalMap(
            json = productsJson(
                item(
                    title = "USGS Lidar Point Cloud NY_SouthEast4County_A22 u_5790058550_2022",
                    downloadUrl = "https://example.org/hudson.laz",
                    sizeInBytes = 126_192_321L,
                    publicationDate = "2024-09-23",
                ),
            ),
            west = -74.04,
            south = 41.43,
            east = -74.04,
            north = 41.43,
        )

        assertEquals(1, tiles.size)
        assertEquals(126_192_321L, tiles[0].sizeBytes)
        assertEquals(2024, tiles[0].acquiredYear)
        assertEquals(LidarDownloadSource.USGS_TNM, tiles[0].source)
    }

    @Test
    fun coveringTilesFromOtherProjectsAreReturned() {
        val tiles = NysHistoricLazTileCatalog().parseNationalMap(
            json = productsJson(
                item("NY_Statewide_B99", "https://example.org/other.laz"),
            ),
            west = -74.04,
            south = 41.43,
            east = -74.04,
            north = 41.43,
        )

        assertEquals(1, tiles.size)
        assertEquals("https://example.org/other.laz", tiles[0].downloadUrl)
        assertEquals("NY_Statewide_B99", tiles[0].project)
    }

    @Test
    fun theSoutheastFourCountyProjectIsRankedFirst() {
        val tiles = NysHistoricLazTileCatalog().parseNationalMap(
            json = productsJson(
                item("NY_Statewide_B99", "https://example.org/other.laz"),
                item("NY_SouthEast4County_A22", "https://example.org/hudson.laz"),
            ),
            west = -74.04,
            south = 41.43,
            east = -74.04,
            north = 41.43,
        )

        assertEquals(2, tiles.size)
        assertEquals("https://example.org/hudson.laz", tiles[0].downloadUrl)
    }

    @Test
    fun tilesThatDoNotCoverTheQueryAreStillExcluded() {
        val tiles = NysHistoricLazTileCatalog().parseNationalMap(
            json = productsJson(
                item("NY_Statewide_B99", "https://example.org/far.laz", minX = -80.0, maxX = -79.0),
            ),
            west = -74.04,
            south = 41.43,
            east = -74.04,
            north = 41.43,
        )

        assertTrue(tiles.isEmpty())
    }

    @Test
    fun nonPointCloudDownloadsAreIgnored() {
        val tiles = NysHistoricLazTileCatalog().parseNationalMap(
            json = productsJson(
                item("NY_Statewide_B99", "https://example.org/metadata.xml"),
                item("NY_Statewide_B99", "http://example.org/insecure.laz"),
            ),
            west = -74.04,
            south = 41.43,
            east = -74.04,
            north = 41.43,
        )

        assertTrue(tiles.isEmpty())
    }

    /**
     * Area searches page through the product feed; the first request must stay offset-free so the
     * service returns the head of the list.
     */
    @Test
    fun laterPagesCarryAnOffsetAndTheFirstDoesNot() {
        val catalog = NysHistoricLazTileCatalog()
        val firstPage = catalog.buildNationalMapUrl(
            west = -74.05,
            south = 41.42,
            east = -74.03,
            north = 41.44,
        )
        val secondPage = catalog.buildNationalMapUrl(
            west = -74.05,
            south = 41.42,
            east = -74.03,
            north = 41.44,
            offset = 100,
        )

        assertFalse(firstPage.contains("offset="))
        assertTrue(secondPage.contains("offset=100"))
        assertTrue(secondPage.contains("max=100"))
    }

    private fun item(
        title: String,
        downloadUrl: String,
        minX: Double = -74.05,
        maxX: Double = -74.03,
        sizeInBytes: Long? = null,
        publicationDate: String? = null,
    ): String = buildString {
        append("{")
        append("\"title\": \"$title\",")
        append("\"downloadURL\": \"$downloadUrl\",")
        sizeInBytes?.let { append("\"sizeInBytes\": $it,") }
        publicationDate?.let { append("\"publicationDate\": \"$it\",") }
        append("\"boundingBox\": { \"minX\": $minX, \"minY\": 41.42, \"maxX\": $maxX, \"maxY\": 41.44 }")
        append("}")
    }

    private fun productsJson(vararg items: String): String =
        """{ "items": [ ${items.joinToString(",")} ] }"""
}
