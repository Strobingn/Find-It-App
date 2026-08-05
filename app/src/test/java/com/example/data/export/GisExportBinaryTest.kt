package com.example.data.export

import com.example.data.DetectionSource
import com.example.data.MetalType
import com.example.data.TargetSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GisExportBinaryTest {

    private fun signal(id: Long, latitude: Double?, longitude: Double?) = TargetSignal(
        id = id,
        gridX = 10f,
        gridY = 20f,
        metalType = MetalType.IRON,
        signalStrength = 72.5f,
        depthCm = 15,
        latitude = latitude,
        longitude = longitude,
        source = DetectionSource.MANUAL,
        notes = "near the wall",
        status = "Logged",
    )

    @Test
    fun shapefileZipContainsShpShxDbfForGeoreferencedSignals() {
        val signals = listOf(
            signal(1L, 41.4, -74.0),
            signal(2L, 41.401, -74.002),
            signal(3L, null, null), // skipped, matching the GPX/KML/GeoJSON behavior
        )
        val entries = readZipArchive(buildShapefileZip(signals))
        assertEquals(
            setOf("find-it-targets.shp", "find-it-targets.shx", "find-it-targets.dbf"),
            entries.keys,
        )
        // .shp: 100-byte header + 2 point records of (8-byte record header + 20-byte content).
        assertEquals(100 + 2 * 28, entries.getValue("find-it-targets.shp").size)
        // .shx: 100-byte header + one 8-byte index entry per point.
        assertEquals(100 + 2 * 8, entries.getValue("find-it-targets.shx").size)
        // .dbf: 32-byte header + 6 field descriptors + terminator + 2 records + EOF marker.
        val recordLength = 1 + 24 + 12 + 6 + 6 + 24 + 80
        assertEquals(32 + 6 * 32 + 1 + 2 * recordLength + 1, entries.getValue("find-it-targets.dbf").size)
    }

    @Test
    fun shapefileZipWithoutCoordinatesStillProducesValidEmptyBundle() {
        val entries = readZipArchive(buildShapefileZip(listOf(signal(1L, null, null))))
        assertEquals(100, entries.getValue("find-it-targets.shp").size)
        assertEquals(100, entries.getValue("find-it-targets.shx").size)
    }

    @Test
    fun kmzRoundTripsTheKmlDocument() {
        val kmz = buildKmz(listOf(signal(1L, 41.4, -74.0), signal(2L, null, null)))
        val kml = KmzExporter.readMainDocument(kmz)
        assertNotNull(kml)
        assertTrue(kml!!.contains("<kml"))
        assertTrue(kml.contains("-74.0000000"))
        assertTrue(kml.contains("41.4000000"))
        // The un-georeferenced signal must not appear, matching buildKml.
        assertTrue(kml.contains("<Placemark>").not() || kml.split("<Placemark>").size == 2)
    }
}
