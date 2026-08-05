package com.example.data

import com.example.geospatial.GeoSpatialLibrary
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A user-selected geographic search area for public LiDAR discovery.
 *
 * The catalog is queried with [bounds] because the public services accept envelopes. Returned
 * tiles are then tested against the actual selection so a circular or polygonal search does not
 * accidentally include every tile in its enclosing rectangle.
 */
sealed interface LidarAreaSelection {
    val bounds: GeoSpatialLibrary.GeographicBounds

    /** Human-readable geometry retained with the logical project for field and recovery context. */
    fun projectAreaDescription(): String

    fun intersects(tileBounds: GeoSpatialLibrary.GeographicBounds): Boolean

    data class Rectangle(
        override val bounds: GeoSpatialLibrary.GeographicBounds,
    ) : LidarAreaSelection {
        override fun intersects(tileBounds: GeoSpatialLibrary.GeographicBounds): Boolean =
            tileBounds.maxLon >= bounds.minLon && tileBounds.minLon <= bounds.maxLon &&
                tileBounds.maxLat >= bounds.minLat && tileBounds.minLat <= bounds.maxLat

        override fun projectAreaDescription(): String =
            "Rectangle: west ${bounds.minLon}, south ${bounds.minLat}, east ${bounds.maxLon}, north ${bounds.maxLat}"
    }

    data class Radius(
        val center: Point,
        val radiusMeters: Double,
    ) : LidarAreaSelection {
        init {
            require(radiusMeters.isFinite() && radiusMeters > 0.0) { "Radius must be greater than zero" }
            require(radiusMeters <= MAX_RADIUS_METERS) { "Radius must not exceed 100 km" }
        }

        override val bounds: GeoSpatialLibrary.GeographicBounds = radiusBounds(center, radiusMeters)

        override fun intersects(tileBounds: GeoSpatialLibrary.GeographicBounds): Boolean {
            val closestLatitude = center.latitude.coerceIn(tileBounds.minLat, tileBounds.maxLat)
            val closestLongitude = center.longitude.coerceIn(tileBounds.minLon, tileBounds.maxLon)
            return distanceMeters(center, Point(closestLatitude, closestLongitude)) <= radiusMeters
        }

        override fun projectAreaDescription(): String =
            "Radius: ${radiusMeters} m around ${center.latitude}, ${center.longitude}"
    }

    data class Polygon(
        val vertices: List<Point>,
    ) : LidarAreaSelection {
        init {
            require(vertices.size >= 3) { "A polygon needs at least three vertices" }
            require(abs(signedArea(vertices)) > MIN_POLYGON_AREA_DEGREES_SQUARED) {
                "Polygon vertices must enclose an area"
            }
            require(!hasSelfIntersection(vertices)) { "Polygon edges cannot cross" }
        }

        override val bounds: GeoSpatialLibrary.GeographicBounds = polygonBounds(vertices)

        override fun intersects(tileBounds: GeoSpatialLibrary.GeographicBounds): Boolean {
            val rectangle = listOf(
                Point(tileBounds.minLat, tileBounds.minLon),
                Point(tileBounds.minLat, tileBounds.maxLon),
                Point(tileBounds.maxLat, tileBounds.maxLon),
                Point(tileBounds.maxLat, tileBounds.minLon),
            )
            if (vertices.any { pointInRectangle(it, tileBounds) }) return true
            if (rectangle.any { pointInPolygon(it, vertices) }) return true
            val polygonEdges = vertices.zipWithNext() + (vertices.last() to vertices.first())
            val rectangleEdges = rectangle.zipWithNext() + (rectangle.last() to rectangle.first())
            return polygonEdges.any { polygonEdge ->
                rectangleEdges.any { rectangleEdge -> segmentsIntersect(polygonEdge, rectangleEdge) }
            }
        }

        override fun projectAreaDescription(): String =
            "Polygon: " + vertices.joinToString(separator = "; ") { "${it.latitude}, ${it.longitude}" }
    }

    data class Point(
        val latitude: Double,
        val longitude: Double,
    ) {
        init {
            require(latitude.isFinite() && latitude in -90.0..90.0) { "Latitude must be between -90 and 90" }
            require(longitude.isFinite() && longitude in -180.0..180.0) { "Longitude must be between -180 and 180" }
        }
    }

