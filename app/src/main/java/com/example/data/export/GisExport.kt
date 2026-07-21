package com.example.data.export

import com.example.data.TargetSignal
import java.util.Locale

fun buildCsv(signals: List<TargetSignal>): String = buildString {
    append("ID,GridX,GridY,Latitude,Longitude,Type,Source,SignalStrength,DepthCm,Status,Notes\n")
    signals.forEach { signal ->
        val fields = listOf(
            signal.id.toString(),
            formatDecimal(signal.gridX.toDouble(), 3),
            formatDecimal(signal.gridY.toDouble(), 3),
            signal.latitude?.let { formatDecimal(it, 7) }.orEmpty(),
            signal.longitude?.let { formatDecimal(it, 7) }.orEmpty(),
            signal.metalType.label,
            signal.source.name,
            formatDecimal(signal.signalStrength.toDouble(), 1),
            signal.depthCm?.toString().orEmpty(),
            signal.status,
            signal.notes,
        )
        append(fields.joinToString(",") { csvEscape(it) }).append('\n')
    }
}

fun buildGpx(signals: List<TargetSignal>): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<gpx version=\"1.1\" creator=\"Find It\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    signals.forEach { signal ->
        val lat = signal.latitude ?: return@forEach
        val lon = signal.longitude ?: return@forEach
        append("  <wpt lat=\"").append(formatDecimal(lat, 7)).append("\" lon=\"")
            .append(formatDecimal(lon, 7)).append("\">\n")
        append("    <name>").append(xmlEscape(signal.metalType.label)).append("</name>\n")
        val description = buildString {
            append("Source: ${signal.source.name}; Strength: ${signal.signalStrength.toInt()}%; ")
            append(signal.depthCm?.let { "Depth: $it cm; " } ?: "Depth: unknown; ")
            append("Status: ${signal.status}")
            if (signal.notes.isNotBlank()) append("; Notes: ${signal.notes}")
        }
        append("    <desc>").append(xmlEscape(description)).append("</desc>\n")
        append("  </wpt>\n")
    }
    append("</gpx>\n")
}

fun buildKml(signals: List<TargetSignal>): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
    append("  <name>Find It field findings</name>\n")
    signals.forEach { signal ->
        val lat = signal.latitude ?: return@forEach
        val lon = signal.longitude ?: return@forEach
        append("  <Placemark><name>").append(xmlEscape(signal.metalType.label)).append("</name>")
        append("<description>").append(xmlEscape(signal.notes.ifBlank { signal.status })).append("</description>")
        append("<Point><coordinates>").append(formatDecimal(lon, 7)).append(',')
            .append(formatDecimal(lat, 7)).append(",0</coordinates></Point></Placemark>\n")
    }
    append("</Document></kml>\n")
}

fun buildGeoJson(signals: List<TargetSignal>): String = buildString {
    append("{\"type\":\"FeatureCollection\",\"features\":[")
    signals.filter { it.latitude != null && it.longitude != null }.forEachIndexed { index, signal ->
        if (index > 0) append(',')
        append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
        append(formatDecimal(requireNotNull(signal.longitude), 7)).append(',')
        append(formatDecimal(requireNotNull(signal.latitude), 7)).append("]},\"properties\":{")
        append("\"id\":").append(signal.id).append(',')
        append("\"type\":\"").append(jsonEscape(signal.metalType.label)).append("\",")
        append("\"source\":\"").append(jsonEscape(signal.source.name)).append("\",")
        append("\"strength\":").append(formatDecimal(signal.signalStrength.toDouble(), 1)).append(',')
        append("\"depthCm\":").append(signal.depthCm ?: "null").append(',')
        append("\"status\":\"").append(jsonEscape(signal.status)).append("\",")
        append("\"notes\":\"").append(jsonEscape(signal.notes)).append("\"}}")
    }
    append("]}")
}

private fun formatDecimal(value: Double, places: Int) =
    String.format(Locale.US, "%.${places}f", value)

private fun csvEscape(value: String) = "\"${value.replace("\"", "\"\"")}\""

private fun xmlEscape(value: String) = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun jsonEscape(value: String) = buildString {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
}
