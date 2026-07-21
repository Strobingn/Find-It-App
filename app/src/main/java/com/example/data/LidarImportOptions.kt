package com.example.data

/**
 * Non-destructive ways to turn LAS/LAZ returns into a terrain raster.
 * The source point cloud is never rewritten or reclassified in place.
 */
data class NormalizedRasterBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    fun sanitized(): NormalizedRasterBounds {
        val safeLeft = left.coerceIn(0.0, 0.999)
        val safeTop = top.coerceIn(0.0, 0.999)
        return copy(
            left = safeLeft,
            top = safeTop,
            right = right.coerceIn(safeLeft + 0.001, 1.0),
            bottom = bottom.coerceIn(safeTop + 0.001, 1.0),
        )
    }

    fun inside(parent: NormalizedRasterBounds): NormalizedRasterBounds {
        val child = sanitized()
        val outer = parent.sanitized()
        val width = outer.right - outer.left
        val height = outer.bottom - outer.top
        return NormalizedRasterBounds(
            left = outer.left + child.left * width,
            top = outer.top + child.top * height,
            right = outer.left + child.right * width,
            bottom = outer.top + child.bottom * height,
        ).sanitized()
    }

    companion object {
        val Full = NormalizedRasterBounds(0.0, 0.0, 1.0, 1.0)
    }
}

data class TerrainImportSource(
    val uri: String,
    val displayName: String,
    val options: LidarImportOptions,
)

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
    /** Optional normalized viewport to re-rasterize from the original point cloud. */
    val focusBounds: NormalizedRasterBounds? = null,
) {
    fun sanitized(): LidarImportOptions = copy(
        rasterResolution = rasterResolution.coerceIn(128, 1_024),
        smoothingRadius = smoothingRadius.coerceIn(0, 4),
        focusBounds = focusBounds?.sanitized(),
    )
}
