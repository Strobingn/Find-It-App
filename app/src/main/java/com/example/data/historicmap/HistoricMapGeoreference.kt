package com.example.data.historicmap

import com.example.data.field.FieldNavigation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Converts a [GeoReferencer] fit into a ground-overlay placement (center, width, height, bearing)
 * that Google Maps can render. Image corners are projected through the affine transform; edge
 * midpoints define footprint size; the top edge defines bearing.
 */
data class HistoricMapPlacement(
    val centerLatitude: Double,
    val centerLongitude: Double,
    val widthMeters: Float,
    val heightMeters: Float,
    /** Clockwise degrees from north for the image top edge, matching GroundOverlay bearing. */
    val bearingDegrees: Float,
)

object HistoricMapGeoreference {
    fun placementFromFit(
        fit: GeoReferenceFit,
        imageWidthPx: Int,
        imageHeightPx: Int,
    ): HistoricMapPlacement? {
        val transform = fit.transform ?: return null
        if (imageWidthPx < 2 || imageHeightPx < 2) return null
        val w = imageWidthPx.toFloat()
        val h = imageHeightPx.toFloat()
        val topLeft = transform.imageToWorld(0f, 0f)
        val topRight = transform.imageToWorld(w, 0f)
        val bottomRight = transform.imageToWorld(w, h)
        val bottomLeft = transform.imageToWorld(0f, h)
        val corners = listOf(topLeft, topRight, bottomRight, bottomLeft)
        if (corners.any { (lat, lon) ->
                !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0
            }
        ) {
            return null
        }

        val centerLat = corners.map { it.first }.average()
        val centerLon = corners.map { it.second }.average()
        if (!centerLat.isFinite() || !centerLon.isFinite()) return null

        val midTop = midpoint(topLeft, topRight)
        val midBottom = midpoint(bottomLeft, bottomRight)
        val midLeft = midpoint(topLeft, bottomLeft)
        val midRight = midpoint(topRight, bottomRight)
        val widthMeters = FieldNavigation.distanceMeters(
            midLeft.first, midLeft.second, midRight.first, midRight.second,
        ).toFloat().coerceAtLeast(1f)
        val heightMeters = FieldNavigation.distanceMeters(
            midTop.first, midTop.second, midBottom.first, midBottom.second,
        ).toFloat().coerceAtLeast(1f)

        // Bearing of the image top edge: north=0, east=90 (GroundOverlay convention).
        val bearing = bearingDegrees(topLeft.first, topLeft.second, topRight.first, topRight.second)
        return HistoricMapPlacement(
            centerLatitude = centerLat,
            centerLongitude = centerLon,
            widthMeters = widthMeters,
            heightMeters = heightMeters,
            bearingDegrees = bearing,
        )
    }

    private fun midpoint(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
    ): Pair<Double, Double> = (a.first + b.first) / 2.0 to (a.second + b.second) / 2.0

    private fun bearingDegrees(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): Float {
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val degrees = Math.toDegrees(atan2(y, x))
        // Image top edge runs left→right; GroundOverlay bearing is clockwise from north for the
        // image's "up" direction, which is 90° left of left→right (counter-clockwise of edge).
        val upBearing = (degrees - 90.0 + 360.0) % 360.0
        // Maps API accepts -180..180 typically for overlays.
        return when {
            upBearing > 180.0 -> (upBearing - 360.0).toFloat()
            else -> upBearing.toFloat()
        }
    }
}
