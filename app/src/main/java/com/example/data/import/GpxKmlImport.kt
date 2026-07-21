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
        val boundaries = mutableListOf<List<SurveyPoint>>()
        var currentTrack: SurveyTrack? = null
        var currentTrackPoints = mutableListOf<SurveyPoint>()
        val boundsTracker = BoundsTracker()
        val errors = mutableListOf<String>()
        
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)
            
            var eventType = parser.eventType
            var currentTag = ""
            var currentName = ""
            var currentDescription = ""
            var currentLat: Double? = null
            var currentLon: Double? = null
            var currentEle: Double? = null
            var currentTime: String? = null
            
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        when (currentTag) {
                            "wpt" -> {
                                currentName = parser.getAttributeValue(null, "name") ?: ""
                                currentDescription = ""
                                currentLat = null
                                currentLon = null
                                currentEle = null
                                currentTime = null
                            }
                            "trk" -> {
                                currentTrack = SurveyTrack(
                                    name = parser.getAttributeValue(null, "name") ?: "",
                                    description = ""
                                )
                                currentTrackPoints = mutableListOf()
                            }
                            "trkseg" -> {}
                            "rte" -> {
                                currentTrack = SurveyTrack(
                                    name = parser.getAttributeValue(null, "name") ?: "",
                                    description = ""
                                )
                                currentTrackPoints = mutableListOf()
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text.trim()
                        if (text.isNotEmpty()) {
                            when (currentTag) {
                                "name" -> currentName = text
                                "desc" -> currentDescription += text
                                "lat" -> currentLat = text.toDoubleOrNull()
                                "lon" -> currentLon = text.toDoubleOrNull()
                                "ele" -> currentEle = text.toDoubleOrNull()
                                "time" -> currentTime = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "wpt" -> {
                                if (currentLat != null && currentLon != null) {
                                    val point = SurveyPoint(
                                        latitude = currentLat!!,
                                        longitude = currentLon!!,
                                        name = currentName,
                                        description = currentDescription,
                                        elevation = currentEle,
                                        timestamp = currentTime?.let { parseIso8601Time(it) }
                                    )
                                    waypoints.add(point)
                                    boundsTracker.update(point.latitude, point.longitude)
                                } else {
                                    errors.add("Waypoint missing coordinates")
                                }
                            }
                            "trkpt" -> {
                                if (currentLat != null && currentLon != null) {
                                    val point = SurveyPoint(
                                        latitude = currentLat!!,
                                        longitude = currentLon!!,
                                        name = currentName,
                                        description = currentDescription,
                                        elevation = currentEle,
                                        timestamp = currentTime?.let { parseIso8601Time(it) }
                                    )
                                    currentTrackPoints.add(point)
                                    boundsTracker.update(point.latitude, point.longitude)
                                }
                            }
                            "trkseg" -> {}
                            "trk" -> {
                                currentTrack?.let { track ->
                                    tracks.add(track.copy(points = currentTrackPoints.toList()))
                                }
                                currentTrack = null
                                currentTrackPoints = mutableListOf()
                            }
                            "rte" -> {
                                currentTrack?.let { track ->
                                    tracks.add(track.copy(points = currentTrackPoints.toList()))
                                }
                                currentTrack = null
                                currentTrackPoints = mutableListOf()
                            }
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
            
            // If we have track points but no track, add them as a boundary
            if (currentTrackPoints.isNotEmpty() && currentTrack == null) {
                boundaries.add(currentTrackPoints.toList())
            }
            
        } catch (e: Exception) {
            errors.add("GPX parsing error: ${e.localizedMessage}")
        }
        
        return SurveyImportResult(
            waypoints = waypoints,
            tracks = tracks,
            boundaries = boundaries,
            bounds = boundsTracker.toBoundingBox(),
            errors = errors
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
        var currentTrack: SurveyTrack? = null
        var currentTrackPoints = mutableListOf<SurveyPoint>()
        val boundsTracker = BoundsTracker()
        val errors = mutableListOf<String>()
        
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)
            
            var eventType = parser.eventType
            var inPlacemark = false
            var inLineString = false
            var inPoint = false
            var inPolygon = false
            var inMultiGeometry = false
            var currentName = ""
            var currentDescription = ""
            var currentPlacemark: SurveyPoint? = null
            var currentCoordinates: String? = null
            
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "Placemark" -> {
                                inPlacemark = true
                                currentName = parser.getAttributeValue(null, "name") ?: ""
                                currentDescription = ""
                                currentPlacemark = SurveyPoint(
                                    latitude = 0.0,
                                    longitude = 0.0,
                                    name = currentName,
                                    description = ""
                                )
                                currentCoordinates = null
                            }
                            "name" -> {
                                if (inPlacemark) {
                                    currentName = parser.nextText().trim()
                                    currentPlacemark?.let { pm ->
                                        currentPlacemark = pm.copy(name = currentName)
                                    }
                                }
                            }
                            "description" -> {
                                if (inPlacemark) {
                                    currentDescription = parser.nextText().trim()
                                    currentPlacemark?.let { pm ->
                                        currentPlacemark = pm.copy(description = currentDescription)
                                    }
                                }
                            }
                            "Point" -> {
                                inPoint = true
                                inLineString = false
                                inPolygon = false
                            }
                            "LineString" -> {
                                inLineString = true
                                inPoint = false
                                inPolygon = false
                                currentTrackPoints = mutableListOf()
                            }
                            "LinearRing" -> {
                                inPolygon = true
                                inPoint = false
                                inLineString = false
                                currentTrackPoints = mutableListOf()
                            }
                            "MultiGeometry" -> {
                                inMultiGeometry = true
                            }
                            "coordinates" -> {
                                currentCoordinates = parser.nextText().trim()
                            }
                        }
                        eventType = parser.next()
                        continue
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "Placemark" -> {
                                if (inPlacemark) {
                                    currentPlacemark?.let { pm ->
                                        if (pm.latitude != 0.0 || pm.longitude != 0.0) {
                                            waypoints.add(pm)
                                            boundsTracker.update(pm.latitude, pm.longitude)
                                        }
                                    }
                                    inPlacemark = false
                                    currentPlacemark = null
                                }
                            }
                            "Point" -> {
                                inPoint = false
                            }
                            "LineString" -> {
                                inLineString = false
                                if (currentTrackPoints.isNotEmpty()) {
                                    tracks.add(SurveyTrack(
                                        name = currentName,
                                        description = currentDescription,
                                        points = currentTrackPoints.toList()
                                    ))
                                    currentTrackPoints.forEach { boundsTracker.update(it.latitude, it.longitude) }
                                }
                            }
                            "LinearRing" -> {
                                inPolygon = false
                                if (currentTrackPoints.isNotEmpty()) {
                                    boundaries.add(currentTrackPoints.toList())
                                    currentTrackPoints.forEach { boundsTracker.update(it.latitude, it.longitude) }
                                }
                            }
                            "MultiGeometry" -> {
                                inMultiGeometry = false
                            }
                        }
                    }
                }
                
                // Process coordinates if we have them
                currentCoordinates?.let { coords ->
                    val points = parseCoordinates(coords)
                    if (inPoint && points.isNotEmpty()) {
                        val point = points[0]
                        currentPlacemark?.let { pm ->
                            currentPlacemark = pm.copy(
                                latitude = point.latitude,
                                longitude = point.longitude,
                                elevation = point.elevation
                            )
                        }
                    } else if (inLineString && points.isNotEmpty()) {
                        currentTrackPoints.addAll(points)
                    } else if (inPolygon && points.isNotEmpty()) {
                        currentTrackPoints.addAll(points)
                    }
                    currentCoordinates = null
                }
                
                eventType = parser.next()
            }
            
        } catch (e: Exception) {
            errors.add("KML parsing error: ${e.localizedMessage}")
        }
        
        return SurveyImportResult(
            waypoints = waypoints,
            tracks = tracks,
            boundaries = boundaries,
            bounds = boundsTracker.toBoundingBox(),
            errors = errors
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
