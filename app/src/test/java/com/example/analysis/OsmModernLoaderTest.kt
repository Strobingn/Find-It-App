package com.example.analysis

import com.example.geospatial.GeoSpatialLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OsmModernLoaderTest {
    @Test
    fun parsesRoadsAndBuildingsIntoNormalizedPercents() {
        val bounds = GeoSpatialLibrary.GeographicBounds(43.0, 43.2, -75.5, -75.2)
        val body = """
            {"elements":[
              {"tags":{"highway":"primary"},"center":{"lat":43.1,"lon":-75.35}},
              {"tags":{"building":"yes"},"center":{"lat":43.05,"lon":-75.25}}
            ]}
        """.trimIndent()
        val context = OsmModernLoader.parse(body, bounds)
        assertEquals(2, context.features.size)
        assertTrue(context.hitsModern(50f, 50f, 8f))
    }
}
