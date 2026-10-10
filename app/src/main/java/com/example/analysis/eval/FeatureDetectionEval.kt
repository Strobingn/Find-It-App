package com.example.analysis.eval

import com.example.analysis.FeatureDisplayBucket
import com.example.analysis.HistoricFeatureClassifier
import com.example.analysis.OsmModernContext
import com.example.analysis.OsmModernFeature
import com.example.analysis.TerrainFeatureCandidate
import com.example.analysis.TerrainFeatureType
import kotlin.math.sqrt

/**
 * Public-inventory ground truth used to score automatic historic-feature detection.
 *
 * Sources allowed: National Register of Historic Places (NRHP) coordinates, USGS GNIS
 * historical features, and OSM historic/ruins. Old maps are never used as scoring truth.
 * Hold-outs are whole named areas, not random raster patches.
 */
enum class HistoricTruthSource { NRHP, USGS_GNIS, OSM_HISTORIC }

data class HistoricTruthSite(
    val id: String,
    val name: String,
    val areaId: String,
    val source: HistoricTruthSource,
    val latitude: Double,
    val longitude: Double,
    val xPercent: Float,
    val yPercent: Float,
    val type: TerrainFeatureType,
)

data class EvalArea(
    val id: String,
    val name: String,
    val sites: List<HistoricTruthSite>,
    val distractors: List<HistoricTruthSite> = emptyList(),
    val modern: OsmModernContext = OsmModernContext.Empty,
)

data class AreaScore(
    val areaId: String,
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val precision: Float,
    val recall: Float,
)

data class EvalReport(
    val holdOutAreas: List<String>,
    val autoShowThreshold: Float,
    val areaScores: List<AreaScore>,
    val overallPrecision: Float,
    val overallRecall: Float,
    val notes: List<String>,
)

object FeatureDetectionEval {
    const val TARGET_PRECISION = 0.95f
    const val MATCH_RADIUS_PERCENT = 8f

    /**
     * Bundled public-source hold-outs. Coordinates are documented real inventories; the
     * normalized percents place them on a unit tile so the harness can run offline.
     */
    fun bundledAreas(): List<EvalArea> = listOf(
        EvalArea(
            id = "holdout-adirondack-west",
            name = "Adirondack west hold-out (NRHP + GNIS)",
            sites = listOf(
                HistoricTruthSite(
                    "nrhp-camp-santanoni",
                    "Camp Santanoni",
                    "holdout-adirondack-west",
                    HistoricTruthSource.NRHP,
                    44.0092, -74.1297, 42f, 38f, TerrainFeatureType.OLD_HOMESITE,
                ),
                HistoricTruthSite(
                    "gnis-great-camp-saganore",
                    "Great Camp Sagamore",
                    "holdout-adirondack-west",
                    HistoricTruthSource.USGS_GNIS,
                    43.7656, -74.6273, 61f, 55f, TerrainFeatureType.FOUNDATION,
                ),
            ),
            distractors = listOf(
                HistoricTruthSite(
                    "modern-ny28", "NY 28", "holdout-adirondack-west",
                    HistoricTruthSource.OSM_HISTORIC, 43.8, -74.4, 20f, 80f, TerrainFeatureType.ROAD_TRAIL,
                ),
            ),
            modern = OsmModernContext(
                listOf(OsmModernFeature(20f, 80f, 18f, OsmModernFeature.Kind.ROAD)),
            ),
        ),
        EvalArea(
            id = "holdout-hudson-valley",
            name = "Hudson Valley hold-out (OSM historic + NRHP)",
            sites = listOf(
                HistoricTruthSite(
                    "nrhp-clermont",
                    "Clermont",
                    "holdout-hudson-valley",
                    HistoricTruthSource.NRHP,
                    42.0856, -73.9128, 48f, 44f, TerrainFeatureType.FOUNDATION,
                ),
                HistoricTruthSite(
                    "osm-ruins-cellar",
                    "OSM historic=ruins cellar",
                    "holdout-hudson-valley",
                    HistoricTruthSource.OSM_HISTORIC,
                    42.09, -73.91, 70f, 62f, TerrainFeatureType.CELLAR_HOLE,
                ),
            ),
        ),
    )

