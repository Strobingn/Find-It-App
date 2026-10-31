package com.example.thermals

import com.example.data.ElevationGrid as TerrainRaster
import com.example.geospatial.GeoSpatialLibrary
import kotlin.math.floor

/**
 * On-device elevation at a lat/lon, in meters. Null means this source doesn't cover the point.
 * Stand search prefers this over Open-Meteo when the center sits inside a loaded site.
 */
fun interface SiteDem {
    fun elevationMeters(latitude: Double, longitude: Double): Double?
}

object SiteDemGrids {
    /** Fewer hits than this and the footprint is too small to search — use Open-Meteo. */
    const val MIN_HITS = 25

    /**
     * Builds the stand grid from [site] when the center cell is covered and at least [MIN_HITS]
     * samples land on the DEM. Uncovered cells are marked so they are not scored.
     */
    fun fromSamples(centerLat: Double, centerLon: Double, site: SiteDem): ElevationGrid? {
        val points = ElevationGrid.samplePoints(
            centerLat,
            centerLon,
            StandFinder.GRID_SIZE,
            StandFinder.GRID_SPACING_M,
        )
        val values = points.map { site.elevationMeters(it.first, it.second) }
        val center = (StandFinder.GRID_SIZE / 2) * StandFinder.GRID_SIZE + StandFinder.GRID_SIZE / 2
        if (values.getOrNull(center) == null) return null
        if (values.count { it != null } < MIN_HITS) return null
        val covered = BooleanArray(values.size) { values[it] != null }
        // A hole is not sea level. 0 m here would be scored as ground if the mask were skipped.
        val meters = DoubleArray(values.size) { values[it] ?: Double.NaN }
        return ElevationGrid(centerLat, centerLon, StandFinder.GRID_SIZE, StandFinder.GRID_SPACING_M, meters, covered)
    }
}

/**
 * Samples a loaded LiDAR / site raster. Row 0 is north, matching the hillshade bitmap
 * (geographic y% 0 is the north edge). Bare earth only — canopy is not the ground the wind sees.
 * Heights are the raster's stored values (same as hillshade). The grid has no vertical-unit
 * field, so a foot-valued LAS is not rescaled here.
 */
object LidarStandDem {
    fun from(raster: TerrainRaster, metadata: GeoSpatialLibrary.GeoSpatialMetadata): SiteDem? {
        if (!metadata.isGeoreferenced || raster.width < 8 || raster.height < 8) return null
        return SiteDem { lat, lon ->
            sample(lat, lon, metadata, raster.width, raster.height) { col, row ->
                val index = row * raster.width + col
                if (index !in raster.validData.indices || !raster.validData[index]) {
                    null
                } else {
                    raster.getElevationAt(col, row, vegetationFilter = 1f).toDouble()
                }
            }
        }
    }

    /** Bilinear meters, or null outside the footprint or on a no-data cell. */
    internal fun sample(
        lat: Double,
        lon: Double,
        metadata: GeoSpatialLibrary.GeoSpatialMetadata,
        width: Int,
        height: Int,
        at: (col: Int, row: Int) -> Double?,
    ): Double? {
        if (width < 2 || height < 2) return null
        val pct = GeoSpatialLibrary.geographicToGrid(lat, lon, metadata) ?: return null
        val colF = pct.first / 100.0 * (width - 1)
        val rowF = pct.second / 100.0 * (height - 1)
        val c0 = floor(colF).toInt().coerceIn(0, width - 2)
        val r0 = floor(rowF).toInt().coerceIn(0, height - 2)
        val fc = (colF - c0).coerceIn(0.0, 1.0)
        val fr = (rowF - r0).coerceIn(0.0, 1.0)
        val z00 = at(c0, r0) ?: return null
        val z10 = at(c0 + 1, r0) ?: return null
        val z01 = at(c0, r0 + 1) ?: return null
        val z11 = at(c0 + 1, r0 + 1) ?: return null
        val north = z00 * (1 - fc) + z10 * fc
        val south = z01 * (1 - fc) + z11 * fc
        return north * (1 - fr) + south * fr
    }
}
