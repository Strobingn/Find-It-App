package com.example.data.field

/**
 * A survey/project boundary polygon tied to a terrain project. Used to keep targets,
 * observations, and navigation inside the permitted search area and to label out-of-bounds work.
 */
data class BoundaryVertex(
    val latitude: Double,
    val longitude: Double,
)

data class SurveyBoundary(
    val id: String,
    val terrainKey: String,
    val displayName: String,
    val vertices: List<BoundaryVertex>,
    val createdAtMillis: Long,
) {
    /** Ray-casting containment in lon/lat space; a boundary needs at least three vertices. */
    fun contains(latitude: Double, longitude: Double): Boolean {
        if (vertices.size < 3) return false
        var inside = false
        var previous = vertices.size - 1
        for (current in vertices.indices) {
            val currentVertex = vertices[current]
            val previousVertex = vertices[previous]
            val straddles = (currentVertex.latitude > latitude) != (previousVertex.latitude > latitude)
            if (straddles) {
                val fraction = (latitude - previousVertex.latitude) /
                    (currentVertex.latitude - previousVertex.latitude)
                val crossingLongitude = previousVertex.longitude +
                    fraction * (currentVertex.longitude - previousVertex.longitude)
                if (longitude < crossingLongitude) inside = !inside
            }
            previous = current
        }
        return inside
    }
}

/** Compact, locale-independent vertex storage, matching the breadcrumb codec conventions. */
internal fun boundaryVerticesToStorage(vertices: List<BoundaryVertex>): String =
    vertices.joinToString(";") { vertex -> "${vertex.latitude},${vertex.longitude}" }

internal fun SurveyBoundary.verticesToStorage(): String = boundaryVerticesToStorage(vertices)

internal fun boundaryVerticesFromStorage(value: String): List<BoundaryVertex> = buildList {
    value.split(';').forEach { serializedVertex ->
        val values = serializedVertex.split(',')
        if (values.size != 2) return@forEach
        val latitude = values[0].toDoubleOrNull() ?: return@forEach
        val longitude = values[1].toDoubleOrNull() ?: return@forEach
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@forEach
        add(BoundaryVertex(latitude, longitude))
    }
}
