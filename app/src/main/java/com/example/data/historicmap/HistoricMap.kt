package com.example.data.historicmap

import com.example.data.field.BoundaryVertex

/** Feature classes extracted from a georeferenced historic map. */
enum class MapFeatureType(val label: String) {
    ROAD("Road or track"),
    STRUCTURE("Structure or foundation"),
    WALL("Wall"),
    BOUNDARY("Property or survey boundary"),
}

/**
 * A historic map with its source, control points, fitted transform, and alignment quality —
 * everything needed to reproduce or correct the georeferencing later.
 */
data class GeoReferencedMap(
    val id: String,
    val terrainKey: String,
    val displayName: String,
    val imageUri: String,
    val sourceAttribution: String,
    val controlPoints: List<HistoricMapControlPoint>,
    val transform: GeoReferenceTransform?,
    val rmseMeters: Double?,
    val maxResidualMeters: Double?,
    val confidence: GeoReferenceConfidence,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    /** Low-confidence alignments must stay visibly labeled; reliable ones may guide ranking. */
    val isReliable: Boolean
        get() = confidence == GeoReferenceConfidence.GOOD ||
            confidence == GeoReferenceConfidence.FAIR

    fun withFit(fit: GeoReferenceFit, atMillis: Long): GeoReferencedMap = copy(
        controlPoints = controlPoints,
        transform = fit.transform,
        rmseMeters = fit.rmseMeters,
        maxResidualMeters = fit.maxResidualMeters,
        confidence = fit.confidence,
        updatedAtMillis = atMillis,
    )
}

/** One extracted feature (polyline/polygon as lat/lon vertices) from a historic map. */
data class HistoricMapFeature(
    val id: String,
    val mapId: String,
    val type: MapFeatureType,
    val points: List<BoundaryVertex>,
    val confidence: Float,
    val note: String,
    val createdAtMillis: Long,
)

/** Locale-independent control-point storage, matching the breadcrumb codec conventions. */
fun controlPointsToStorage(points: List<HistoricMapControlPoint>): String =
    points.joinToString(";") { point ->
        "${point.imageX},${point.imageY},${point.latitude},${point.longitude}"
    }

fun controlPointsFromStorage(value: String): List<HistoricMapControlPoint> = buildList {
    value.split(';').forEach { serializedPoint ->
        val values = serializedPoint.split(',')
        if (values.size != 4) return@forEach
        val imageX = values[0].toFloatOrNull() ?: return@forEach
        val imageY = values[1].toFloatOrNull() ?: return@forEach
        val latitude = values[2].toDoubleOrNull() ?: return@forEach
        val longitude = values[3].toDoubleOrNull() ?: return@forEach
        if (!imageX.isFinite() || !imageY.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) return@forEach
        add(HistoricMapControlPoint(imageX, imageY, latitude, longitude))
    }
}
