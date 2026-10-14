package com.example.gps.crs

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** CRS and XY extent read straight from a LAS/LAZ header and its (E)VLRs. */
data class LasCrsInfo(
    val crs: ProjectedCrs?,
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    /** Why [crs] is null, for UI diagnostics. */
    val problem: String? = null,
)

object LasCrsReader {
    private const val PROJECTION_USER = "LASF_Projection"
    private const val WKT_RECORD = 2112
    private const val GEOKEY_RECORD = 34735

    fun read(file: File): LasCrsInfo? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(minOf(375L, raf.length()).toInt())
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.US_ASCII) != "LASF") return null
            val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val minor = head[25].toInt()
            val headerSize = b.getShort(94).toInt() and 0xFFFF
            val vlrCount = b.getInt(100).toLong() and 0xFFFFFFFFL
            val maxX = b.getDouble(179)
            val minX = b.getDouble(187)
            val maxY = b.getDouble(195)
            val minY = b.getDouble(203)
            var wkt: String? = null
            var geoKeys: ByteArray? = null
            var offset = headerSize.toLong()
            repeat(vlrCount.coerceAtMost(1000).toInt()) {
                val vh = ByteArray(54)
                raf.seek(offset); raf.readFully(vh)
                val bb = ByteBuffer.wrap(vh).order(ByteOrder.LITTLE_ENDIAN)
                val user = String(vh, 2, 16, Charsets.US_ASCII).trimEnd('\u0000')
                val record = bb.getShort(18).toInt() and 0xFFFF
                val length = bb.getShort(20).toInt() and 0xFFFF
                if (user == PROJECTION_USER && (record == WKT_RECORD || record == GEOKEY_RECORD)) {
                    val data = ByteArray(length); raf.readFully(data)
                    if (record == WKT_RECORD) wkt = String(data, Charsets.UTF_8) else geoKeys = data
                }
                offset += 54 + length
            }
            if (minor >= 4 && wkt == null && head.size >= 247) {
                var evlr = b.getLong(235)
                val count = b.getInt(243).toLong() and 0xFFFFFFFFL
                repeat(count.coerceAtMost(1000).toInt()) {
                    if (evlr <= 0 || evlr + 60 > raf.length()) return@repeat
                    val vh = ByteArray(60)
                    raf.seek(evlr); raf.readFully(vh)
                    val bb = ByteBuffer.wrap(vh).order(ByteOrder.LITTLE_ENDIAN)
                    val user = String(vh, 2, 16, Charsets.US_ASCII).trimEnd('\u0000')
                    val record = bb.getShort(18).toInt() and 0xFFFF
                    val length = bb.getLong(20)
                    if (user == PROJECTION_USER && record == WKT_RECORD && length in 1..1_000_000) {
                        val data = ByteArray(length.toInt()); raf.readFully(data)
                        wkt = String(data, Charsets.UTF_8)
                    }
                    evlr += 60 + length
                }
            }
            val crs = wkt?.let(WktCrsParser::parseProjected) ?: geoKeys?.let(::fromGeoKeys)
            val problem = when {
                crs != null -> null
                wkt != null -> "Unsupported CRS in WKT"
                geoKeys != null -> "Unsupported EPSG code in GeoKeys"
                else -> "No CRS stored in this file"
            }
            LasCrsInfo(crs, minX, maxX, minY, maxY, problem)
        }
    }.getOrNull()

    /** GeoTIFF key directory: ProjectedCSTypeGeoKey (3072) + optional ProjLinearUnitsGeoKey (3076). */
    internal fun fromGeoKeys(data: ByteArray): ProjectedCrs? {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (data.size < 8) return null
        val keys = b.getShort(6).toInt() and 0xFFFF
        var epsg: Int? = null
        var unitCode: Int? = null
        for (k in 0 until keys) {
            val base = 8 + k * 8
            if (base + 8 > data.size) break
            val id = b.getShort(base).toInt() and 0xFFFF
            val location = b.getShort(base + 2).toInt() and 0xFFFF
            val value = b.getShort(base + 6).toInt() and 0xFFFF
            if (location != 0) continue
            when (id) {
                3072 -> epsg = value
                3076 -> unitCode = value
            }
        }
        val crs = epsg?.let(ProjectedCrs::fromEpsg) ?: return null
        return when (unitCode) {
            9001 -> crs.copy(unitToMeters = 1.0)
            9002 -> crs.copy(unitToMeters = ProjectedCrs.INTERNATIONAL_FOOT)
            9003 -> crs.copy(unitToMeters = ProjectedCrs.US_SURVEY_FOOT)
            else -> crs
        }
    }
}
