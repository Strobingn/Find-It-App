package com.example.data.import

import com.example.data.TargetSignal
import com.example.data.MetalType
import com.example.data.DetectionSource
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.Locale

/**
 * Data class representing a waypoint or point of interest from GPX/KML files
 */
data class SurveyPoint(
    val latitude: Double,
    val longitude: Double,
    val name: String = "",
    val description: String = "",
    val elevation: Double? = null,
    val timestamp: Long? = null,
)

/**
 * Data class representing a track/route from GPX/KML files
 */
data class SurveyTrack(
    val name: String = "",
    val description: String = "",
    val points: List<SurveyPoint> = emptyList(),
)

/**
 * Result of parsing a GPX or KML file
 */
data class SurveyImportResult(
    val waypoints: List<SurveyPoint> = emptyList(),
    val tracks: List<SurveyTrack> = emptyList(),
    val boundaries: List<List<SurveyPoint>> = emptyList(),
    val bounds: BoundingBox? = null,
    val errors: List<String> = emptyList(),
)

/**
 * Simple bounding box for geographic coordinates
 */
data class BoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
) {
    fun contains(lat: Double, lon: Double): Boolean = 
        lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon
    
    fun center(): Pair<Double, Double> = 
        ((minLat + maxLat) / 2) to ((minLon + maxLon) / 2)
}

/**
 * Helper class for tracking bounds during parsing
 */
private class BoundsTracker {
    var minLat: Double = Double.POSITIVE_INFINITY
    var maxLat: Double = Double.NEGATIVE_INFINITY
    var minLon: Double = Double.POSITIVE_INFINITY
    var maxLon: Double = Double.NEGATIVE_INFINITY
    
    fun update(lat: Double, lon: Double) {
        minLat = minOf(minLat, lat)
        maxLat = maxOf(maxLat, lat)
        minLon = minOf(minLon, lon)
        maxLon = maxOf(maxLon, lon)
    }
    
    fun toBoundingBox(): BoundingBox? {
        return if (minLat != Double.POSITIVE_INFINITY) {
            BoundingBox(minLat, maxLat, minLon, maxLon)
        } else {
            null
        }
    }
}

/**
 * Parser for GPX files (GPS Exchange Format)
 */
object GpxParser {
    
