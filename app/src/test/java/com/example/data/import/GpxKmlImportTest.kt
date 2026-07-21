package com.example.data.import

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GpxKmlImportTest {
    @Test
    fun gpxImportsWaypointAndEveryTrackPointFromCoordinateAttributes() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test">
              <wpt lat="42.0" lon="-71.0"><name>Camp</name><ele>10.5</ele></wpt>
              <trk><name>Survey line</name><trkseg>
                <trkpt lat="42.1" lon="-71.1"><ele>11</ele></trkpt>
                <trkpt lat="42.2" lon="-71.2"><ele>12</ele></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val result = GpxParser.parse(ByteArrayInputStream(xml.toByteArray()))

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(1, result.waypoints.size)
        assertEquals("Camp", result.waypoints.single().name)
        assertEquals(1, result.tracks.size)
        assertEquals("Survey line", result.tracks.single().name)
        assertEquals(2, result.tracks.single().points.size)
        assertEquals(-71.2, result.bounds?.minLon ?: Double.NaN, 0.000_001)
        assertEquals(42.2, result.bounds?.maxLat ?: Double.NaN, 0.000_001)
    }

    @Test
    fun kmlImportsEveryWhitespaceSeparatedCoordinateTuple() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark>
              <name>Boundary</name><LineString><coordinates>
                -71.0,42.0,5 -71.1,42.1,6
                -71.2,42.2,7
              </coordinates></LineString>
            </Placemark></Document></kml>
        """.trimIndent()

        val result = KmlParser.parse(ByteArrayInputStream(xml.toByteArray()))

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(1, result.tracks.size)
        assertEquals(3, result.tracks.single().points.size)
        assertEquals(-71.2, result.tracks.single().points.last().longitude, 0.000_001)
        assertEquals(7.0, result.tracks.single().points.last().elevation ?: Double.NaN, 0.000_001)
    }
}