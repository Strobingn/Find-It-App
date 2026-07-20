package com.strobingn.findit

import com.strobingn.findit.data.export.HuntExporter
import com.strobingn.findit.data.model.FindRecord
import com.strobingn.findit.data.model.GeoPoint
import com.strobingn.findit.data.model.MetalType
import com.strobingn.findit.data.model.SearchGrid
import com.strobingn.findit.data.terrain.TerrainAnalyzer
import org.junit.Assert.assertTrue
import org.junit.Test

class HuntExporterTest {
  private val sample =
    listOf(
      FindRecord(
        title = "Buffalo nickel",
        metalType = MetalType.COIN,
        depthInches = 4.0,
        notes = "Near oak",
        photoUri = "content://photos/1",
        location = GeoPoint(40.0, -75.0),
      ),
    )

  @Test
  fun csvContainsHeaderAndPhoto() {
    val csv = HuntExporter.toCsv(sample)
    assertTrue(csv.contains("photo_uri"))
    assertTrue(csv.contains("Buffalo nickel"))
    assertTrue(csv.contains("content://photos/1"))
  }

  @Test
  fun gpxHasWaypoint() {
    val gpx = HuntExporter.toGpx(sample)
    assertTrue(gpx.contains("<wpt"))
    assertTrue(gpx.contains("lat=\"40.0\""))
  }

  @Test
  fun kmlIncludesGridPolygon() {
    val grid =
      SearchGrid(
        name = "field",
        sw = GeoPoint(40.0, -75.01),
        ne = GeoPoint(40.01, -75.0),
      )
    val kml = HuntExporter.toKml(sample, listOf(grid))
    assertTrue(kml.contains("<Polygon>"))
    assertTrue(kml.contains("field"))
  }

  @Test
  fun terrainAnalyzerProducesMaps() {
    val dem = TerrainAnalyzer.demoDem(32, 32)
    val maps = TerrainAnalyzer.analyze(dem, 32, 32)
    assertTrue(maps.hillshade.any { it > 0f })
    assertTrue(maps.skyViewFactor.all { it in 0f..1f })
    assertTrue(maps.disturbance.size == 32 * 32)
  }
}