    fun parse(input: InputStream): SurveyImportResult {
        val waypoints = mutableListOf<SurveyPoint>()
        val tracks = mutableListOf<SurveyTrack>()
        val boundsTracker = BoundsTracker()
        val errors = mutableListOf<String>()

        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(input, null)
            }
            var eventType = parser.eventType
            var currentTag = ""
            var collectionType: String? = null
            var collectionName = ""
            var collectionDescription = ""
            var collectionPoints = mutableListOf<SurveyPoint>()
            var pointType: String? = null
            var pointName = ""
            var pointDescription = ""
            var pointLat: Double? = null
            var pointLon: Double? = null
            var pointElevation: Double? = null
            var pointTime: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        when (currentTag) {
                            "trk", "rte" -> {
                                collectionType = currentTag
                                collectionName = ""
                                collectionDescription = ""
                                collectionPoints = mutableListOf()
                            }
                            "wpt", "trkpt", "rtept" -> {
                                pointType = currentTag
                                pointName = ""
                                pointDescription = ""
                                pointLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                                pointLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                                pointElevation = null
                                pointTime = null
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val value = parser.text.trim()
                        if (value.isNotEmpty()) {
                            if (pointType != null) {
                                when (currentTag) {
                                    "name" -> pointName += value
                                    "desc", "cmt" -> pointDescription += value
                                    "ele" -> pointElevation = value.toDoubleOrNull()
                                    "time" -> pointTime = value
                                }
                            } else if (collectionType != null) {
                                when (currentTag) {
                                    "name" -> collectionName += value
                                    "desc" -> collectionDescription += value
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "wpt", "trkpt", "rtept" -> {
                                val latitude = pointLat
                                val longitude = pointLon
                                if (
                                    latitude == null ||
                                    longitude == null ||
                                    !latitude.isFinite() ||
                                    !longitude.isFinite() ||
                                    latitude !in -90.0..90.0 ||
                                    longitude !in -180.0..180.0
                                ) {
                                    errors.add("${parser.name} missing valid coordinates")
                                } else {
                                    val point = SurveyPoint(
                                        latitude = latitude,
                                        longitude = longitude,
                                        name = pointName,
                                        description = pointDescription,
                                        elevation = pointElevation?.takeIf { it.isFinite() },
                                        timestamp = pointTime?.let(::parseIso8601Time),
                                    )
                                    if (parser.name == "wpt") waypoints.add(point) else collectionPoints.add(point)
                                    boundsTracker.update(latitude, longitude)
                                }
                                pointType = null
                            }
                            "trk", "rte" -> {
                                if (collectionPoints.isNotEmpty()) {
                                    tracks.add(
                                        SurveyTrack(
                                            name = collectionName,
                                            description = collectionDescription,
                                            points = collectionPoints.toList(),
                                        ),
                                    )
                                }
                                collectionType = null
                                collectionPoints = mutableListOf()
                            }
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (exception: Exception) {
            errors.add("GPX parsing error: ${exception.localizedMessage ?: exception.javaClass.simpleName}")
        }

        return SurveyImportResult(
            waypoints = waypoints,
            tracks = tracks,
            bounds = boundsTracker.toBoundingBox(),
            errors = errors,
        )
    }
    private fun parseIso8601Time(timeString: String): Long? {
        return try {
            // Try parsing ISO 8601 format
            // Common formats: yyyy-MM-dd'T'HH:mm:ss'Z' or yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
            val cleaned = timeString.replace("T", " ").replace("Z", "")
            val parts = cleaned.split(" ", ":", "-", ".")
            if (parts.size >= 6) {
                val year = parts[0].toInt()
                val month = parts[1].toInt() - 1
                val day = parts[2].toInt()
                val hour = parts[3].toInt()
                val minute = parts[4].toInt()
                val second = parts.getOrNull(5)?.toInt() ?: 0
                
                val calendar = java.util.Calendar.getInstance()
                calendar.set(year, month, day, hour, minute, second)
                calendar.timeInMillis
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Parser for KML files (Keyhole Markup Language)
 */
object KmlParser {
    
    fun parse(input: InputStream): SurveyImportResult {
        val waypoints = mutableListOf<SurveyPoint>()
        val tracks = mutableListOf<SurveyTrack>()
        val boundaries = mutableListOf<List<SurveyPoint>>()
        val boundsTracker = BoundsTracker()
        val errors = mutableListOf<String>()

        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(input, null)
            }
            var eventType = parser.eventType
            var currentTag = ""
            val text = StringBuilder()
            var placemarkName = ""
            var placemarkDescription = ""
            var geometryPoints = emptyList<SurveyPoint>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.substringAfter(':')
                        when (currentTag) {
                            "Placemark" -> {
                                placemarkName = ""
                                placemarkDescription = ""
                            }
                            "Point", "LineString", "LinearRing" -> {
                                geometryPoints = emptyList()
                            }
                            "name", "description", "coordinates" -> text.setLength(0)
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        if (currentTag in setOf("name", "description", "coordinates")) {
                            text.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name.substringAfter(':')) {
                            "name" -> placemarkName = text.toString().trim()
                            "description" -> placemarkDescription = text.toString().trim()
                            "coordinates" -> geometryPoints = parseCoordinates(text.toString())
                            "Point" -> {
                                geometryPoints.firstOrNull()?.let { raw ->
                                    val point = raw.copy(name = placemarkName, description = placemarkDescription)
                                    waypoints.add(point)
                                    boundsTracker.update(point.latitude, point.longitude)
                                }
                            }
                            "LineString" -> {
                                if (geometryPoints.isNotEmpty()) {
                                    val namedPoints = geometryPoints.map { it.copy(description = placemarkDescription) }
                                    tracks.add(SurveyTrack(placemarkName, placemarkDescription, namedPoints))
                                    namedPoints.forEach { boundsTracker.update(it.latitude, it.longitude) }
                                }
                            }
                            "LinearRing" -> {
                                if (geometryPoints.isNotEmpty()) {
                                    boundaries.add(geometryPoints)
                                    geometryPoints.forEach { boundsTracker.update(it.latitude, it.longitude) }
                                }
                            }
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (exception: Exception) {
            errors.add("KML parsing error: ${exception.localizedMessage ?: exception.javaClass.simpleName}")
        }

        return SurveyImportResult(
            waypoints = waypoints,
            tracks = tracks,
            boundaries = boundaries,
            bounds = boundsTracker.toBoundingBox(),
            errors = errors,
        )
    }
    private fun parseCoordinates(coords: String): List<SurveyPoint> {
        val result = mutableListOf<SurveyPoint>()
        val coordinateTuples = coords.trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }

        for (tuple in coordinateTuples) {
            val parts = tuple.split(',')
            val longitude = parts.getOrNull(0)?.toDoubleOrNull()
            val latitude = parts.getOrNull(1)?.toDoubleOrNull()
            val elevation = parts.getOrNull(2)?.toDoubleOrNull()
                ?.takeIf { it.isFinite() }
            if (
                longitude == null ||
                latitude == null ||
                !longitude.isFinite() ||
                !latitude.isFinite() ||
                longitude !in -180.0..180.0 ||
                latitude !in -90.0..90.0
            ) {
                continue
            }
            result.add(
                SurveyPoint(
                    latitude = latitude,
                    longitude = longitude,
                    elevation = elevation,
                ),
            )
        }

        return result
    }
}

/**
 * Converts survey points to TargetSignal objects for logging
 */
fun SurveyPoint.toTargetSignal(
    metalType: MetalType = MetalType.MANUAL_MARKER,
    signalStrength: Float = 0f,
    depthCm: Int? = null,
    source: DetectionSource = DetectionSource.MANUAL,
    status: String = "Imported",
    notes: String = description.ifBlank { name }
): TargetSignal {
    return TargetSignal(
        gridX = 0f, // Will be calculated based on terrain bounds
        gridY = 0f,
        metalType = metalType,
        signalStrength = signalStrength,
        depthCm = depthCm,
        latitude = latitude,
        longitude = longitude,
        source = source,
        timestamp = timestamp ?: System.currentTimeMillis(),
        notes = notes,
        status = status
    )
}

/**
 * Detects if a file is GPX or KML based on its content
 */
fun detectFileType(content: String): String? {
    return when {
        content.contains("<?xml") && content.contains("gpx") -> "gpx"
        content.contains("<?xml") && content.contains("kml") -> "kml"
        else -> null
    }
}

/**
 * Detects if a file is GPX or KML based on its extension
 */
fun detectFileTypeByExtension(filename: String): String? {
    val ext = filename.substringAfterLast('.', "").lowercase(Locale.US)
    return when (ext) {
        "gpx" -> "gpx"
        "kml" -> "kml"
        else -> null
    }
}
