package com.example.data.historicmap

import com.example.data.ElevationGrid
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live alignment feedback for historic-map overlays: scores how well the ink of a scanned map
 * (roads, structures, walls, property lines) sits on terrain evidence, using
 * [MapTerrainAgreement]. The pixel path is Bitmap-free — callers pass a packed ARGB array — so
 * the whole scorer is unit-testable off-device.
 */
object HistoricMapAgreementScorer {

    /** Normalized relief-contrast evidence layer derived from the terrain grid. */
    data class EvidenceGrid(
        val width: Int,
        val height: Int,
        val values: FloatArray,
        val valid: BooleanArray,
        val supportThreshold: Float,
    ) {
        override fun equals(other: Any?): Boolean =
            other is EvidenceGrid &&
                width == other.width &&
                height == other.height &&
                supportThreshold == other.supportThreshold &&
                values.contentEquals(other.values) &&
                valid.contentEquals(other.valid)

        override fun hashCode(): Int = values.contentHashCode() * 31 + width
    }

    /**
     * Downsamples the terrain to at most [maxSide] cells per side and turns each block's
     * bare-earth relief (max minus min) into a normalized evidence value in [0, 1]. Raised,
     * uneven ground — where structures, roads, and walls leave terrain traces — scores high.
     * The support threshold is the mean plus half a standard deviation of the valid cells.
     */
    fun buildReliefEvidence(grid: ElevationGrid, maxSide: Int = 256): EvidenceGrid {
        val block = max(1, ceil(max(grid.width, grid.height).toDouble() / maxSide).toInt())
        val width = (grid.width + block - 1) / block
        val height = (grid.height + block - 1) / block
        val values = FloatArray(width * height)
        val valid = BooleanArray(width * height)
        for (blockY in 0 until height) {
            val y0 = blockY * block
            val y1 = min(grid.height, y0 + block)
            for (blockX in 0 until width) {
                val x0 = blockX * block
                val x1 = min(grid.width, x0 + block)
                var low = Float.POSITIVE_INFINITY
                var high = Float.NEGATIVE_INFINITY
                var any = false
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val index = y * grid.width + x
                        if (!grid.validData[index]) continue
                        val elevation = grid.bareEarth[index]
                        if (!elevation.isFinite()) continue
                        any = true
                        if (elevation < low) low = elevation
                        if (elevation > high) high = elevation
                    }
                }
                val out = blockY * width + blockX
                if (any) {
                    values[out] = high - low
                    valid[out] = true
                }
            }
        }
        var reliefMax = 0f
        for (index in values.indices) {
            if (valid[index] && values[index] > reliefMax) reliefMax = values[index]
        }
        if (reliefMax > 1e-6f) {
            for (index in values.indices) values[index] /= reliefMax
        }
        var sum = 0f
        var count = 0
        for (index in values.indices) {
            if (valid[index]) {
                sum += values[index]
                count++
            }
        }
        val mean = if (count > 0) sum / count else 0f
        var squared = 0f
        for (index in values.indices) {
            if (valid[index]) {
                val delta = values[index] - mean
                squared += delta * delta
            }
        }
        val std = if (count > 0) sqrt(squared / count) else 0f
        return EvidenceGrid(
            width = width,
            height = height,
            values = values,
            valid = valid,
            supportThreshold = (mean + 0.5f * std).coerceIn(0f, 1f),
        )
    }

    /**
     * Maps the dark ("ink") pixels of a scanned map through its alignment geometry into
     * evidence-grid cells. [pixels] is packed ARGB, row-major, [imageWidth] × [imageHeight].
     * Bearing follows the ground-overlay convention: clockwise degrees from north around the
     * overlay center. Pixels at or above [luminanceThreshold] (paper, sepia, white) are ignored.
     */
    fun inkCells(
        pixels: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        centerLatitude: Double,
        centerLongitude: Double,
        widthMeters: Float,
        heightMeters: Float,
        bearingDegrees: Float,
        gridMinLatitude: Double,
        gridMaxLatitude: Double,
        gridMinLongitude: Double,
        gridMaxLongitude: Double,
        evidenceWidth: Int,
        evidenceHeight: Int,
        luminanceThreshold: Float = 0.45f,
        sampleStep: Int = 2,
    ): Set<Int> {
        if (imageWidth <= 0 || imageHeight <= 0 || pixels.size < imageWidth * imageHeight) {
            return emptySet()
        }
        if (evidenceWidth <= 0 || evidenceHeight <= 0) return emptySet()
        if (gridMaxLatitude <= gridMinLatitude || gridMaxLongitude <= gridMinLongitude) {
            return emptySet()
        }
        val cells = HashSet<Int>()
        val bearing = Math.toRadians(bearingDegrees.toDouble())
        val cosBearing = cos(bearing)
        val sinBearing = sin(bearing)
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLon =
            (metersPerDegreeLat * cos(Math.toRadians(centerLatitude))).coerceAtLeast(1.0)
        val step = sampleStep.coerceAtLeast(1)
        var y = 0
        while (y < imageHeight) {
            var x = 0
            while (x < imageWidth) {
                val argb = pixels[y * imageWidth + x]
                val red = (argb shr 16) and 0xFF
                val green = (argb shr 8) and 0xFF
                val blue = argb and 0xFF
                val luminance = (0.299f * red + 0.587f * green + 0.114f * blue) / 255f
                if (luminance < luminanceThreshold) {
                    // Image-local offset from the overlay center: x grows east, y grows south.
                    val localEast = ((x + 0.5f) / imageWidth - 0.5f) * widthMeters
                    val localNorth = -(((y + 0.5f) / imageHeight - 0.5f) * heightMeters)
                    // Clockwise bearing rotation of the (east, north) offset.
                    val east = localEast * cosBearing + localNorth * sinBearing
                    val north = -localEast * sinBearing + localNorth * cosBearing
                    val latitude = centerLatitude + north / metersPerDegreeLat
                    val longitude = centerLongitude + east / metersPerDegreeLon
                    val column =
                        ((longitude - gridMinLongitude) / (gridMaxLongitude - gridMinLongitude) * evidenceWidth)
                            .toInt()
                    val row =
                        ((gridMaxLatitude - latitude) / (gridMaxLatitude - gridMinLatitude) * evidenceHeight)
                            .toInt()
                    if (column in 0 until evidenceWidth && row in 0 until evidenceHeight) {
                        cells.add(row * evidenceWidth + column)
                    }
                }
                x += step
            }
            y += step
        }
        return cells
    }

    /** Scores one aligned overlay against the terrain evidence layer. */
    fun scoreOverlay(
        pixels: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        centerLatitude: Double,
        centerLongitude: Double,
        widthMeters: Float,
        heightMeters: Float,
        bearingDegrees: Float,
        gridMinLatitude: Double,
        gridMaxLatitude: Double,
        gridMinLongitude: Double,
        gridMaxLongitude: Double,
        evidence: EvidenceGrid,
    ): MapFeatureAgreement {
        val cells = inkCells(
            pixels = pixels,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            centerLatitude = centerLatitude,
            centerLongitude = centerLongitude,
            widthMeters = widthMeters,
            heightMeters = heightMeters,
            bearingDegrees = bearingDegrees,
            gridMinLatitude = gridMinLatitude,
            gridMaxLatitude = gridMaxLatitude,
            gridMinLongitude = gridMinLongitude,
            gridMaxLongitude = gridMaxLongitude,
            evidenceWidth = evidence.width,
            evidenceHeight = evidence.height,
        )
        return MapTerrainAgreement.score(
            featureCells = cells,
            evidence = evidence.values,
            validData = evidence.valid,
            supportThreshold = evidence.supportThreshold,
        )
    }
}
