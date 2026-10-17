package com.example.data.download

import com.example.data.LazRsNativeDecoder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Rebuilds a USGS TNM LAZ tile from the USGS 3DEP Entwine Point Tile copy in the public
 * `usgs-lidar-public` AWS bucket (served at ~100+ MB/s versus ~0.35 MB/s per rockyweb connection).
 *
 * 1. Map the TNM project/subproject folder in the tile URL to an EPT resource and confirm its
 *    `ept.json` exists and its bounds cover the tile.
 * 2. Range-read the original tile's LAS header + VLRs (tens of KB) for its exact bounds, scale,
 *    offset, CRS and point count.
 * 3. Walk `ept-hierarchy` and download every octree node intersecting the tile at every depth
 *    (full detail) with [connections] parallel keep-alive connections.
 * 4. Native merge: EPSG:3857 -> NAD83 UTM, re-quantize to the original grid, keep the points in the
 *    original quantized bounds and write one standard LAZ with the original header/CRS.
 * 5. Accept only if the kept count equals the original header's point count; otherwise return null
 *    so the caller downloads the original file.
 */
class EptTileBuilder(
    private val connections: Int = 24,
    private val fetch: (String, LongRange?) -> ByteArray? = ::httpGet,
    private val merge: (File, List<File>, File, DoubleArray) -> LongArray? = ::nativeMergeOrNull,
    private val available: () -> Boolean = { LazRsNativeDecoder.isAvailable },
) {
    data class Result(val file: File, val resource: String, val nodes: Int, val nodeBytes: Long, val points: Long)

    fun build(
        tileUrl: String,
        destinationDirectory: File,
        progress: ((Long, Long) -> Unit)? = null,
        shouldContinue: () -> Boolean = { true },
        /**
         * Optional external resolver (e.g. the OpenTopography catalog) mapping the tile's WGS84
         * box (west, south, east, north) to 3DEP EPT resource names. When set it replaces the
         * resource names derived from the tile URL; the exact point-count check is unchanged.
         */
        resourcesFor: ((DoubleArray) -> List<String>)? = null,
    ): Result? {
        if (!available()) return null
        val name = URL(tileUrl).path.substringAfterLast('/')
        if (!name.endsWith(".laz", true) && !name.endsWith(".las", true)) return null
        val target = File(destinationDirectory, name)
        if (target.exists()) return null
        var header: TileHeader? = null
        val names = if (resourcesFor == null) {
            candidateResources(tileUrl)
        } else {
            header = readTileHeader(tileUrl) ?: return null
            val utm = header.utm ?: return null
            resourcesFor(geographicBox(utm, header.minX, header.minY, header.maxX, header.maxY))
                .filter { it.matches(Regex("[A-Za-z0-9_\\-]+")) }
        }
        val resource = names.firstNotNullOfOrNull { r ->
            fetch("$BUCKET/$r/ept.json", null)?.let { r to String(it) }
        } ?: return null
        val (resourceName, ept) = resource
        if (!Regex("\"horizontal\"\\s*:\\s*\"3857\"").containsMatchIn(ept)) return null
        if (header == null) header = readTileHeader(tileUrl) ?: return null
        val tm = header.utm ?: return null
        val box = mercatorBox(tm, header.minX, header.minY, header.maxX, header.maxY)
        val eb = Regex("\"bounds\"\\s*:\\s*\\[([^\\]]+)]").find(ept)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim().toDouble() }?.takeIf { it.size == 6 }?.toDoubleArray() ?: return null
        if (box[0] < eb[0] || box[1] < eb[1] || box[2] > eb[3] || box[3] > eb[4]) return null
        val nodes = intersectingNodes(resourceName, eb, box, shouldContinue)
        if (nodes.isEmpty()) return null

        destinationDirectory.mkdirs()
        val work = File(destinationDirectory, ".ept-${name}.d").apply { deleteRecursively(); mkdirs() }
        try {
            val templateFile = File(work, "template.las").apply { writeBytes(header.bytes) }
            val files = downloadNodes(resourceName, nodes, work, progress, shouldContinue)
            val partial = File(destinationDirectory, ".$name.ept.part")
            val stats = merge(templateFile, files, partial, tm.params) ?: return null
            if (stats[1] != header.pointCount) {
                partial.delete()
                System.err.println("EPT $resourceName kept ${stats[1]} of ${header.pointCount} points; using original")
                return null
            }
            if (!partial.renameTo(target)) return null
            return Result(target, resourceName, nodes.size, files.sumOf { it.length() }, stats[1])
        } finally {
            work.deleteRecursively()
        }
    }

    internal class TileHeader(
        val bytes: ByteArray,
        val minX: Double, val maxX: Double, val minY: Double, val maxY: Double,
        val pointCount: Long,
        val utm: Utm?,
    )

    internal fun readTileHeader(url: String): TileHeader? {
        val head = fetch(url, 0L..374L) ?: return null
        if (head.size < 227) return null
        val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val offset = bb.getInt(96).toLong() and 0xffffffffL
        if (offset > 4L * 1024 * 1024) return null
        val all = if (offset <= head.size) head.copyOf(offset.toInt()) else fetch(url, 0L until offset) ?: return null
        if (all.size.toLong() != offset) return null
        return parseHeader(all)
    }

    private fun intersectingNodes(resource: String, eb: DoubleArray, box: DoubleArray, shouldContinue: () -> Boolean): List<String> {
        val pool = Executors.newFixedThreadPool(connections.coerceIn(1, 64))
        try {
            val out = ArrayList<String>()
            var frontier = listOf("0-0-0-0")
            while (frontier.isNotEmpty()) {
                if (!shouldContinue()) throw CancellationException("EPT cancelled")
                val pages = frontier.map { k -> pool.submit<ByteArray?> { fetch("$BUCKET/$resource/ept-hierarchy/$k.json", null) } }
                    .map { it.get() ?: throw IOException("EPT hierarchy page missing") }
                val next = ArrayList<String>()
                for (page in pages) {
                    for (m in HIER_ENTRY.findAll(String(page))) {
                        val key = m.groupValues[1]
                        if (!nodeIntersects(key, eb, box)) continue
                        when (val count = m.groupValues[2].toLong()) {
                            -1L -> next += key
                            else -> if (count > 0) out += key
                        }
                    }
                }
                frontier = next
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    private fun downloadNodes(
        resource: String, nodes: List<String>, dir: File,
        progress: ((Long, Long) -> Unit)?, shouldContinue: () -> Boolean,
    ): List<File> {
        val pool = Executors.newFixedThreadPool(connections.coerceIn(1, 64))
        val done = AtomicLong()
        try {
            val futures: List<Future<File>> = nodes.map { key ->
                pool.submit<File> {
                    if (!shouldContinue()) throw CancellationException("EPT cancelled")
                    var bytes: ByteArray? = null
                    for (attempt in 0 until 4) {
                        bytes = runCatching { fetch("$BUCKET/$resource/ept-data/$key.laz", null) }.getOrNull()
                        if (bytes != null) break
                        Thread.sleep(500L shl attempt)
                    }
                    val data = bytes ?: throw IOException("EPT node $key failed")
                    val f = File(dir, "$key.laz")
                    FileOutputStream(f).use { it.write(data) }
                    progress?.invoke(done.addAndGet(data.size.toLong()), -1L)
                    f
                }
            }
            return futures.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    /** NAD83 / UTM zone N, as [lon0Deg, k0, falseEasting, falseNorthing]. */
    data class Utm(val zone: Int) {
        val lon0 get() = zone * 6.0 - 183.0
        val params get() = doubleArrayOf(lon0, K0, 500_000.0, 0.0)
    }

    companion object {
        const val BUCKET = "https://s3-us-west-2.amazonaws.com/usgs-lidar-public"
        private const val K0 = 0.9996
        private const val A = 6378137.0
        private const val F = 1.0 / 298.257222101
        private const val R_MERC = 6378137.0

        private val HIER_ENTRY = Regex("\"(\\d+-\\d+-\\d+-\\d+)\"\\s*:\\s*(-?\\d+)")
        val shared by lazy { EptTileBuilder() }

        /** EPT resource names to try for a TNM tile URL, most specific first. */
        fun candidateResources(url: String): List<String> {
            val segs = runCatching { URL(url).path }.getOrDefault(url).split('/').filter { it.isNotBlank() }
            val out = LinkedHashSet<String>()
            val laz = segs.indexOfFirst { it.equals("LAZ", true) }
            if (laz > 0) out += segs[laz - 1]
            val proj = segs.indexOfFirst { it.equals("Projects", true) }
            if (proj >= 0 && proj + 1 < segs.size && proj + 1 < laz) out += segs[proj + 1]
            return out.filter { it.matches(Regex("[A-Za-z0-9_\\-]+")) }
        }

        fun utmFromWkt(wkt: String): Utm? {
            if (!wkt.contains("NAD83", true) && !wkt.contains("North American Datum 1983", true)) return null
            val m = Regex("UTM zone (\\d{1,2})N", RegexOption.IGNORE_CASE).find(wkt) ?: return null
            val unit = Regex("UNIT\\[\"([^\"]+)\"").findAll(wkt).map { it.groupValues[1].lowercase() }.toList()
            if (unit.any { it.contains("foot") || it.contains("feet") || it.contains("us survey") }) return null
            return Utm(m.groupValues[1].toInt())
        }

        internal fun parseHeader(all: ByteArray): TileHeader? {
            val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            if (String(all, 0, 4, Charsets.US_ASCII) != "LASF") return null
            val minor = all[25].toInt()
            val headerSize = bb.getShort(94).toInt() and 0xffff
            val nVlr = bb.getInt(100)
            val legacy = bb.getInt(107).toLong() and 0xffffffffL
            val count = if (minor >= 4 && all.size >= 255) bb.getLong(247).takeIf { it > 0 } ?: legacy else legacy
            val maxX = bb.getDouble(179); val minX = bb.getDouble(187)
            val maxY = bb.getDouble(195); val minY = bb.getDouble(203)
            var p = headerSize
            var utm: Utm? = null
            repeat(nVlr) {
                if (p + 54 > all.size) return@repeat
                val user = String(all, p + 2, 16, Charsets.US_ASCII).trimEnd('\u0000')
                val rec = bb.getShort(p + 18).toInt() and 0xffff
                val len = bb.getShort(p + 20).toInt() and 0xffff
                if (user == "LASF_Projection" && rec == 2112 && p + 54 + len <= all.size) {
                    utm = utmFromWkt(String(all, p + 54, len, Charsets.UTF_8))
                }
                p += 54 + len
            }
            return TileHeader(all, minX, maxX, minY, maxY, count, utm)
        }

        /** Inverse NAD83(GRS80) Transverse Mercator -> (latRad, lonRad). Karney/Krüger series. */
        fun utmToGeographic(utm: Utm, e: Double, n: Double): Pair<Double, Double> {
            val nn = F / (2 - F)
            val n2 = nn * nn; val n3 = n2 * nn; val n4 = n3 * nn; val n5 = n4 * nn; val n6 = n5 * nn
            val bigA = A / (1 + nn) * (1 + n2 / 4 + n4 / 64 + n6 / 256)
            val beta = doubleArrayOf(
                nn / 2 - 2.0 / 3 * n2 + 37.0 / 96 * n3 - 1.0 / 360 * n4 - 81.0 / 512 * n5 + 96199.0 / 604800 * n6,
                1.0 / 48 * n2 + 1.0 / 15 * n3 - 437.0 / 1440 * n4 + 46.0 / 105 * n5 - 1118711.0 / 3870720 * n6,
                17.0 / 480 * n3 - 37.0 / 840 * n4 - 209.0 / 4480 * n5 + 5569.0 / 90720 * n6,
                4397.0 / 161280 * n4 - 11.0 / 504 * n5 - 830251.0 / 7257600 * n6,
                4583.0 / 161280 * n5 - 108847.0 / 3991680 * n6,
                20648693.0 / 638668800 * n6,
            )
            val xi = n / (K0 * bigA); val eta = (e - 500_000.0) / (K0 * bigA)
            var xp = xi; var ep = eta
            for (j in 1..6) {
                xp -= beta[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
                ep -= beta[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
            }
            val tauP = sin(xp) / sqrt(sinh(ep) * sinh(ep) + cos(xp) * cos(xp))
            val lon = atan2(sinh(ep), cos(xp)) + Math.toRadians(utm.lon0)
            val es = sqrt(F * (2 - F))
            var tau = tauP
            repeat(6) {
                val sigma = sinh(es * atanh(es * tau / sqrt(1 + tau * tau)))
                val tp = tau * sqrt(1 + sigma * sigma) - sigma * sqrt(1 + tau * tau)
                tau += (tauP - tp) / sqrt(1 + tp * tp) * (1 + (1 - es * es) * tau * tau) / ((1 - es * es) * sqrt(1 + tau * tau))
            }
            return atan(tau) to lon
        }

        /** WGS84 (west, south, east, north) degrees enclosing a UTM box. */
        fun geographicBox(utm: Utm, minX: Double, minY: Double, maxX: Double, maxY: Double): DoubleArray {
            val corners = listOf(minX to minY, minX to maxY, maxX to minY, maxX to maxY)
                .map { (e, n) -> utmToGeographic(utm, e, n) }
            val lats = corners.map { Math.toDegrees(it.first) }
            val lons = corners.map { Math.toDegrees(it.second) }
            return doubleArrayOf(lons.min(), lats.min(), lons.max(), lats.max())
        }

        fun utmToMercator(utm: Utm, e: Double, n: Double): Pair<Double, Double> {
            val (lat, lon) = utmToGeographic(utm, e, n)
            return R_MERC * lon to R_MERC * ln(tan(Math.PI / 4 + lat / 2))
        }

        /** EPSG:3857 [minX, minY, maxX, maxY] enclosing a UTM rectangle (edges sampled, 2 m pad). */
        fun mercatorBox(utm: Utm, minX: Double, minY: Double, maxX: Double, maxY: Double): DoubleArray {
            val b = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
            for (i in 0..16) {
                val t = i / 16.0
                for ((x, y) in listOf(minX + t * (maxX - minX) to minY, minX + t * (maxX - minX) to maxY,
                    minX to minY + t * (maxY - minY), maxX to minY + t * (maxY - minY))) {
                    val (mx, my) = utmToMercator(utm, x, y)
                    b[0] = minOf(b[0], mx); b[1] = minOf(b[1], my); b[2] = maxOf(b[2], mx); b[3] = maxOf(b[3], my)
                }
            }
            b[0] -= 2; b[1] -= 2; b[2] += 2; b[3] += 2
            return b
        }

        /** EPT key "d-x-y-z" cube (from the cubic ept bounds) overlaps the XY box. */
        fun nodeIntersects(key: String, eptBounds: DoubleArray, box: DoubleArray): Boolean {
            val p = key.split('-').map { it.toInt() }
            val size = (eptBounds[3] - eptBounds[0]) / (1 shl p[0])
            val sizeY = (eptBounds[4] - eptBounds[1]) / (1 shl p[0])
            val x0 = eptBounds[0] + p[1] * size; val y0 = eptBounds[1] + p[2] * sizeY
            return x0 <= box[2] && x0 + size >= box[0] && y0 <= box[3] && y0 + sizeY >= box[1]
        }

        fun httpGet(url: String, range: LongRange?): ByteArray? {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            if (range != null) c.setRequestProperty("Range", "bytes=${range.first}-${range.last}")
            return try {
                val code = c.responseCode
                if (code != 200 && code != 206) null
                else c.inputStream.use { s ->
                    if (range != null && code == 200) {
                        // Range ignored: read only what we asked for.
                        val want = (range.last - range.first + 1).toInt()
                        val buf = ByteArray(want); var off = 0
                        while (off < want) { val r = s.read(buf, off, want - off); if (r < 0) break; off += r }
                        buf.copyOf(off)
                    } else s.readBytes()
                }
            } catch (e: IOException) {
                null
            } finally {
                if (range != null) c.disconnect()
            }
        }

        fun nativeMergeOrNull(template: File, inputs: List<File>, out: File, tm: DoubleArray): LongArray? {
            if (!LazRsNativeDecoder.isAvailable) return null
            val stats = LongArray(2)
            val err = runCatching {
                nativeMerge(template.absolutePath, inputs.map { it.absolutePath }.toTypedArray(), out.absolutePath, tm, stats)
            }.getOrElse { it.message ?: "native merge failed" }
            if (err != null) {
                out.delete()
                System.err.println("EPT merge failed: $err")
                return null
            }
            return stats
        }

        @JvmStatic
        private external fun nativeMerge(template: String, inputs: Array<String>, out: String, tm: DoubleArray, stats: LongArray): String?
    }
}
