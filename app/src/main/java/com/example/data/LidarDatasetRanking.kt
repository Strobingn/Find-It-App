package com.example.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos

/**
 * Groups LiDAR tiles covering a selected area into datasets (survey projects) and ranks them so the
 * most detailed one is downloaded by default: highest point density (pts/m²) first, then the
 * newest acquisition year. Density comes from a real LAS header (point count over the tile's
 * geographic footprint) when available, else from the USGS quality level in the name, else unknown
 * (ranked last among equals).
 */
object LidarDatasetRanking {
    enum class DensitySource { LAS_HEADER, QUALITY_LEVEL, UNKNOWN }

    data class Dataset(
        val key: String,
        val displayName: String,
        val year: Int?,
        val pointsPerSquareMeter: Double?,
        val densitySource: DensitySource,
        val tiles: List<NysHistoricLazTileCatalog.Tile>,
    ) {
        val summary: String
            get() = buildString {
                append(displayName)
                year?.let { append(" · ").append(it) }
                append(" · ")
                append(
                    pointsPerSquareMeter?.let { "%.1f pts/m²".format(java.util.Locale.US, it) } ?: "density unknown",
                )
                if (densitySource == DensitySource.QUALITY_LEVEL) append(" (QL)")
                append(" · ").append(tiles.size).append(if (tiles.size == 1) " tile" else " tiles")
            }
    }

    /** Dataset key: USGS `/Projects/<project>/<subproject>/` path, else the catalog project label. */
    fun datasetKey(tile: NysHistoricLazTileCatalog.Tile): String {
        val m = Regex("/Projects/([^/]+)/([^/]+)/", RegexOption.IGNORE_CASE).find(tile.downloadUrl)
        if (m != null) {
            val (project, sub) = m.destructured
            return if (sub.equals("LAZ", true) || sub.equals("LAS", true)) project else "$project/$sub"
        }
        return tile.project.ifBlank { tile.downloadUrl.substringBeforeLast('/') }
    }

    fun displayName(key: String): String = key.substringBefore('/').replace('_', ' ')

    /** Acquisition year from names like `..._2022`, `_A22`, `_D19`, `2018_D19`. */
    fun yearOf(text: String): Int? {
        Regex("(?<![0-9])(20[0-3][0-9]|199[0-9])(?![0-9])").findAll(text).map { it.value.toInt() }.maxOrNull()?.let { return it }
        Regex("_[A-Z](\\d{2})(?![0-9A-Za-z])").find(text)?.let { return 2000 + it.groupValues[1].toInt() }
        return null
    }

    /** Nominal minimum density for a USGS quality level mentioned in a name (QL0 8+, QL1 8, QL2 2, QL3 0.5). */
    fun qualityLevelDensity(text: String): Double? = when {
        Regex("QL0", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 20.0
        Regex("QL1", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 8.0
        Regex("QL2", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 2.0
        Regex("QL3", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 0.5
        else -> null
    }

    /** Point count from the first 375 bytes of a LAS/LAZ file (LAS 1.4 extended count preferred). */
    fun lasHeaderPointCount(header: ByteArray): Long? {
        if (header.size < 111 || String(header, 0, 4, Charsets.US_ASCII) != "LASF") return null
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val minor = header[25].toInt()
        if (minor >= 4 && header.size >= 255) {
            val extended = b.getLong(247)
            if (extended > 0) return extended
        }
        return (b.getInt(107).toLong() and 0xFFFFFFFFL).takeIf { it > 0 }
    }

    internal fun densityBucket(d: Double?): Int =
        if (d == null || d <= 0.0) Int.MIN_VALUE else kotlin.math.floor(kotlin.math.ln(d) / kotlin.math.ln(1.15)).toInt()

    /** Approximate ground area (m²) of a lon/lat box. */
    fun footprintSquareMeters(tile: NysHistoricLazTileCatalog.Tile): Double? {
        val w = tile.minLongitude ?: return null
        val e = tile.maxLongitude ?: return null
        val s = tile.minLatitude ?: return null
        val n = tile.maxLatitude ?: return null
        if (e <= w || n <= s) return null
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos((s + n) / 2.0 * PI / 180.0)
        return (e - w) * metersPerDegLon * (n - s) * metersPerDegLat
    }

    /**
     * Groups and ranks. [headerOf] fetches one tile's first 375 bytes (null if unavailable); it is
     * called for at most one tile per dataset.
     */
    fun rank(
        tiles: List<NysHistoricLazTileCatalog.Tile>,
        headerOf: (NysHistoricLazTileCatalog.Tile) -> ByteArray? = { null },
    ): List<Dataset> {
        val groups = tiles.groupBy(::datasetKey)
        val datasets = groups.map { (key, members) ->
            val names = key + " " + members.first().project + " " + members.first().name
            val sample = members.firstOrNull { footprintSquareMeters(it) != null }
            val headerDensity = sample?.let { t ->
                val count = runCatching { headerOf(t) }.getOrNull()?.let(::lasHeaderPointCount)
                val area = footprintSquareMeters(t)
                if (count != null && area != null && area > 0) count / area else null
            }
            val ql = qualityLevelDensity(names)
            Dataset(
                key = key,
                displayName = displayName(key),
                year = yearOf(names),
                pointsPerSquareMeter = headerDensity ?: ql,
                densitySource = when {
                    headerDensity != null -> DensitySource.LAS_HEADER
                    ql != null -> DensitySource.QUALITY_LEVEL
                    else -> DensitySource.UNKNOWN
                },
                tiles = members,
            )
        }
        return sort(datasets)
    }

    /**
     * Density (pts/m², unknown last) descending, then year descending, then name for stability.
     * Densities within ~15% of each other share a bucket so header noise does not beat a newer survey.
     */
    fun sort(datasets: List<Dataset>): List<Dataset> = datasets.sortedWith(
        compareByDescending<Dataset> { densityBucket(it.pointsPerSquareMeter) }
            .thenByDescending { it.year ?: 0 }
            .thenBy { it.key },
    )
}
