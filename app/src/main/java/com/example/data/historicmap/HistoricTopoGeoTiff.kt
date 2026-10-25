package com.example.data.historicmap

import com.example.gps.crs.Ellipsoid
import com.example.gps.crs.HistoricDatum
import com.example.gps.crs.HistoricDatumShift
import com.example.gps.crs.LambertConformalConic
import com.example.gps.crs.Polyconic
import com.example.gps.crs.Projection
import com.example.gps.crs.TransverseMercator
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * One image directory of a classic TIFF: the full-resolution image or one of its overviews.
 * Only the fields needed to cut a USGS historical topo out of its collar are kept.
 */
internal class TiffDirectory(
    val width: Int,
    val height: Int,
    val compression: Int,
    val photometric: Int,
    val subfileType: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val rowsPerStrip: Int,
    val offsets: LongArray,
    val byteCounts: LongArray,
    val jpegTables: ByteArray?,
    val pixelScale: DoubleArray?,
    val tiepoint: DoubleArray?,
    val geoKeys: IntArray?,
    val geoDoubles: DoubleArray?,
) {
    val isTiled: Boolean get() = tileWidth > 0 && tileHeight > 0
    val blockWidth: Int get() = if (isTiled) tileWidth else width
    val blockHeight: Int get() = if (isTiled) tileHeight else rowsPerStrip.coerceAtLeast(1)
    val blocksAcross: Int get() = (width + blockWidth - 1) / blockWidth
    val isJpeg: Boolean get() = compression == 7
}

/** Reads every image directory (main image + overviews) of a classic little/big-endian TIFF. */
internal object TiffDirectoryReader {
    private const val MAX_DIRECTORIES = 16

    fun read(file: File): List<TiffDirectory> = RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(8)
        raf.readFully(header)
        val order = when {
            header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> error("Not a TIFF file")
        }
        val head = ByteBuffer.wrap(header).order(order)
        require(head.getShort(2).toInt() == 42) { "BigTIFF and non-TIFF files are not supported" }
        var next = head.getInt(4).toLong() and 0xFFFF_FFFFL
        val result = ArrayList<TiffDirectory>()
        while (next != 0L && result.size < MAX_DIRECTORIES) {
            val (dir, after) = readDirectory(raf, next, order)
            result += dir
            next = after
        }
        result
    }

    private fun readDirectory(raf: RandomAccessFile, offset: Long, order: ByteOrder): Pair<TiffDirectory, Long> {
        raf.seek(offset)
        val countBytes = ByteArray(2).also { raf.readFully(it) }
        val count = ByteBuffer.wrap(countBytes).order(order).short.toInt() and 0xFFFF
        val entries = ByteArray(count * 12 + 4).also { raf.readFully(it) }
        val buf = ByteBuffer.wrap(entries).order(order)
        val values = HashMap<Int, Entry>()
        for (i in 0 until count) {
            val base = i * 12
            val tag = buf.getShort(base).toInt() and 0xFFFF
            val type = buf.getShort(base + 2).toInt() and 0xFFFF
            val n = buf.getInt(base + 4).toLong() and 0xFFFF_FFFFL
            val size = typeSize(type) * n
            val raw = if (size <= 4) {
                entries.copyOfRange(base + 8, base + 8 + size.toInt())
            } else if (size > 16L * 1024 * 1024) {
                ByteArray(0)
            } else {
                val at = buf.getInt(base + 8).toLong() and 0xFFFF_FFFFL
                val bytes = ByteArray(size.toInt())
                raf.seek(at)
                raf.readFully(bytes)
                bytes
            }
            values[tag] = Entry(type, n.toInt(), raw)
        }
        val nextOffset = buf.getInt(count * 12).toLong() and 0xFFFF_FFFFL
        fun longs(tag: Int): LongArray = values[tag]?.let { e -> LongArray(e.count) { e.long(it, order) } } ?: LongArray(0)
        fun int(tag: Int, default: Int = 0): Int = values[tag]?.takeIf { it.count > 0 }?.long(0, order)?.toInt() ?: default
        fun doubles(tag: Int): DoubleArray? = values[tag]?.let { e -> DoubleArray(e.count) { e.double(it, order) } }
        val isTiled = values.containsKey(322)
        val dir = TiffDirectory(
            width = int(256),
            height = int(257),
            compression = int(259, 1),
            photometric = int(262, 2),
            subfileType = int(254, 0),
            tileWidth = int(322),
            tileHeight = int(323),
            rowsPerStrip = int(278, Int.MAX_VALUE),
            offsets = longs(if (isTiled) 324 else 273),
            byteCounts = longs(if (isTiled) 325 else 279),
            jpegTables = values[347]?.raw,
            pixelScale = doubles(33550),
            tiepoint = doubles(33922),
            geoKeys = values[34735]?.let { e -> IntArray(e.count) { e.long(it, order).toInt() } },
            geoDoubles = doubles(34736),
        )
        return dir to nextOffset
    }

    private fun typeSize(type: Int): Long = when (type) {
        1, 2, 6, 7 -> 1L
        3, 8 -> 2L
        4, 9, 11 -> 4L
        5, 10, 12, 16, 17, 18 -> 8L
        else -> 1L
    }

    private class Entry(val type: Int, val count: Int, val raw: ByteArray) {
        fun long(i: Int, order: ByteOrder): Long {
            val b = ByteBuffer.wrap(raw).order(order)
            return when (type) {
                1, 7 -> (raw[i].toLong() and 0xFF)
                3 -> (b.getShort(i * 2).toLong() and 0xFFFF)
                4 -> (b.getInt(i * 4).toLong() and 0xFFFF_FFFFL)
                16 -> b.getLong(i * 8)
                else -> double(i, order).toLong()
            }
        }

        fun double(i: Int, order: ByteOrder): Double {
            val b = ByteBuffer.wrap(raw).order(order)
            return when (type) {
                12 -> b.getDouble(i * 8)
                11 -> b.getFloat(i * 4).toDouble()
                5 -> {
                    val num = b.getInt(i * 8).toLong() and 0xFFFF_FFFFL
                    val den = b.getInt(i * 8 + 4).toLong() and 0xFFFF_FFFFL
                    if (den == 0L) 0.0 else num.toDouble() / den
                }
                else -> long(i, order).toDouble()
            }
        }
    }
}

