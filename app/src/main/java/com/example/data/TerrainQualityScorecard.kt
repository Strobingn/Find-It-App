package com.example.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Compact ground/CRS quality summary for the active elevation grid.
 *
 * Used by the Terrain workspace banner (valid coverage, canopy spikes, cell density,
 * footprint, and georeference status) so operators can trust or distrust a LAZ surface
 * before spending field time on it.
 */
data class TerrainQualityScorecard(
    val gridWidth: Int,
    val gridHeight: Int,
    val cellSizeMeters: Float,
    val validCellFraction: Float, // 0..1 from validData
    val canopySpikeFraction: Float, // fraction of valid cells with meaningful canopy
    val footprintWidthMeters: Float,
    val footprintHeightMeters: Float,
    val crs: String,
    val datum: String,
    val georeferenced: Boolean,
    val terrainSummary: String,
) {
    /** Approximate cell density (cells per square meter). */
    val densityCellsPerM2: Float
        get() {
            val cell = cellSizeMeters
            if (!cell.isFinite() || cell <= 0f) return 0f
            return 1f / (cell * cell)
        }

    /** Short one-liner for the Terrain tab footer banner. */
    fun bannerLine(): String {
        val crsShort = shortCrs(crs)
        val georef = if (georeferenced) "georef yes" else "georef no"
        val cell = formatMeters(cellSizeMeters)
        val footW = formatMeters(footprintWidthMeters)
        val footH = formatMeters(footprintHeightMeters)
        val validPct = (validCellFraction.coerceIn(0f, 1f) * 100f).toInt()
        val canopyPct = (canopySpikeFraction.coerceIn(0f, 1f) * 100f).toInt()
        return String.format(
            Locale.US,
            "%s · %s · %s · %s×%s · valid %d%% · canopy %d%%",
            crsShort,
            georef,
            cell,
            footW,
            footH,
            validPct,
            canopyPct,
        )
    }

    /** Multi-line detail for a quality card / export. */
    fun scorecardLines(): List<String> {
        val density = if (densityCellsPerM2 > 0f) {
            String.format(Locale.US, "%.3f cells/m²", densityCellsPerM2)
        } else {
            "n/a"
        }
        return listOf(
            "Grid: ${gridWidth}×${gridHeight}",
            "Cell size: ${formatMeters(cellSizeMeters)}",
            "Footprint: ${formatMeters(footprintWidthMeters)} × ${formatMeters(footprintHeightMeters)}",
            "Valid cells: ${String.format(Locale.US, "%.1f%%", validCellFraction.coerceIn(0f, 1f) * 100f)}",
            "Canopy spikes: ${String.format(Locale.US, "%.1f%%", canopySpikeFraction.coerceIn(0f, 1f) * 100f)}",
            "Density: $density",
            "CRS: $crs",
            "Datum: $datum",
            "Georeferenced: ${if (georeferenced) "yes" else "no"}",
            "Summary: ${terrainSummary.ifBlank { "—" }}",
        )
    }

    companion object {
        private fun shortCrs(crs: String): String {
            val trimmed = crs.trim().ifBlank { "CRS?" }
            val paren = trimmed.indexOf('(')
            val base = if (paren > 0) trimmed.substring(0, paren).trim() else trimmed
            return base.take(28).ifBlank { "CRS?" }
        }

        private fun formatMeters(meters: Float): String {
            if (!meters.isFinite() || meters < 0f) return "—"
            return when {
                meters < 1f -> String.format(Locale.US, "%.2f m", meters)
                meters < 10f -> String.format(Locale.US, "%.1f m", meters)
                else -> String.format(Locale.US, "%.0f m", meters)
            }
        }
    }
}

object TerrainQuality {
    /**
     * Build a [TerrainQualityScorecard] from an elevation grid plus geo metadata fields.
     *
     * Valid fraction uses [ElevationGrid.validData]. Canopy fraction is the share of valid
     * cells whose canopy spike exceeds ~2% of cell size (floored at 1 m for the threshold base).
     */
    fun from(
        grid: ElevationGrid,
        crs: String,
        datum: String,
        georeferenced: Boolean,
        summary: String,
    ): TerrainQualityScorecard {
        val width = grid.width
        val height = grid.height
        val cell = grid.cellSizeMeters.let { if (it.isFinite() && it > 0f) it else 1f }
        val total = width * height
        val validMask = grid.validData
        val canopy = grid.canopySpikes
        val spikeThreshold = 0.02f * max(1f, cell)

        var validCount = 0
        var canopySpikeCount = 0
        val limit = minOf(total, validMask.size, canopy.size)
        for (i in 0 until limit) {
            if (!validMask[i]) continue
            validCount++
            if (abs(canopy[i]) > spikeThreshold) {
                canopySpikeCount++
            }
        }

        val validFraction = if (total > 0) validCount.toFloat() / total.toFloat() else 0f
        val canopyFraction = if (validCount > 0) {
            canopySpikeCount.toFloat() / validCount.toFloat()
        } else {
            0f
        }

        val footprintW = (width - 1).coerceAtLeast(1) * cell
        val footprintH = (height - 1).coerceAtLeast(1) * cell

        return TerrainQualityScorecard(
            gridWidth = width,
            gridHeight = height,
            cellSizeMeters = cell,
            validCellFraction = validFraction.coerceIn(0f, 1f),
            canopySpikeFraction = canopyFraction.coerceIn(0f, 1f),
            footprintWidthMeters = footprintW,
            footprintHeightMeters = footprintH,
            crs = crs,
            datum = datum,
            georeferenced = georeferenced,
            terrainSummary = summary,
        )
    }
}
