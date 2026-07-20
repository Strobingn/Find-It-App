package com.strobingn.findit.data.export

import com.strobingn.findit.data.model.FindRecord
import com.strobingn.findit.data.model.SearchGrid
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Export finds/grids to formats hunters actually use (roadmap #9):
 * GPX, KML, and CSV (with photo path column).
 */
object HuntExporter {

  private val isoUtc: SimpleDateFormat
    get() =
      SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
      }

  fun toCsv(finds: List<FindRecord>): String {
    val header =
      "id,title,metal_type,depth_in,lat,lng,elev_m,notes,photo_uri,detected_at_utc"
    val rows =
      finds.joinToString("\n") { f ->
        listOf(
            f.id,
            csv(f.title),
            f.metalType.name,
            f.depthInches?.toString().orEmpty(),
            f.location.lat.toString(),
            f.location.lng.toString(),
            f.location.elevM?.toString().orEmpty(),
            csv(f.notes),
            csv(f.photoUri.orEmpty()),
            isoUtc.format(Date(f.detectedAtEpochMs)),
          )
          .joinToString(",")
      }
    return "$header\n$rows\n"
  }

  fun toGpx(finds: List<FindRecord>, name: String = "Find It hunt"): String {
    val wpts =
      finds.joinToString("\n") { f ->
        val elev = f.location.elevM?.let { "<ele>$it</ele>" }.orEmpty()
        val time = isoUtc.format(Date(f.detectedAtEpochMs))
        """
        |  <wpt lat="${f.location.lat}" lon="${f.location.lng}">
        |    $elev
        |    <time>$time</time>
        |    <name>${xml(f.title)}</name>
        |    <desc>${xml("${f.metalType} depth=${f.depthInches ?: "?"}in ${f.notes}")}</desc>
        |    <type>${f.metalType}</type>
        |  </wpt>
        """
          .trimMargin()
      }
    return """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<gpx version="1.1" creator="Find It" xmlns="http://www.topografix.com/GPX/1/1">
      |  <metadata><name>${xml(name)}</name></metadata>
      |$wpts
      |</gpx>
      """
      .trimMargin()
  }

  fun toKml(finds: List<FindRecord>, grids: List<SearchGrid> = emptyList(), name: String = "Find It hunt"): String {
    val findPlacemarks =
      finds.joinToString("\n") { f ->
        """
        |    <Placemark>
        |      <name>${xml(f.title)}</name>
        |      <description>${xml("${f.metalType} ${f.depthInches ?: "?"}in\n${f.notes}\nphoto=${f.photoUri.orEmpty()}")}</description>
        |      <Point><coordinates>${f.location.lng},${f.location.lat},${f.location.elevM ?: 0.0}</coordinates></Point>
        |    </Placemark>
        """
          .trimMargin()
      }
    val gridPlacemarks =
      grids.joinToString("\n") { g ->
        val ring =
          listOf(
              "${g.sw.lng},${g.sw.lat},0",
              "${g.ne.lng},${g.sw.lat},0",
              "${g.ne.lng},${g.ne.lat},0",
              "${g.sw.lng},${g.ne.lat},0",
              "${g.sw.lng},${g.sw.lat},0",
            )
            .joinToString(" ")
        """
        |    <Placemark>
        |      <name>${xml(g.name)}</name>
        |      <description>cell=${g.cellSizeMeters}m covered=${g.coveredCellIds.size}</description>
        |      <Polygon><outerBoundaryIs><LinearRing><coordinates>$ring</coordinates></LinearRing></outerBoundaryIs></Polygon>
        |    </Placemark>
        """
          .trimMargin()
      }
    return """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<kml xmlns="http://www.opengis.net/kml/2.2">
      |  <Document>
      |    <name>${xml(name)}</name>
      |$findPlacemarks
      |$gridPlacemarks
      |  </Document>
      |</kml>
      """
      .trimMargin()
  }

  private fun csv(s: String): String {
    val needs = s.contains(',') || s.contains('"') || s.contains('\n')
    val escaped = s.replace("\"", "\"\"")
    return if (needs) "\"$escaped\"" else escaped
  }

  private fun xml(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
}
