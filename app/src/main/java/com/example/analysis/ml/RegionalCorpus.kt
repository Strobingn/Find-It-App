package com.example.analysis.ml

import com.example.analysis.ReviewedCandidateExample
import com.example.analysis.ReviewedExampleStore
import com.example.analysis.ReviewedVerdict
import com.example.analysis.reviewedExampleFromManifestLine
import com.example.analysis.toManifestLine
import java.io.File
import java.util.Locale

/**
 * Named regional training corpora for the explainable ranker.
 * Bounds are coarse WGS84 rectangles used only to filter reviewed examples — not ownership claims.
 */
data class RegionalCorpusRegion(
    val id: String,
    val displayName: String,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val notes: String,
) {
    fun contains(lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null) return false
        return lat in minLat..maxLat && lon in minLon..maxLon
    }
}

object RegionalCorpusCatalog {
    val HUDSON_VALLEY = RegionalCorpusRegion(
        id = "hudson_valley",
        displayName = "Hudson Valley / Catskills edge",
        minLat = 41.0,
        maxLat = 42.6,
        minLon = -75.0,
        maxLon = -73.5,
        notes = "NY mid-Hudson focus; farms, cellar holes, stone walls",
    )
    val NORTHEAST = RegionalCorpusRegion(
        id = "northeast_us",
        displayName = "Northeastern US (broad)",
        minLat = 38.5,
        maxLat = 47.5,
        minLon = -80.5,
        maxLon = -66.5,
        notes = "Broad NE filter for portable field packs",
    )
    val ALL = listOf(HUDSON_VALLEY, NORTHEAST)

    fun byId(id: String): RegionalCorpusRegion? = ALL.firstOrNull { it.id == id }
}

data class RegionalCorpusStats(
    val region: RegionalCorpusRegion,
    val total: Int,
    val productive: Int,
    val rejected: Int,
    val other: Int,
) {
    fun summaryLine(): String =
        "${region.displayName}: $total examples · $productive productive · $rejected rejected"
}

/**
 * Filters / exports / imports regional slices of [ReviewedExampleStore] for ranker training.
 */
object RegionalCorpus {

    fun filter(
        examples: List<ReviewedCandidateExample>,
        region: RegionalCorpusRegion,
    ): List<ReviewedCandidateExample> = examples.filter { region.contains(it.latitude, it.longitude) }

    fun stats(
        examples: List<ReviewedCandidateExample>,
        region: RegionalCorpusRegion,
    ): RegionalCorpusStats {
        val slice = filter(examples, region)
        return RegionalCorpusStats(
            region = region,
            total = slice.size,
            productive = slice.count { it.verdict == ReviewedVerdict.PRODUCTIVE },
            rejected = slice.count { it.verdict == ReviewedVerdict.REJECTED },
            other = slice.count {
                it.verdict != ReviewedVerdict.PRODUCTIVE && it.verdict != ReviewedVerdict.REJECTED
            },
        )
    }

    fun allStats(examples: List<ReviewedCandidateExample>): List<RegionalCorpusStats> =
        RegionalCorpusCatalog.ALL.map { stats(examples, it) }

    /**
     * Writes a regional TSV sibling of the main store (same line format) for handoff / backup.
     * Returns count written.
     */
    fun exportToFile(
        store: ReviewedExampleStore,
        region: RegionalCorpusRegion,
        outFile: File,
    ): Int {
        val slice = filter(store.readAll(), region)
        outFile.parentFile?.mkdirs()
        outFile.writeText(
            buildString {
                appendLine("FINDIT_REGIONAL_CORPUS_V1")
                appendLine("region=${region.id}")
                appendLine("name=${region.displayName}")
                appendLine("count=${slice.size}")
                appendLine("---")
                // Re-use manifest format by writing via a temp store pattern
                slice.forEach { ex ->
                    appendLine(ex.toManifestLine())
                }
            },
        )
        return slice.size
    }

    /**
     * Imports regional corpus lines into the main append-only store.
     * Accepts either raw reviewed-example lines or [exportToFile] format.
     */
    fun importIntoStore(
        store: ReviewedExampleStore,
        sourceFile: File,
    ): Int {
        if (!sourceFile.isFile) return 0
        val lines = sourceFile.readLines()
        val body = if (lines.firstOrNull() == "FINDIT_REGIONAL_CORPUS_V1") {
            val dash = lines.indexOfFirst { it.trim() == "---" }
            if (dash < 0) emptyList() else lines.drop(dash + 1)
        } else if (lines.firstOrNull() == "v1") {
            lines.drop(1)
        } else {
            lines
        }
        var n = 0
        for (line in body) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("region=") ||
                line.startsWith("name=") || line.startsWith("count=")
            ) {
                continue
            }
            val ex = reviewedExampleFromManifestLine(line) ?: continue
            store.append(ex)
            n++
        }
        return n
    }

    fun detectRegion(lat: Double?, lon: Double?): RegionalCorpusRegion? {
        if (lat == null || lon == null) return null
        // Prefer tighter region first
        return RegionalCorpusCatalog.ALL.firstOrNull { it.contains(lat, lon) }
    }

    fun formatCatalog(): String = RegionalCorpusCatalog.ALL.joinToString("\n") { r ->
        String.format(
            Locale.US,
            "%s [%s] lat %.2f..%.2f lon %.2f..%.2f — %s",
            r.displayName,
            r.id,
            r.minLat,
            r.maxLat,
            r.minLon,
            r.maxLon,
            r.notes,
        )
    }
}