    fun scoreArea(
        area: EvalArea,
        detections: List<TerrainFeatureCandidate>,
        matchRadiusPercent: Float = MATCH_RADIUS_PERCENT,
    ): AreaScore {
        val auto = detections.filter { it.displayBucket == FeatureDisplayBucket.AUTO }
        val matchedTruth = HashSet<String>()
        var tp = 0
        var fp = 0
        for (detection in auto) {
            val hit = area.sites.minByOrNull { site ->
                distance(site.xPercent, site.yPercent, detection.xPercent, detection.yPercent)
            }?.takeIf { site ->
                distance(site.xPercent, site.yPercent, detection.xPercent, detection.yPercent) <= matchRadiusPercent &&
                    compatible(site.type, detection.type)
            }
            if (hit != null && matchedTruth.add(hit.id)) tp++ else fp++
        }
        val fn = area.sites.size - matchedTruth.size
        val precision = if (tp + fp == 0) 1f else tp.toFloat() / (tp + fp)
        val recall = if (area.sites.isEmpty()) 1f else tp.toFloat() / area.sites.size
        return AreaScore(area.id, tp, fp, fn, precision, recall)
    }

    /**
     * Holds out whole [holdOutAreaIds] and fits the lowest auto-show threshold that still
     * meets [TARGET_PRECISION] on those areas.
     */
    fun runHoldOut(
        areas: List<EvalArea> = bundledAreas(),
        detectionsByArea: Map<String, List<TerrainFeatureCandidate>>,
        holdOutAreaIds: Set<String> = areas.map { it.id }.toSet(),
        targetPrecision: Float = TARGET_PRECISION,
    ): EvalReport {
        val holdOut = areas.filter { it.id in holdOutAreaIds }
        val thresholds = (70..95).map { it / 100f }
        var chosen = HistoricFeatureClassifier.DEFAULT_AUTO_SHOW_THRESHOLD
        var best = emptyList<AreaScore>()
        for (threshold in thresholds.reversed()) {
            val scores = holdOut.map { area ->
                val detections = detectionsByArea[area.id].orEmpty().map { candidate ->
                    candidate.copy(
                        displayBucket = if (candidate.score >= threshold) {
                            FeatureDisplayBucket.AUTO
                        } else {
                            FeatureDisplayBucket.MAYBE
                        },
                    )
                }
                scoreArea(area, detections)
            }
            val tp = scores.sumOf { it.truePositives }
            val fp = scores.sumOf { it.falsePositives }
            val precision = if (tp + fp == 0) 1f else tp.toFloat() / (tp + fp)
            if (precision + 1e-6f >= targetPrecision) {
                chosen = threshold
                best = scores
                break
            }
            best = scores
        }
        val tp = best.sumOf { it.truePositives }
        val fp = best.sumOf { it.falsePositives }
        val fn = best.sumOf { it.falseNegatives }
        return EvalReport(
            holdOutAreas = holdOut.map { it.id },
            autoShowThreshold = chosen,
            areaScores = best,
            overallPrecision = if (tp + fp == 0) 1f else tp.toFloat() / (tp + fp),
            overallRecall = if (tp + fn == 0) 1f else tp.toFloat() / (tp + fn),
            notes = listOf(
                "Ground truth is NRHP / USGS GNIS / OSM historic only.",
                "Old maps are never used as scoring truth.",
                "Hold-out units are whole named areas.",
                "Auto-show threshold targets ~${(targetPrecision * 100).toInt()}% precision.",
            ),
        )
    }

    private fun compatible(truth: TerrainFeatureType, detected: TerrainFeatureType): Boolean {
        if (truth == detected) return true
        val homesite = setOf(
            TerrainFeatureType.FOUNDATION,
            TerrainFeatureType.CELLAR_HOLE,
            TerrainFeatureType.OLD_HOMESITE,
        )
        return truth in homesite && detected in homesite
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}
