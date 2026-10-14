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
        if (!listOf(left, top, right, bottom).all { it.isFinite() }) return Full
        val safeLeft = left.coerceIn(0.0, 1.0 - 1e-9)
        val safeTop = top.coerceIn(0.0, 1.0 - 1e-9)
        return copy(
            left = safeLeft,
            top = safeTop,
            right = right.coerceIn(safeLeft + 1e-9, 1.0),
            bottom = bottom.coerceIn(safeTop + 1e-9, 1.0),
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
    val rasterResolution: Int = DEFAULT_OVERVIEW_RESOLUTION,
    /** Optional post-raster smoothing; zero preserves the smallest earthworks. */
    val smoothingRadius: Int = 0,
    /** Optional normalized viewport to re-rasterize from the original point cloud. */
    val focusBounds: NormalizedRasterBounds? = null,
    /** When non-null and non-empty, only these ASPRS classes contribute to elevation binning. */
    val allowedClasses: Set<Int>? = null,
    /** Anchored tile grids specify both node counts; ordinary imports derive their shape. */
    val fixedWidth: Int? = null,
    val fixedHeight: Int? = null,
) {
    fun sanitized(): LidarImportOptions {
        val sanitizedFocus = focusBounds?.sanitized()
        val maxResolution = if (sanitizedFocus == null) {
            // First paint is a full detailed overview — never a 256 px stub.
            MAX_OVERVIEW_RESOLUTION
        } else {
            MAX_REFINED_RESOLUTION
        }
        // Empty set is treated as "no filter" so UI "clear" never leaves a silent full-block.
        val classes = allowedClasses?.takeIf { it.isNotEmpty() }
        return copy(
            rasterResolution = rasterResolution.coerceIn(MIN_RASTER_RESOLUTION, maxResolution),
            smoothingRadius = smoothingRadius.coerceIn(0, 4),
            fixedWidth = fixedWidth?.coerceIn(2, maxResolution),
            fixedHeight = fixedHeight?.coerceIn(2, maxResolution),
            focusBounds = sanitizedFocus,
            allowedClasses = classes,
        )
    }

    companion object {
        internal const val MIN_RASTER_RESOLUTION = 128
        /** Default full-footprint open: detailed hillshade on first paint. */
        internal const val DEFAULT_OVERVIEW_RESOLUTION = 1_024
        internal const val MAX_OVERVIEW_RESOLUTION = 1_536
        internal const val MAX_REFINED_RESOLUTION = 2_048
    }
}
