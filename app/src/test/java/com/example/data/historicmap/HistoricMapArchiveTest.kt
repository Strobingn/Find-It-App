package com.example.data.historicmap

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HistoricMapArchiveTest {
    @Test
    fun storesIndexUnderFilesDirAndDeduplicatesFootprints() {
        val context = RuntimeEnvironment.getApplication() as Context
        val dir = File(context.filesDir, "historic-map-archive-test").apply { mkdirs() }
        val archive = HistoricMapArchive(context, dir)
        val first = HistoricMapRecord(
            id = "usgs-a",
            source = HistoricMapSource.USGS_HISTORICAL,
            title = "Port Leyden 1903",
            editionYear = 1903,
            attribution = "USGS",
            remoteUrl = "https://example.test/1903.tif",
            localFile = File(dir, "usgs-a.tif").apply { writeText("tif") },
            minLatitude = 43.0,
            maxLatitude = 43.2,
            minLongitude = -75.5,
            maxLongitude = -75.2,
            georeferenced = true,
            downloadedAtMillis = 1L,
        )
        archive.upsert(first)
        archive.upsert(first.copy(title = "Port Leyden 1903 reprint"))
        val index = archive.loadIndex()
        assertEquals(1, index.records.size)
        assertTrue(archive.contains(first))
        val listed = index.forBounds(43.05, 43.15, -75.4, -75.3)
        assertEquals(HistoricMapSource.USGS_HISTORICAL, listed.single().source)
        assertTrue(dir.canonicalPath.startsWith(context.filesDir.canonicalPath))
    }

    @Test
    fun pickerOrdersRumseyAndUsgsFirst() {
        val records = listOf(
            sample(HistoricMapSource.LIBRARY_OF_CONGRESS, 1910, "loc"),
            sample(HistoricMapSource.USGS_HISTORICAL, 1898, "usgs"),
            sample(HistoricMapSource.DAVID_RUMSEY, 1855, "rumsey"),
        )
        val ordered = HistoricMapIndex(records).forBounds(0.0, 1.0, 0.0, 1.0)
        assertEquals(
            listOf(HistoricMapSource.DAVID_RUMSEY, HistoricMapSource.USGS_HISTORICAL, HistoricMapSource.LIBRARY_OF_CONGRESS),
            ordered.map { it.source },
        )
    }

    private fun sample(source: HistoricMapSource, year: Int, id: String) = HistoricMapRecord(
        id = id,
        source = source,
        title = id,
        editionYear = year,
        attribution = id,
        remoteUrl = "https://example.test/$id",
        localFile = null,
        minLatitude = 0.0,
        maxLatitude = 1.0,
        minLongitude = 0.0,
        maxLongitude = 1.0,
        georeferenced = source != HistoricMapSource.LIBRARY_OF_CONGRESS,
    )
}
