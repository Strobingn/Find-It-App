package com.example.data

import com.example.geospatial.GeoSpatialLibrary
import com.example.gps.crs.GridGeoreference
import com.example.gps.crs.LasCrsReader
import java.io.File

/**
 * Attaches an exact projected georeference to a LAS/LAZ terrain from the file's CRS VLR and
 * header extent, cropped exactly like [LidarRasterizer] crops a refined viewport.
 */
internal object LasGeoreferencing {
    fun attach(result: DemGenerator.TerrainLoadResult, file: File, options: LidarImportOptions): DemGenerator.TerrainLoadResult {
        if (result.geoMetadata?.projected != null) return result
        val info = runCatching { LasCrsReader.read(file) }.getOrNull() ?: return result
        val crs = info.crs ?: return result
        val rangeX = info.maxX - info.minX
        val rangeY = info.maxY - info.minY
        if (!(rangeX > 0.0) || !(rangeY > 0.0)) return result
        val focus = options.sanitized().focusBounds
        val minX = focus?.let { info.minX + it.left * rangeX } ?: info.minX
        val maxX = focus?.let { info.minX + it.right * rangeX } ?: info.maxX
        val minY = focus?.let { info.minY + (1.0 - it.bottom) * rangeY } ?: info.minY
        val maxY = focus?.let { info.minY + (1.0 - it.top) * rangeY } ?: info.maxY
        val grid = result.grid
        val georef = GridGeoreference(crs, minX, maxX, minY, maxY, grid.width, grid.height)
        val env = georef.geographicEnvelope()
        val metadata = GeoSpatialLibrary.GeoSpatialMetadata(
            siteName = result.geoMetadata?.siteName ?: file.nameWithoutExtension,
            bounds = GeoSpatialLibrary.GeographicBounds(env[0], env[1], env[2], env[3]),
            crs = crs.name + (crs.epsg?.let { " (EPSG:$it)" } ?: ""),
            datum = if (crs.frame == com.example.gps.crs.GeodeticFrame.NAD83_2011) "NAD83(2011)" else "WGS 84",
            resolutionMeters = georef.cellMeters,
            columns = grid.width,
            rows = grid.height,
            projected = georef,
        )
        return result.copy(geoMetadata = metadata)
    }
}
