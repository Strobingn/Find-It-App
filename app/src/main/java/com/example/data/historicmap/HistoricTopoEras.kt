package com.example.data.historicmap

import kotlin.math.abs

/**
 * Groups the USGS historical topo editions covering a view into decades ("eras") and picks which
 * sheets to draw for an era: one edition per quadrangle footprint, finest scale on top.
 */
object HistoricTopoEras {
    private val SCALE_PATTERN = Regex("""1:(\d{4,7})""")
    private val FILE_SCALE_PATTERN = Regex("""_(\d{4,7})_geo\.""")

    fun scaleOf(record: HistoricMapRecord): Int =
        SCALE_PATTERN.find(record.title)?.groupValues?.get(1)?.toIntOrNull()
            ?: FILE_SCALE_PATTERN.find(record.remoteUrl)?.groupValues?.get(1)?.toIntOrNull()
            ?: 62_500

    fun decadeOf(record: HistoricMapRecord): Int? = record.editionYear?.let { it / 10 * 10 }

    /** Decades with at least one sheet covering the point, oldest first. */
    fun decades(records: List<HistoricMapRecord>, lat: Double, lon: Double): List<Int> =
        records.filter { it.covers(lat, lon) }.mapNotNull(::decadeOf).distinct().sorted()

    /**
     * Sheets to draw for [decade] in the view, coarse scales first so finer sheets stack on top.
     * Within one footprint the earliest edition of the decade wins (first printings are the ones
     * that show what was there before later revisions).
     */
    fun sheetsFor(
        records: List<HistoricMapRecord>,
        decade: Int,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        centerLat: Double,
        centerLon: Double,
        maxSheets: Int = 4,
    ): List<HistoricMapRecord> {
        val inEra = records.filter { decadeOf(it) == decade && it.intersects(minLat, maxLat, minLon, maxLon) }
        val perFootprint = inEra
            .groupBy { footprint(it) }
            .values
            .map { group -> group.minWith(compareBy<HistoricMapRecord>({ it.editionYear ?: 9999 }, { it.id })) }
        // Prefer the finest scale that covers the centre; coarser sheets only fill gaps.
        val ranked = perFootprint.sortedWith(
            compareBy<HistoricMapRecord>(
                { if (it.covers(centerLat, centerLon)) 0 else 1 },
                { scaleOf(it) },
                { distanceToCenter(it, centerLat, centerLon) },
            ),
        )
        val picked = ArrayList<HistoricMapRecord>()
        for (candidate in ranked) {
            if (picked.size >= maxSheets) break
            // Skip a coarse sheet whose area is already fully covered by a finer sheet we picked.
            val hiddenByFiner = picked.any { it.containsBox(candidate) && scaleOf(it) < scaleOf(candidate) }
            if (!hiddenByFiner) picked += candidate
        }
        return picked.sortedByDescending { scaleOf(it) }
    }

    private fun footprint(r: HistoricMapRecord) = listOf(
        "%.4f".format(r.minLatitude),
        "%.4f".format(r.maxLatitude),
        "%.4f".format(r.minLongitude),
        "%.4f".format(r.maxLongitude),
    ).joinToString(",")

    private fun HistoricMapRecord.covers(lat: Double, lon: Double) =
        lat in minLatitude..maxLatitude && lon in minLongitude..maxLongitude

    private fun HistoricMapRecord.intersects(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) =
        minLatitude <= maxLat && maxLatitude >= minLat && minLongitude <= maxLon && maxLongitude >= minLon

    private fun HistoricMapRecord.containsBox(other: HistoricMapRecord) =
        minLatitude <= other.minLatitude && maxLatitude >= other.maxLatitude &&
            minLongitude <= other.minLongitude && maxLongitude >= other.maxLongitude

    private fun distanceToCenter(r: HistoricMapRecord, lat: Double, lon: Double) =
        abs((r.minLatitude + r.maxLatitude) / 2 - lat) + abs((r.minLongitude + r.maxLongitude) / 2 - lon)
}
