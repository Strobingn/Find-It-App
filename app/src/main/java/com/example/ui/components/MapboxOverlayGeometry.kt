package com.example.ui.components

import com.example.data.field.BreadcrumbTrack
import com.example.data.field.FieldWaypoint
import com.example.data.survey.SurveyFeature
import com.example.data.survey.SurveyGeometryType
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure geometry helpers for the Mapbox map tab: no map or Android dependencies, so they are
 * unit-testable on the JVM.
 */
internal object MapboxOverlayGeometry {
    private const val METERS_PER_DEGREE_LAT = 111_320.0

    /**
     * Corners of a terrain overlay for a Mapbox image source, in Mapbox order
     * (top-left, top-right, bottom-right, bottom-left), each as `[longitude, latitude]`.
     *
     * Matches Google's GroundOverlay placement used on the Google map tab: the image is centred on
     * [centerLat]/[centerLon], sized in meters, and rotated clockwise from north by [bearingDegrees].
     */
    fun overlayCorners(
        centerLat: Double,
        centerLon: Double,
        widthMeters: Double,
        heightMeters: Double,
        bearingDegrees: Double,
    ): List<List<Double>> {
        val halfWidth = widthMeters.coerceAtLeast(1.0) / 2.0
        val halfHeight = heightMeters.coerceAtLeast(1.0) / 2.0
        val theta = Math.toRadians(bearingDegrees)
        val cosT = cos(theta)
        val sinT = sin(theta)
        val metersPerLonDegree = (METERS_PER_DEGREE_LAT * cos(Math.toRadians(centerLat))).coerceAtLeast(10_000.0)
        // (east, north) offsets of each corner before rotation.
        val local = listOf(
            -halfWidth to halfHeight,
            halfWidth to halfHeight,
            halfWidth to -halfHeight,
            -halfWidth to -halfHeight,
        )
        return local.map { (east, north) ->
            // Clockwise rotation: a north-pointing vector turns toward east as bearing grows.
            val rotatedEast = east * cosT + north * sinT
            val rotatedNorth = -east * sinT + north * cosT
            listOf(
                centerLon + rotatedEast / metersPerLonDegree,
                centerLat + rotatedNorth / METERS_PER_DEGREE_LAT,
            )
        }
    }

    fun surveyPoints(features: List<SurveyFeature>): FeatureCollection =
        FeatureCollection.fromFeatures(
            features.filter { it.geometryType == SurveyGeometryType.POINT }.flatMap { feature ->
                feature.coordinates.map { coordinate ->
                    Feature.fromGeometry(Point.fromLngLat(coordinate.longitude, coordinate.latitude)).also {
                        feature.name?.let { name -> it.addStringProperty("name", name) }
                    }
                }
            },
        )

    fun surveyLines(features: List<SurveyFeature>): FeatureCollection =
        FeatureCollection.fromFeatures(
            features.filter { it.geometryType == SurveyGeometryType.LINE && it.coordinates.size >= 2 }.map { feature ->
                Feature.fromGeometry(
                    LineString.fromLngLats(feature.coordinates.map { Point.fromLngLat(it.longitude, it.latitude) }),
                )
            },
        )

    fun surveyPolygons(features: List<SurveyFeature>): FeatureCollection =
        FeatureCollection.fromFeatures(
            features.filter { it.geometryType == SurveyGeometryType.POLYGON && it.coordinates.size >= 3 }.map { feature ->
                val ring = feature.coordinates.map { Point.fromLngLat(it.longitude, it.latitude) }.toMutableList()
                if (ring.first() != ring.last()) ring += ring.first()
                Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
            },
        )

    fun breadcrumbLines(tracks: List<BreadcrumbTrack>): FeatureCollection =
        FeatureCollection.fromFeatures(
            tracks.filter { it.points.size >= 2 }.map { track ->
                Feature.fromGeometry(
                    LineString.fromLngLats(track.points.map { Point.fromLngLat(it.longitude, it.latitude) }),
                )
            },
        )

    fun routeLine(waypoints: List<FieldWaypoint>): FeatureCollection =
        FeatureCollection.fromFeatures(
            if (waypoints.size >= 2) {
                listOf(
                    Feature.fromGeometry(
                        LineString.fromLngLats(waypoints.map { Point.fromLngLat(it.longitude, it.latitude) }),
                    ),
                )
            } else {
                emptyList()
            },
        )

    fun routeStops(waypoints: List<FieldWaypoint>): FeatureCollection =
        FeatureCollection.fromFeatures(
            waypoints.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) },
        )
}
