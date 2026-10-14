package com.example.gps.crs

/**
 * Exact placement of geographic positions on a raster whose cells are axis-aligned in a
 * projected CRS (LiDAR grids). Uses the rasterizer's cell mapping: column = floor((E − minX) ×
 * (width − 1) / (maxX − minX)), row 0 at maxY. Percentages are of the drawn image (0–100).
 */
data class GridGeoreference(
    val crs: ProjectedCrs,
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    val width: Int,
    val height: Int,
) {
    private val rangeX = (maxX - minX).coerceAtLeast(1e-9)
    private val rangeY = (maxY - minY).coerceAtLeast(1e-9)

    /** Ground size of one cell in metres. */
    val cellMeters: Double get() = rangeX / (width - 1).coerceAtLeast(1) * crs.unitToMeters

    /**
     * [frame]/[epochYear] describe the fix: phone GNSS is ITRF at the current epoch; NYSNet RTK
     * is already NAD83(2011). Returns null outside the grid.
     */
    fun toGridPercent(latDeg: Double, lonDeg: Double, frame: GeodeticFrame, epochYear: Double): Pair<Float, Float>? {
        val (lat, lon) = DatumShift.convert(latDeg, lonDeg, frame, crs.frame, epochYear)
        val (e, n) = crs.project(lat, lon)
        if (!e.isFinite() || !n.isFinite() || e !in minX..maxX || n !in minY..maxY) return null
        val x = (e - minX) / rangeX * (width - 1) / width * 100.0
        val y = (maxY - n) / rangeY * (height - 1) / height * 100.0
        if (!x.isFinite() || !y.isFinite() || x !in 0.0..100.0 || y !in 0.0..100.0) return null
        return x.toFloat() to y.toFloat()
    }

    /** Inverse of [toGridPercent], returned in the requested [frame]. */
    fun gridPercentToGeographic(xPct: Double, yPct: Double, frame: GeodeticFrame, epochYear: Double): Pair<Double, Double> {
        val e = minX + xPct / 100.0 * width / (width - 1).coerceAtLeast(1) * rangeX
        val n = maxY - yPct / 100.0 * height / (height - 1).coerceAtLeast(1) * rangeY
        val (lat, lon) = crs.unproject(e, n)
        return DatumShift.convert(lat, lon, crs.frame, frame, epochYear)
    }

    /** Geographic envelope (CRS datum) sampled along the edges, for basemaps and catalog lookups. */
    fun geographicEnvelope(): DoubleArray {
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        for (i in 0..8) {
            val f = i / 8.0
            for ((e, n) in listOf(minX + f * rangeX to minY, minX + f * rangeX to maxY, minX to minY + f * rangeY, maxX to minY + f * rangeY)) {
                val (lat, lon) = crs.unproject(e, n)
                minLat = minOf(minLat, lat); maxLat = maxOf(maxLat, lat)
                minLon = minOf(minLon, lon); maxLon = maxOf(maxLon, lon)
            }
        }
        return doubleArrayOf(minLat, maxLat, minLon, maxLon)
    }
}
