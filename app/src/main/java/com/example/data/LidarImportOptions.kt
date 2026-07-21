package com.example.data

/**
 * Non-destructive ways to turn LAS/LAZ returns into a terrain raster.
 * The source point cloud is never rewritten or reclassified in place.
 */
enum class GroundSurfaceMode {
    /** Prefer ASPRS ground classes (2 and legacy model key-points), with an automatic fallback. */
    SOURCE_CLASSIFIED,

    /** Estimate a ground surface from the lowest valid return in each raster cell. */
    AUTO_LOWEST,

    /** Render the highest return in each cell as a surface model (trees and structures included). */
    SURFACE_MODEL,
}

data class LidarImportOptions(
    val groundMode: GroundSurfaceMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
    /** Maximum raster dimension. The shorter side preserves the source footprint aspect ratio. */
    val rasterResolution: Int = 320,
    /** Optional post-raster smoothing; zero preserves the smallest earthworks. */
    val smoothingRadius: Int = 0,
) {
    fun sanitized(): LidarImportOptions = copy(
        rasterResolution = rasterResolution.coerceIn(128, 512),
        smoothingRadius = smoothingRadius.coerceIn(0, 2),
    )
}
