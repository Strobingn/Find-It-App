package com.example.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenTopographyCatalogTest {
    private val fixture = javaClass.classLoader!!.getResource("opentopo/otcatalog_ny.json")!!.readText()
    private val box = doubleArrayOf(-73.79, 42.64, -73.77, 42.66)

    @Test
    fun parsesUsgs3depIdsFromRealNyCatalogResponse() {
        val ids = OpenTopographyCatalog.parseUsgs3depIds(fixture)
        assertTrue(ids.contains("NY_FEMAR2_Central_4_2018"))
        assertTrue(ids.contains("NY_Columbia_Rensselaer_2016"))
        assertFalse(ids.any { it.isBlank() })
    }

    @Test
    fun urlCarriesBoxAndKeyOnlyWhenSet() {
        val noKey = OpenTopographyCatalog.catalogUrl(box)
        assertTrue(noKey.startsWith(OpenTopographyCatalog.ENDPOINT))
        assertTrue(noKey.contains("minx=-73.79000") && noKey.contains("maxy=42.66000"))
        assertTrue(noKey.contains("include_federated=true"))
        assertFalse(noKey.contains("API_Key"))
        assertTrue(OpenTopographyCatalog.catalogUrl(box, "k").endsWith("&API_Key=k"))
    }

    @Test
    fun cachesAndSurvivesFailures() {
        var calls = 0
        val cat = OpenTopographyCatalog(get = { calls++; fixture })
        cat.usgs3depResources(box); cat.usgs3depResources(box)
        assertEquals(1, calls)
        assertTrue(OpenTopographyCatalog(get = { null }).usgs3depResources(box).isEmpty())
        assertTrue(OpenTopographyCatalog(get = { throw RuntimeException("x") }).usgs3depResources(box).isEmpty())
    }

    @Test
    fun geographicBoxOfUtmTileIsInNewYork() {
        val b = EptTileBuilder.geographicBox(EptTileBuilder.Utm(18), 517_500.0, 4_650_000.0, 518_500.0, 4_651_000.0)
        assertTrue(b[0] < b[2] && b[1] < b[3])
        assertEquals(-74.79, b[0], 0.05)
        assertEquals(42.0, b[1], 0.1)
    }
}