/**
 * Pixel ↔ map placement of a USGS historical topographic GeoTIFF, built from its GeoKeys.
 * The quadrangle's neatline is a graticule box in the map's own datum, so projecting those four
 * corners into the image finds the map body inside the white collar exactly.
 */
internal class HistoricTopoGeoreference(
    private val projection: Projection,
    val datum: HistoricDatum,
    private val unitMeters: Double,
    private val scaleX: Double,
    private val scaleY: Double,
    private val tieI: Double,
    private val tieJ: Double,
    private val tieX: Double,
    private val tieY: Double,
) {
    /** Full-resolution pixel (x right, y down) of a lat/lon on the map's own datum. */
    fun pixelOf(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val (e, n) = projection.forward(latDeg, lonDeg)
        val x = e / unitMeters
        val y = n / unitMeters
        return ((x - tieX) / scaleX + tieI) to ((tieY - y) / scaleY + tieJ)
    }

    fun toWgs84(latDeg: Double, lonDeg: Double): Pair<Double, Double> =
        HistoricDatumShift.toWgs84(latDeg, lonDeg, datum)

    /**
     * Neatline of a quadrangle bounded by the given graticule box, as full-resolution pixels in
     * the order top-left, top-right, bottom-right, bottom-left.
     */
    fun neatlinePixels(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Pair<Double, Double>> =
        listOf(
            pixelOf(maxLat, minLon),
            pixelOf(maxLat, maxLon),
            pixelOf(minLat, maxLon),
            pixelOf(minLat, minLon),
        )

    /** WGS84 `[lon, lat]` corners in the same order, ready for a Mapbox image source. */
    fun neatlineWgs84(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<List<Double>> =
        listOf(maxLat to minLon, maxLat to maxLon, minLat to maxLon, minLat to minLon).map { (lat, lon) ->
            val (wLat, wLon) = toWgs84(lat, lon)
            listOf(wLon, wLat)
        }

    companion object {
        private const val KEY_GEOG_CS = 2048
        private const val KEY_ELLIPSOID = 2056
        private const val KEY_SEMI_MAJOR = 2057
        private const val KEY_INV_FLATTENING = 2059
        private const val KEY_PROJECTED_CS = 3072
        private const val KEY_COORD_TRANS = 3075
        private const val KEY_LINEAR_UNITS = 3076
        private const val KEY_STD_PARALLEL_1 = 3078
        private const val KEY_STD_PARALLEL_2 = 3079
        private const val KEY_NAT_ORIGIN_LONG = 3080
        private const val KEY_NAT_ORIGIN_LAT = 3081
        private const val KEY_FALSE_EASTING = 3082
        private const val KEY_FALSE_NORTHING = 3083
        private const val KEY_FALSE_ORIGIN_LONG = 3084
        private const val KEY_FALSE_ORIGIN_LAT = 3085
        private const val KEY_CENTER_LONG = 3088
        private const val KEY_CENTER_LAT = 3089
        private const val KEY_SCALE_AT_NAT_ORIGIN = 3092

        private const val CT_TRANSVERSE_MERCATOR = 1
        private const val CT_LAMBERT_CONIC_2SP = 8
        private const val CT_LAMBERT_CONIC_1SP = 9
        private const val CT_POLYCONIC = 22

        /** Null when the file uses a projection this reader doesn't handle. */
        fun from(dir: TiffDirectory): HistoricTopoGeoreference? {
            val keys = dir.geoKeys ?: return null
            val scale = dir.pixelScale ?: return null
            val tie = dir.tiepoint ?: return null
            if (keys.size < 4 || scale.size < 2 || tie.size < 6) return null
            val doubles = dir.geoDoubles ?: DoubleArray(0)
            val shorts = HashMap<Int, Int>()
            val reals = HashMap<Int, Double>()
            for (i in 0 until keys[3]) {
                val base = 4 + i * 4
                if (base + 3 >= keys.size) break
                val key = keys[base]
                val location = keys[base + 1]
                val value = keys[base + 3]
                when (location) {
                    0 -> shorts[key] = value
                    34736 -> doubles.getOrNull(value)?.let { reals[key] = it }
                }
            }
            val semiMajor = reals[KEY_SEMI_MAJOR]
            val isNad27 = shorts[KEY_GEOG_CS] == 4267 || shorts[KEY_ELLIPSOID] == 7008 ||
                (semiMajor != null && abs(semiMajor - 6_378_206.4) < 1.0) ||
                shorts[KEY_PROJECTED_CS]?.let { it in 26701..26799 } == true
            val datum = if (isNad27) HistoricDatum.NAD27 else HistoricDatum.WGS84
            val ellipsoid = when {
                isNad27 -> HistoricDatumShift.CLARKE_1866
                semiMajor != null && reals[KEY_INV_FLATTENING] != null ->
                    Ellipsoid(semiMajor, reals.getValue(KEY_INV_FLATTENING))
                else -> Ellipsoid.WGS84
            }
            val unitMeters = when (shorts[KEY_LINEAR_UNITS]) {
                9002 -> 0.3048
                9003 -> 1200.0 / 3937.0
                else -> 1.0
            }
            val fe = (reals[KEY_FALSE_EASTING] ?: 0.0) * unitMeters
            val fn = (reals[KEY_FALSE_NORTHING] ?: 0.0) * unitMeters
            val projection: Projection = utmFromEpsg(shorts[KEY_PROJECTED_CS], ellipsoid) ?: when (shorts[KEY_COORD_TRANS]) {
                CT_TRANSVERSE_MERCATOR -> TransverseMercator(
                    ellipsoid,
                    reals[KEY_NAT_ORIGIN_LAT] ?: reals[KEY_CENTER_LAT] ?: 0.0,
                    reals[KEY_NAT_ORIGIN_LONG] ?: reals[KEY_CENTER_LONG] ?: return null,
                    reals[KEY_SCALE_AT_NAT_ORIGIN] ?: 1.0,
                    fe,
                    fn,
                )
                CT_POLYCONIC -> Polyconic(
                    ellipsoid,
                    reals[KEY_NAT_ORIGIN_LAT] ?: reals[KEY_CENTER_LAT] ?: 0.0,
                    reals[KEY_NAT_ORIGIN_LONG] ?: reals[KEY_CENTER_LONG] ?: return null,
                    fe,
                    fn,
                )
                CT_LAMBERT_CONIC_2SP, CT_LAMBERT_CONIC_1SP -> LambertConformalConic(
                    ellipsoid,
                    reals[KEY_FALSE_ORIGIN_LAT] ?: reals[KEY_NAT_ORIGIN_LAT] ?: 0.0,
                    reals[KEY_FALSE_ORIGIN_LONG] ?: reals[KEY_NAT_ORIGIN_LONG] ?: return null,
                    reals[KEY_STD_PARALLEL_1],
                    reals[KEY_STD_PARALLEL_2],
                    reals[KEY_SCALE_AT_NAT_ORIGIN] ?: 1.0,
                    ((reals[3086] ?: reals[KEY_FALSE_EASTING]) ?: 0.0) * unitMeters,
                    ((reals[3087] ?: reals[KEY_FALSE_NORTHING]) ?: 0.0) * unitMeters,
                )
                else -> return null
            }
            return HistoricTopoGeoreference(
                projection = projection,
                datum = datum,
                unitMeters = unitMeters,
                scaleX = scale[0],
                scaleY = scale[1],
                tieI = tie[0],
                tieJ = tie[1],
                tieX = tie[3],
                tieY = tie[4],
            )
        }

        /** EPSG UTM codes: 267zz NAD27, 269zz NAD83, 326zz WGS84 (north). */
        private fun utmFromEpsg(code: Int?, ellipsoid: Ellipsoid): Projection? {
            val zone = when (code) {
                in 26701..26722 -> code!! - 26700
                in 26901..26923 -> code!! - 26900
                in 32601..32660 -> code!! - 32600
                else -> return null
            }
            return TransverseMercator(ellipsoid, 0.0, -183.0 + 6.0 * zone, 0.9996, 500_000.0, 0.0)
        }
    }
}
