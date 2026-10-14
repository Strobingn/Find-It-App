package com.example.gps.crs

/** A projected CRS: datum frame + projection + horizontal linear unit. */
data class ProjectedCrs(
    val name: String,
    val frame: GeodeticFrame,
    val projection: Projection,
    /** Metres per CRS unit: 1, 0.3048 (international ft) or 1200/3937 (US survey ft). */
    val unitToMeters: Double,
    val epsg: Int? = null,
    /** True when the datum is NAD83 but not explicitly the 2011 realization (≤ ~1 m older shifts). */
    val nad83RealizationAssumed: Boolean = false,
) {
    fun project(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val (e, n) = projection.forward(latDeg, lonDeg)
        return (e / unitToMeters) to (n / unitToMeters)
    }

    fun unproject(easting: Double, northing: Double): Pair<Double, Double> =
        projection.inverse(easting * unitToMeters, northing * unitToMeters)

    companion object {
        const val US_SURVEY_FOOT = 1200.0 / 3937.0
        const val INTERNATIONAL_FOOT = 0.3048

        private fun utm(zone: Int, north: Boolean, frame: GeodeticFrame, epsg: Int, label: String) = ProjectedCrs(
            name = "$label / UTM zone $zone${if (north) "N" else "S"}",
            frame = frame,
            projection = TransverseMercator(
                if (frame == GeodeticFrame.ITRF) Ellipsoid.WGS84 else Ellipsoid.GRS80,
                0.0, zone * 6.0 - 183.0, 0.9996, 500_000.0, if (north) 0.0 else 10_000_000.0,
            ),
            unitToMeters = 1.0,
            epsg = epsg,
            nad83RealizationAssumed = label == "NAD83",
        )

        private fun nyStatePlane(code: Int): ProjectedCrs? {
            val us = code in 2260..2263
            val zone = if (us) code - 2260 else code - 32115
            if (zone !in 0..3) return null
            val unit = if (us) US_SURVEY_FOOT else 1.0
            val names = listOf("New York East", "New York Central", "New York West", "New York Long Island")
            val projection = when (zone) {
                0 -> TransverseMercator(Ellipsoid.GRS80, 38.0 + 50.0 / 60.0, -74.5, 0.9999, 150_000.0, 0.0)
                1 -> TransverseMercator(Ellipsoid.GRS80, 40.0, -(76.0 + 35.0 / 60.0), 0.9999375, 250_000.0, 0.0)
                2 -> TransverseMercator(Ellipsoid.GRS80, 40.0, -(78.0 + 35.0 / 60.0), 0.9999375, 350_000.0, 0.0)
                else -> LambertConformalConic(
                    Ellipsoid.GRS80, 40.0 + 10.0 / 60.0, -74.0, 41.0 + 2.0 / 60.0, 40.0 + 40.0 / 60.0, 1.0, 300_000.0, 0.0,
                )
            }
            return ProjectedCrs("NAD83 / ${names[zone]}${if (us) " (ftUS)" else ""}", GeodeticFrame.NAD83_2011, projection, unit, code, true)
        }

        /** Built-in EPSG codes common for US LiDAR delivered with GeoKeys instead of WKT. */
        fun fromEpsg(code: Int): ProjectedCrs? = when (code) {
            in 26901..26923 -> utm(code - 26900, true, GeodeticFrame.NAD83_2011, code, "NAD83")
            in 6330..6348 -> utm(code - 6329, true, GeodeticFrame.NAD83_2011, code, "NAD83(2011)")
            in 32601..32660 -> utm(code - 32600, true, GeodeticFrame.ITRF, code, "WGS 84")
            in 32701..32760 -> utm(code - 32700, false, GeodeticFrame.ITRF, code, "WGS 84")
            in 2260..2263, in 32115..32118 -> nyStatePlane(code)
            else -> null
        }
    }
}
