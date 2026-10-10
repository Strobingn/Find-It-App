package com.example.data.historicmap

import kotlin.math.max

/**
 * Fits non-georeferenced scans (LOC / Sanborn) by pairing image control features
 * (road intersections, streams) with DEM/OSM counterparts. Residual error widens
 * the map's match radius; a poorly aligned map can only add a little confidence.
 */
object AutoGeoreferencer {
    const val BASE_MATCH_RADIUS_METERS = 50.0

    data class ControlPair(
        val imageX: Float,
        val imageY: Float,
        val latitude: Double,
        val longitude: Double,
    )

    data class Result(
        val fit: GeoReferenceFit,
        val matchRadiusMeters: Double,
        val confidenceBoostScale: Float,
    )

    fun georeference(pairs: List<ControlPair>): Result {
        val points = pairs.map {
            HistoricMapControlPoint(it.imageX, it.imageY, it.latitude, it.longitude)
        }
        val fit = GeoReferencer.fit(points)
        val rmse = fit.rmseMeters ?: Double.POSITIVE_INFINITY
        val radius = BASE_MATCH_RADIUS_METERS + (if (rmse.isFinite()) rmse else 80.0)
        val scale = when (fit.confidence) {
            GeoReferenceConfidence.GOOD -> 1f
            GeoReferenceConfidence.FAIR -> 0.55f
            GeoReferenceConfidence.LOW_CONFIDENCE -> 0.22f
            GeoReferenceConfidence.INSUFFICIENT_POINTS -> 0.08f
        }
        return Result(
            fit = fit,
            matchRadiusMeters = radius,
            confidenceBoostScale = scale,
        )
    }

    fun boostForMatch(
        residualRmseMeters: Double?,
        georeferenced: Boolean,
        distanceMeters: Double,
        matchRadiusMeters: Double,
    ): Float {
        if (distanceMeters > matchRadiusMeters) return 0f
        val closeness = (1.0 - distanceMeters / max(matchRadiusMeters, 1.0)).toFloat().coerceIn(0f, 1f)
        val quality = when {
            georeferenced && (residualRmseMeters == null || residualRmseMeters <= 12.0) -> 1f
            georeferenced -> 0.45f
            residualRmseMeters != null && residualRmseMeters <= 20.0 -> 0.28f
            else -> 0.12f
        }
        // Poorly aligned scans may raise score a little, never confirm a find by themselves.
        return closeness * quality * 0.18f
    }
}
