package com.example.geospatial

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small, dependency-free coordinate helpers for the terrain preview. */
object GeoSpatialLibrary {
    private const val EQUATORIAL_RADIUS = 6_378_137.0
    private const val POLAR_RADIUS = 6_356_752.314245

    data class GeographicBounds(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
    )

    data class GeoSpatialMetadata(
        val siteName: String,
        val bounds: GeographicBounds? = null,
        val crs: String = "Local grid (CRS unavailable)",
        val datum: String = "Unknown",
        val resolutionMeters: Double = 1.0,
        val columns: Int = 100,
        val rows: Int = 100,
    ) {
        val isGeoreferenced: Boolean get() = bounds != null
        val widthMeters: Double get() = resolutionMeters * columns
        val heightMeters: Double get() = resolutionMeters * rows
    }

    data class UtmCoordinate(
        val zone: Int,
        val hemisphere: Char,
        val easting: Double,
        val northing: Double,
    )

    val SITES_METADATA = listOf(
        GeoSpatialMetadata(
            siteName = "Homestead & Chimney Plot",
            bounds = GeographicBounds(43.1201, 43.1209, -124.4087, -124.4077),
            crs = "EPSG:32610 (WGS 84 / UTM zone 10N)",
            datum = "WGS 84",
            resolutionMeters = 0.5,
        ),
        GeoSpatialMetadata(
            siteName = "Civil War Fortification",
            bounds = GeographicBounds(43.1176, 43.1184, -124.4100, -124.4090),
            crs = "EPSG:32610 (WGS 84 / UTM zone 10N)",
            datum = "WGS 84",
            resolutionMeters = 0.5,
        ),
        GeoSpatialMetadata(
            siteName = "Roman Villa Complex",
            bounds = GeographicBounds(43.1221, 43.1229, -124.4065, -124.4055),
            crs = "EPSG:32610 (WGS 84 / UTM zone 10N)",
            datum = "WGS 84",
            resolutionMeters = 0.5,
        ),
    )

    fun localGrid(
        name: String,
        columns: Int,
        rows: Int,
        resolutionMeters: Double = 1.0,
    ) = GeoSpatialMetadata(
        siteName = name,
        resolutionMeters = resolutionMeters.coerceAtLeast(0.001),
        columns = columns.coerceAtLeast(1),
        rows = rows.coerceAtLeast(1),
    )

    /** Returns null when an imported grid has no declared geographic bounds. */
    fun gridToGeographic(
        xPct: Float,
        yPct: Float,
        metadata: GeoSpatialMetadata,
    ): Pair<Double, Double>? {
        val bounds = metadata.bounds ?: return null
        val x = xPct.coerceIn(0f, 100f) / 100.0
        val y = (100f - yPct.coerceIn(0f, 100f)) / 100.0
        val lat = bounds.minLat + y * (bounds.maxLat - bounds.minLat)
        val lon = bounds.minLon + x * (bounds.maxLon - bounds.minLon)
        return lat to lon
    }

    /**
     * Inverse of [gridToGeographic]: places a real-world fix onto the 0-100 grid used by the
     * terrain canvas. Returns null when the grid has no geographic bounds, or when the fix falls
     * outside the mapped area (the caller decides whether "off the edge" is worth showing).
     */
    fun geographicToGrid(
        lat: Double,
        lon: Double,
        metadata: GeoSpatialMetadata,
    ): Pair<Float, Float>? {
        val bounds = metadata.bounds ?: return null
        val latRange = bounds.maxLat - bounds.minLat
        val lonRange = bounds.maxLon - bounds.minLon
        if (latRange <= 0.0 || lonRange <= 0.0) return null
        val y = (lat - bounds.minLat) / latRange
        val x = (lon - bounds.minLon) / lonRange
        if (x !in 0.0..1.0 || y !in 0.0..1.0) return null
        return (x * 100.0).toFloat() to (100.0 - y * 100.0).toFloat()
    }

    fun calculateGeodesicDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).let { it * it }
        return 6_371_000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Projects valid WGS84 coordinates into their natural UTM zone. */
    fun geographicToUtm(lat: Double, lon: Double): UtmCoordinate {
        require(lat in -80.0..84.0) { "UTM is defined between 80°S and 84°N" }
        require(lon in -180.0..180.0) { "Longitude must be between -180 and 180" }

        val zone = ((lon + 180.0) / 6.0).toInt().coerceIn(0, 59) + 1
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val centralMeridian = Math.toRadians(zone * 6.0 - 183.0)
        val scaleFactor = 0.9996
        val eccSquared =
            (EQUATORIAL_RADIUS * EQUATORIAL_RADIUS - POLAR_RADIUS * POLAR_RADIUS) /
                (EQUATORIAL_RADIUS * EQUATORIAL_RADIUS)
        val eccPrimeSquared = eccSquared / (1.0 - eccSquared)
        val n = EQUATORIAL_RADIUS / sqrt(1.0 - eccSquared * sin(latRad) * sin(latRad))
        val t = kotlin.math.tan(latRad).let { it * it }
        val c = eccPrimeSquared * cos(latRad) * cos(latRad)
        val a = cos(latRad) * (lonRad - centralMeridian)
        val m = EQUATORIAL_RADIUS * (
            (1.0 - eccSquared / 4.0 - 3.0 * eccSquared * eccSquared / 64.0 - 5.0 * eccSquared * eccSquared * eccSquared / 256.0) * latRad -
                (3.0 * eccSquared / 8.0 + 3.0 * eccSquared * eccSquared / 32.0 + 45.0 * eccSquared * eccSquared * eccSquared / 1024.0) * sin(2.0 * latRad) +
                (15.0 * eccSquared * eccSquared / 256.0 + 45.0 * eccSquared * eccSquared * eccSquared / 1024.0) * sin(4.0 * latRad) -
                (35.0 * eccSquared * eccSquared * eccSquared / 3072.0) * sin(6.0 * latRad)
            )

        val easting = 500_000.0 + scaleFactor * n * (
            a + (1.0 - t + c) * a * a * a / 6.0 +
                (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * eccPrimeSquared) * a * a * a * a * a / 120.0
            )
        var northing = scaleFactor * (
            m + n * kotlin.math.tan(latRad) * (
                a * a / 2.0 + (5.0 - t + 9.0 * c + 4.0 * c * c) * a * a * a * a / 24.0 +
                    (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * eccPrimeSquared) * a * a * a * a * a * a / 720.0
                )
            )
        if (lat < 0) northing += 10_000_000.0
        return UtmCoordinate(zone, if (lat >= 0) 'N' else 'S', easting, northing)
    }

    fun formatDms(degrees: Double, isLatitude: Boolean): String {
        val direction = if (isLatitude) {
            if (degrees >= 0) "N" else "S"
        } else {
            if (degrees >= 0) "E" else "W"
        }
        val absolute = kotlin.math.abs(degrees)
        val whole = absolute.toInt()
        val minutes = ((absolute - whole) * 60.0).toInt()
        val seconds = (absolute - whole - minutes / 60.0) * 3600.0
        return String.format(Locale.US, "%d°%02d′%04.1f″%s", whole, minutes, seconds, direction)
    }
}
