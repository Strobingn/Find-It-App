package com.example.data.historicmap

import com.example.data.field.FieldNavigation
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** One manual control point pairing an image pixel with its real-world position. */
data class HistoricMapControlPoint(
    val imageX: Float,
    val imageY: Float,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Affine image-to-world transform:
 * `longitude = a*x + b*y + c`, `latitude = d*x + e*y + f`.
 */
data class GeoReferenceTransform(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    /** Returns (latitude, longitude) for an image pixel. */
    fun imageToWorld(imageX: Float, imageY: Float): Pair<Double, Double> {
        val longitude = a * imageX + b * imageY + c
        val latitude = d * imageX + e * imageY + f
        return latitude to longitude
    }

    /** Inverse mapping; null when the affine is degenerate and cannot be inverted. */
    fun worldToImage(latitude: Double, longitude: Double): Pair<Float, Float>? {
        val determinant = a * e - b * d
        if (abs(determinant) < 1e-12) return null
        val dLon = longitude - c
        val dLat = latitude - f
        val x = (e * dLon - b * dLat) / determinant
        val y = (-d * dLon + a * dLat) / determinant
        return x.toFloat() to y.toFloat()
    }

    fun toStorage(): String = listOf(a, b, c, d, e, f).joinToString(",")

    companion object {
        fun fromStorage(value: String): GeoReferenceTransform? {
            val parts = value.split(',').map { it.toDoubleOrNull() }
            if (parts.size != 6 || parts.any { it == null || !it.isFinite() }) return null
            return GeoReferenceTransform(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!, parts[4]!!, parts[5]!!)
        }
    }
}

enum class GeoReferenceConfidence(val label: String) {
    GOOD("Good alignment"),
    FAIR("Fair alignment"),
    LOW_CONFIDENCE("Low-confidence alignment"),
    INSUFFICIENT_POINTS("Not enough usable control points"),
}

data class GeoReferenceFit(
    val transform: GeoReferenceTransform?,
    val controlPointCount: Int,
    val rmseMeters: Double?,
    val maxResidualMeters: Double?,
    val confidence: GeoReferenceConfidence,
    val note: String,
)

/**
 * Fits an image-to-world transform from manual control points. Three or more non-collinear
 * points produce a least-squares affine fit with per-point residuals in meters; exactly two
 * points produce an exact similarity fit that is always labeled low-confidence because it
 * cannot detect its own error. Collinear or duplicate points are rejected as degenerate.
 */
object GeoReferencer {
    const val GOOD_RMSE_METERS = 10.0
    const val FAIR_RMSE_METERS = 30.0
    private const val PIVOT_EPSILON = 1e-9

    fun fit(controlPoints: List<HistoricMapControlPoint>): GeoReferenceFit {
        if (controlPoints.size < 2) {
            return GeoReferenceFit(
                transform = null,
                controlPointCount = controlPoints.size,
                rmseMeters = null,
                maxResidualMeters = null,
                confidence = GeoReferenceConfidence.INSUFFICIENT_POINTS,
                note = "at least two control points are required",
            )
        }
        val transform = if (controlPoints.size == 2) {
            fitSimilarity(controlPoints[0], controlPoints[1])
        } else {
            fitAffineLeastSquares(controlPoints)
        }
        if (transform == null) {
            return GeoReferenceFit(
                transform = null,
                controlPointCount = controlPoints.size,
                rmseMeters = null,
                maxResidualMeters = null,
                confidence = GeoReferenceConfidence.INSUFFICIENT_POINTS,
                note = "control points are duplicate or collinear; spread them across the map",
            )
        }

        val residuals = controlPoints.map { point ->
            val (latitude, longitude) = transform.imageToWorld(point.imageX, point.imageY)
            FieldNavigation.distanceMeters(point.latitude, point.longitude, latitude, longitude)
        }
        val rmse = sqrt(residuals.map { it * it }.average())
        val maxResidual = residuals.max()
        val confidence = when {
            controlPoints.size == 2 -> GeoReferenceConfidence.LOW_CONFIDENCE
            rmse < GOOD_RMSE_METERS -> GeoReferenceConfidence.GOOD
            rmse < FAIR_RMSE_METERS -> GeoReferenceConfidence.FAIR
            else -> GeoReferenceConfidence.LOW_CONFIDENCE
        }
        val note = if (controlPoints.size == 2) {
            "two control points fit exactly but cannot detect error; add a third point"
        } else {
            "affine fit from ${controlPoints.size} control points"
        }
        return GeoReferenceFit(
            transform = transform,
            controlPointCount = controlPoints.size,
            rmseMeters = rmse,
            maxResidualMeters = maxResidual,
            confidence = confidence,
            note = note,
        )
    }

    /**
     * Exact uniform-scale + rotation + translation fit through two point pairs:
     * world = s·R(θ)·image + t.
     */
    private fun fitSimilarity(
        first: HistoricMapControlPoint,
        second: HistoricMapControlPoint,
    ): GeoReferenceTransform? {
        val imageDx = (second.imageX - first.imageX).toDouble()
        val imageDy = (second.imageY - first.imageY).toDouble()
        val imageLengthSquared = imageDx * imageDx + imageDy * imageDy
        if (imageLengthSquared < 1e-6) return null
        val worldDx = second.longitude - first.longitude
        val worldDy = second.latitude - first.latitude
        if (hypot(worldDx, worldDy) < 1e-12) return null

        val sCos = (imageDx * worldDx + imageDy * worldDy) / imageLengthSquared
        val sSin = (imageDx * worldDy - imageDy * worldDx) / imageLengthSquared
        val a = sCos
        val b = -sSin
        val d = sSin
        val e = sCos
        val c = first.longitude - (a * first.imageX + b * first.imageY)
        val f = first.latitude - (d * first.imageX + e * first.imageY)
        return GeoReferenceTransform(a, b, c, d, e, f)
    }

    /**
     * Least-squares affine from n ≥ 3 points. The lon/lat axes share one 3×3 normal-equation
     * system, solved by Gaussian elimination with partial pivoting; a tiny pivot means the
     * points are collinear or duplicated.
     */
    private fun fitAffineLeastSquares(
        controlPoints: List<HistoricMapControlPoint>,
    ): GeoReferenceTransform? {
        var sxx = 0.0
        var sxy = 0.0
        var sx = 0.0
        var syy = 0.0
        var sy = 0.0
        var sxLon = 0.0
        var syLon = 0.0
        var sLon = 0.0
        var sxLat = 0.0
        var syLat = 0.0
        var sLat = 0.0
        for (point in controlPoints) {
            val x = point.imageX.toDouble()
            val y = point.imageY.toDouble()
            sxx += x * x
            sxy += x * y
            sx += x
            syy += y * y
            sy += y
            sxLon += x * point.longitude
            syLon += y * point.longitude
            sLon += point.longitude
            sxLat += x * point.latitude
            syLat += y * point.latitude
            sLat += point.latitude
        }
        val n = controlPoints.size.toDouble()
        val matrix = arrayOf(
            doubleArrayOf(sxx, sxy, sx),
            doubleArrayOf(sxy, syy, sy),
            doubleArrayOf(sx, sy, n),
        )
        val lonSolution = solve3x3(matrix, doubleArrayOf(sxLon, syLon, sLon)) ?: return null
        val latSolution = solve3x3(matrix, doubleArrayOf(sxLat, syLat, sLat)) ?: return null
        return GeoReferenceTransform(
            a = lonSolution[0],
            b = lonSolution[1],
            c = lonSolution[2],
            d = latSolution[0],
            e = latSolution[1],
            f = latSolution[2],
        )
    }

    private fun solve3x3(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val augmented = Array(3) { row -> DoubleArray(4) { col -> if (col < 3) matrix[row][col] else rhs[row] } }
        for (col in 0 until 3) {
            var pivotRow = col
            for (row in col + 1 until 3) {
                if (abs(augmented[row][col]) > abs(augmented[pivotRow][col])) pivotRow = row
            }
            if (abs(augmented[pivotRow][col]) < PIVOT_EPSILON) return null
            val swap = augmented[col]
            augmented[col] = augmented[pivotRow]
            augmented[pivotRow] = swap
            for (row in 0 until 3) {
                if (row == col) continue
                val factor = augmented[row][col] / augmented[col][col]
                for (k in col until 4) {
                    augmented[row][k] -= factor * augmented[col][k]
                }
            }
        }
        return DoubleArray(3) { row -> augmented[row][3] / augmented[row][row] }
    }
}
