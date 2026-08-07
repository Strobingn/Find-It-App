package com.example.data

import com.example.geospatial.GeoSpatialLibrary.GeoSpatialMetadata
import kotlin.math.roundToInt

/**
 * Lightweight honesty scorecard for the active elevation grid: how complete the bare-earth
 * surface is, how much canopy is present, and whether the raster is georeferenced.
 *
 * Does **not** claim metal, age, dig depth, or ownership — only surface-data quality.
 */
data class TerrainQuality(
    val validPercent: Float,
    val canopyPercent: Float,
    val crs: String,
    val datum: String,
    val isGeoreferenced: Boolean,
    val summary: String,
    val width: Int,
    val height: Int,
    val cellSizeMeters: Float,
) {
    /** One-line banner for Home / Terrain status cards. */
    fun bannerLine(): String = buildString {
        append("Ground quality · valid ${validPercent.roundToInt()}%")
        if (canopyPercent >= 1f) append(" · canopy ${canopyPercent.roundToInt()}%")
        if (isGeoreferenced) {
            append(" · georef")
        } else {
            append(" · local grid (not georeferenced)")
        }
        val shortCrs = crs.take(48)
        if (shortCrs.isNotBlank()) append(" · ").append(shortCrs)
    }

    companion object {
        /**
         * Build a scorecard from an elevation grid + metadata.
         * [summary] is the existing terrain summary string (site name / open message).
         */
        fun from(
            grid: ElevationGrid,
            crs: String,
            datum: String,
            georeferenced: Boolean,
            summary: String,
        ): TerrainQuality {
            val size = (grid.width * grid.height).coerceAtLeast(1)
            var validCount = 0
            var canopyCount = 0
            val canopyThreshold = (grid.cellSizeMeters * 0.5f).coerceAtLeast(0.5f)
            for (i in 0 until size) {
                if (grid.validData.getOrElse(i) { true }) {
                    validCount++
                    if (grid.canopySpikes.getOrElse(i) { 0f } >= canopyThreshold) {
                        canopyCount++
                    }
                }
            }
            val validPct = 100f * validCount / size
            val canopyPct = if (validCount > 0) 100f * canopyCount / validCount else 0f
            return TerrainQuality(
                validPercent = validPct,
                canopyPercent = canopyPct,
                crs = crs,
                datum = datum,
                isGeoreferenced = georeferenced,
                summary = summary,
                width = grid.width,
                height = grid.height,
                cellSizeMeters = grid.cellSizeMeters,
            )
        }

        fun from(
            grid: ElevationGrid,
            metadata: GeoSpatialMetadata,
            summary: String,
        ): TerrainQuality = from(
            grid = grid,
            crs = metadata.crs,
            datum = metadata.datum,
            georeferenced = metadata.isGeoreferenced,
            summary = summary,
        )
    }
}
