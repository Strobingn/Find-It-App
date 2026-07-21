package com.example.geospatial

import java.util.Locale

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A geo-spatial projection and distance calculation engine designed for LiDAR and DEM terrain layers.
 * Supports WGS84, UTM Zone 10N (EPSG:32610), and Haversine geodesic distance computations.
 */
object GeoSpatialLibrary {

    // WGS-84 Ellipsoid Constants
    private const val EQUATORIAL_RADIUS = 6378137.0
    private const val POLAR_RADIUS = 6356752.3142
    private const val FLATTENING = 1.0 / 298.257223563

    /**
     * Bounding box metadata for a geospatial terrain data layer.
     */
    data class GeoSpatialMetadata(
        val siteName: String,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
        val crs: String = "EPSG:32610 (UTM Zone 10N)",
        val datum: String = "NAD83",
        val resolutionMeters: Double = 0.5 // 50cm per pixel
    ) {
        val widthMeters: Double
            get() = resolutionMeters * 100.0 // Grid is 100x100
        val heightMeters: Double
            get() = resolutionMeters * 100.0
    }

    // Default sites mapped to Southern Oregon Coast (Bandon / Coos Bay region)
    val SITES_METADATA = listOf(
        GeoSpatialMetadata(
            siteName = "Homestead & Chimney Plot",
            minLat = 43.1201,
            maxLat = 43.1209,
            minLon = -124.4087,
            maxLon = -124.4077,
            resolutionMeters = 0.5
        ),
        GeoSpatialMetadata(
            siteName = "Civil War Fortification",
            minLat = 43.1176,
            maxLat = 43.1184,
            minLon = -124.4100,
            maxLon = -124.4090,
            resolutionMeters = 0.5
        ),
        GeoSpatialMetadata(
            siteName = "Roman Villa Complex",
            minLat = 43.1221,
            maxLat = 43.1229,
            minLon = -124.4065,
            maxLon = -124.4055,
            resolutionMeters = 0.5
        ),
        // Custom loaded layers default to Bandon Oregon Lidar index (ID 10206)
        GeoSpatialMetadata(
            siteName = "Custom Imported Layer",
            minLat = 43.1150,
            maxLat = 43.1165,
            minLon = -124.4150,
            maxLon = -124.4135,
            resolutionMeters = 0.8
        )
    )

    /**
     * Interpolates geographic coordinates (Lat, Lon) from grid percent coordinates (0-100).
     */
    fun gridToGeographic(
        xPct: Float,
        yPct: Float,
        metadata: GeoSpatialMetadata
    ): Pair<Double, Double> {
        val lat = metadata.minLat + ((100f - yPct) / 100f) * (metadata.maxLat - metadata.minLat)
        val lon = metadata.minLon + (xPct / 100f) * (metadata.maxLon - metadata.minLon)
        return Pair(lat, lon)
    }

    /**
     * Computes the great-circle distance between two geographic coordinates using the Haversine formula.
     */
    fun calculateGeodesicDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val r = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * Projects WGS84 Latitude/Longitude to Transverse Mercator (UTM Zone 10N) Easting and Northing.
     */
    fun geographicToUtmZone10(lat: Double, lon: Double): Pair<Double, Double> {
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)

        val utmZone = 10
        val centralMeridian = Math.toRadians((utmZone * 6 - 183).toDouble())

        val scaleFactor = 0.9996
        val falseEasting = 500000.0
        val falseNorthing = 0.0

        val eccSquared = (EQUATORIAL_RADIUS * EQUATORIAL_RADIUS - POLAR_RADIUS * POLAR_RADIUS) / (EQUATORIAL_RADIUS * EQUATORIAL_RADIUS)
        val eccPrimeSquared = eccSquared / (1.0 - eccSquared)

        val n = EQUATORIAL_RADIUS / sqrt(1.0 - eccSquared * sin(latRad) * sin(latRad))
        val t = Math.tan(latRad) * Math.tan(latRad)
        val c = eccPrimeSquared * cos(latRad) * cos(latRad)
        val a2 = cos(latRad) * (lonRad - centralMeridian)

        val m = EQUATORIAL_RADIUS * (
                (1.0 - eccSquared / 4.0 - 3.0 * eccSquared * eccSquared / 64.0 - 5.0 * eccSquared * eccSquared * eccSquared / 256.0) * latRad -
                (3.0 * eccSquared / 8.0 + 3.0 * eccSquared * eccSquared / 32.0 + 45.0 * eccSquared * eccSquared * eccSquared / 1024.0) * sin(2.0 * latRad) +
                (15.0 * eccSquared * eccSquared / 256.0 + 45.0 * eccSquared * eccSquared * eccSquared / 1024.0) * sin(4.0 * latRad) -
                (35.0 * eccSquared * eccSquared * eccSquared / 3072.0) * sin(6.0 * latRad)
                )

        val easting = falseEasting + scaleFactor * n * (
                a2 +
                (1.0 - t + c) * a2 * a2 * a2 / 6.0 +
                (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * eccPrimeSquared) * a2 * a2 * a2 * a2 * a2 / 120.0
                )

        val northing = falseNorthing + scaleFactor * (
                m + n * Math.tan(latRad) * (
                        a2 * a2 / 2.0 +
                        (5.0 - t + 9.0 * c + 4.0 * c * c) * a2 * a2 * a2 * a2 / 24.0 +
                        (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * eccPrimeSquared) * a2 * a2 * a2 * a2 * a2 * a2 / 720.0
                        )
                )

        return Pair(easting, northing)
    }

    /**
     * Formats a coordinate into clean GPS/DMS (Degrees, Minutes, Seconds) presentation.
     */
    fun formatDMS(deg: Double, isLatitude: Boolean): String {
        val direction = if (isLatitude) {
            if (deg >= 0) "N" else "S"
        } else {
            if (deg >= 0) "E" else "W"
        }
        val absDeg = Math.abs(deg)
        val d = absDeg.toInt()
        val m = ((absDeg - d) * 60.0).toInt()
        val s = (absDeg - d - m / 60.0) * 3600.0
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", d, m, s, direction)
    }
}
