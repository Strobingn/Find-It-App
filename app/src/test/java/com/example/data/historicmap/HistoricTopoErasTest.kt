package com.example.data.historicmap

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoricTopoErasTest {
    private fun record(id: String, year: Int, scale: Int, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) =
        HistoricMapRecord(
            id = id,
            source = HistoricMapSource.USGS_HISTORICAL,
            title = "USGS 1:$scale-scale Quadrangle for Test, NY $year",
            editionYear = year,
            attribution = "USGS",
            remoteUrl = "https://example.test/NY_Test_${id}_${year}_${scale}_geo.tif",
            localFile = null,
            minLatitude = minLat,
            maxLatitude = maxLat,
            minLongitude = minLon,
            maxLongitude = maxLon,
            georeferenced = true,
        )

    private val sheets = listOf(
        record("a", 1902, 62_500, 41.25, 41.5, -74.25, -74.0),
        record("b", 1903, 62_500, 41.25, 41.5, -74.25, -74.0),
        record("c", 1947, 24_000, 41.375, 41.5, -74.125, -74.0),
        record("d", 1943, 62_500, 41.25, 41.5, -74.25, -74.0),
        record("e", 1953, 250_000, 41.0, 42.0, -76.0, -74.0),
    )

    @Test
    fun decadesAtPointAreSorted() {
        assertEquals(listOf(1900, 1940, 1950), HistoricTopoEras.decades(sheets, 41.45, -74.05))
    }

    @Test
    fun earliestEditionPerFootprintAndFinestOnTop() {
        val picked = HistoricTopoEras.sheetsFor(sheets, 1900, 41.44, 41.46, -74.06, -74.04, 41.45, -74.05)
        assertEquals(listOf("a"), picked.map { it.id })
        val forties = HistoricTopoEras.sheetsFor(sheets, 1940, 41.44, 41.46, -74.06, -74.04, 41.45, -74.05)
        // Coarse 62,500 drawn first, fine 24,000 last (on top).
        assertEquals(listOf("d", "c"), forties.map { it.id })
    }

    @Test
    fun scaleIsReadFromTitle() {
        assertEquals(24_000, HistoricTopoEras.scaleOf(sheets[2]))
        assertEquals(250_000, HistoricTopoEras.scaleOf(sheets[4]))
    }
}
