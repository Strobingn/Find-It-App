package com.example.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class ExportArchiveTest {
    @Test
    fun kmzRoundTripsKmlAndSupportingFiles() {
        val kml = """<?xml version="1.0"?><kml><Document><name>Targets</name></Document></kml>"""
        val overlay = byteArrayOf(1, 2, 3, 4)

        val kmz = KmzExporter.createKmz(kml, mapOf("files/overlay.png" to overlay))

        assertEquals(kml, KmzExporter.readMainDocument(kmz))
        assertTrue(readZipArchive(kmz)["files/overlay.png"]!!.contentEquals(overlay))
    }

    @Test
    fun qgisProjectListsLayersWithDatasourcesAndCrs() {
        val qgs = QgisProjectWriter.write(
            projectName = "Cellar survey & walls",
            layers = listOf(
                QgisLayer("Terrain DEM", QgisLayerType.RASTER, "terrain.tif"),
                QgisLayer("Targets", QgisLayerType.VECTOR, "targets.shp"),
            ),
        )

        // Well-formed XML with two layer entries.
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(qgs.byteInputStream())
        val mapLayers = document.getElementsByTagName("maplayer")
        assertEquals(2, mapLayers.length)
        assertEquals("Terrain DEM", document.getElementsByTagName("layername").item(0).textContent)
        assertEquals("targets.shp", document.getElementsByTagName("datasource").item(1).textContent)
        assertEquals("gdal", document.getElementsByTagName("provider").item(0).textContent)
        assertEquals("ogr", document.getElementsByTagName("provider").item(1).textContent)
        // The ampersand in the project name must be escaped.
        assertTrue(qgs.contains("Cellar survey &amp; walls"))
    }

    @Test
    fun projectArchiveManifestRoundTrips() {
        val archive = ProjectArchiveWriter.write(
            projectName = "Ridge survey 2026",
            files = listOf(
                ProjectArchiveFile("terrain.tif", byteArrayOf(9, 9, 9)),
                ProjectArchiveFile("targets/targets.shp", byteArrayOf(1, 2)),
            ),
            createdAtMillis = 1_700_000_000_000L,
        )

        val manifest = ProjectArchiveWriter.readManifest(archive)

        assertNotNull(manifest)
        assertEquals("Ridge survey 2026", manifest!!.projectName)
        assertEquals(1_700_000_000_000L, manifest.createdAtMillis)
        assertEquals(listOf("terrain.tif", "targets/targets.shp"), manifest.filePaths)
        val entries = readZipArchive(archive)
        assertTrue(entries.containsKey(ProjectArchiveWriter.MANIFEST_PATH))
        assertTrue(entries["terrain.tif"]!!.contentEquals(byteArrayOf(9, 9, 9)))
    }

    @Test
    fun projectArchiveRejectsMalformedInput() {
        assertNull(ProjectArchiveWriter.readManifest(byteArrayOf(1, 2, 3)))
        assertNull(ProjectArchiveWriter.readManifest("not a zip".toByteArray()))
        try {
            ProjectArchiveWriter.write(
                projectName = "bad",
                files = listOf(ProjectArchiveFile(ProjectArchiveWriter.MANIFEST_PATH, byteArrayOf())),
                createdAtMillis = 0L,
            )
            fail("reserved manifest path must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