    companion object {
        private const val METERS_PER_DEGREE = 111_320.0
        private const val MAX_RADIUS_METERS = 100_000.0
        private const val MIN_POLYGON_AREA_DEGREES_SQUARED = 1e-12

        private fun radiusBounds(center: Point, radiusMeters: Double): GeoSpatialLibrary.GeographicBounds {
            val latitudeDelta = radiusMeters / METERS_PER_DEGREE
            val longitudeScale = max(0.01, cos(Math.toRadians(center.latitude)))
            val longitudeDelta = radiusMeters / (METERS_PER_DEGREE * longitudeScale)
            return GeoSpatialLibrary.GeographicBounds(
                minLat = (center.latitude - latitudeDelta).coerceAtLeast(-90.0),
                maxLat = (center.latitude + latitudeDelta).coerceAtMost(90.0),
                minLon = (center.longitude - longitudeDelta).coerceAtLeast(-180.0),
                maxLon = (center.longitude + longitudeDelta).coerceAtMost(180.0),
            )
        }

        private fun polygonBounds(vertices: List<Point>): GeoSpatialLibrary.GeographicBounds =
            GeoSpatialLibrary.GeographicBounds(
                minLat = vertices.minOf(Point::latitude),
                maxLat = vertices.maxOf(Point::latitude),
                minLon = vertices.minOf(Point::longitude),
                maxLon = vertices.maxOf(Point::longitude),
            )

        private fun pointInRectangle(point: Point, bounds: GeoSpatialLibrary.GeographicBounds): Boolean =
            point.latitude in bounds.minLat..bounds.maxLat && point.longitude in bounds.minLon..bounds.maxLon

        private fun pointInPolygon(point: Point, vertices: List<Point>): Boolean {
            var inside = false
            var previous = vertices.last()
            vertices.forEach { current ->
                if (pointOnSegment(point, previous, current)) return true
                val crossesLatitude = (current.latitude > point.latitude) != (previous.latitude > point.latitude)
                if (crossesLatitude) {
                    val crossingLongitude = (previous.longitude - current.longitude) *
                        (point.latitude - current.latitude) /
                        (previous.latitude - current.latitude) + current.longitude
                    if (point.longitude < crossingLongitude) inside = !inside
                }
                previous = current
            }
            return inside
        }

        private fun segmentsIntersect(first: Pair<Point, Point>, second: Pair<Point, Point>): Boolean {
            val a = orientation(first.first, first.second, second.first)
            val b = orientation(first.first, first.second, second.second)
            val c = orientation(second.first, second.second, first.first)
            val d = orientation(second.first, second.second, first.second)
            if (a == 0 && pointOnSegment(second.first, first.first, first.second)) return true
            if (b == 0 && pointOnSegment(second.second, first.first, first.second)) return true
            if (c == 0 && pointOnSegment(first.first, second.first, second.second)) return true
            if (d == 0 && pointOnSegment(first.second, second.first, second.second)) return true
            return (a > 0) != (b > 0) && (c > 0) != (d > 0)
        }

        private fun orientation(first: Point, second: Point, third: Point): Int {
            val cross = (second.longitude - first.longitude) * (third.latitude - first.latitude) -
                (second.latitude - first.latitude) * (third.longitude - first.longitude)
            return when {
                abs(cross) < 1e-12 -> 0
                cross > 0.0 -> 1
                else -> -1
            }
        }

        private fun pointOnSegment(point: Point, start: Point, end: Point): Boolean =
            abs((end.longitude - start.longitude) * (point.latitude - start.latitude) -
                (end.latitude - start.latitude) * (point.longitude - start.longitude)) < 1e-12 &&
                point.latitude in min(start.latitude, end.latitude)..max(start.latitude, end.latitude) &&
                point.longitude in min(start.longitude, end.longitude)..max(start.longitude, end.longitude)

        private fun signedArea(vertices: List<Point>): Double {
            val pairs = vertices.zipWithNext() + (vertices.last() to vertices.first())
            return pairs.sumOf { (first, second) ->
                first.longitude * second.latitude - second.longitude * first.latitude
            } / 2.0
        }

        private fun hasSelfIntersection(vertices: List<Point>): Boolean {
            val edges = vertices.zipWithNext() + (vertices.last() to vertices.first())
            return edges.indices.any { firstIndex ->
                edges.indices.any { secondIndex ->
                    if (secondIndex <= firstIndex ||
                        secondIndex == firstIndex + 1 ||
                        (firstIndex == 0 && secondIndex == edges.lastIndex)
                    ) {
                        false
                    } else {
                        segmentsIntersect(edges[firstIndex], edges[secondIndex])
                    }
                }
            }
        }

        private fun distanceMeters(first: Point, second: Point): Double {
            val meanLatitudeRadians = Math.toRadians((first.latitude + second.latitude) / 2.0)
            val dx = (first.longitude - second.longitude) * METERS_PER_DEGREE * cos(meanLatitudeRadians)
            val dy = (first.latitude - second.latitude) * METERS_PER_DEGREE
            return sqrt(dx * dx + dy * dy)
        }
    }
}
