package com.example.analysis.ml

import com.example.analysis.TerrainDerivedLayer
import com.example.analysis.TerrainDerivedLayers
import com.example.analysis.TerrainFeatureCandidate
import kotlin.math.roundToInt

/** A stable, schema-versioned feature vector — the unit every model trains and scores on. */
data class CandidateFeatureVector(
    val schemaVersion: Int,
    val featureNames: List<String>,
    val values: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is CandidateFeatureVector &&
            schemaVersion == other.schemaVersion &&
            featureNames == other.featureNames &&
            values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode() * 31 + schemaVersion
}

/**
 * Extracts the model feature vector for a candidate. Feature order is append-only: models record
 * the schema version they were trained with, and a vector from a newer schema is never silently
 * fed to an older model. Missing derived layers contribute zero rather than failing extraction.
 */
object CandidateFeatures {
    const val SCHEMA_VERSION = 1

    val FEATURE_NAMES: List<String> = listOf(
        "rule_score",
        "radius_meters",
        "evidence_count",
        "layer_slope",
        "layer_curvature",
        "layer_local_relief",
        "layer_multi_scale_relief",
        "layer_hillshade_comparison",
        "layer_ruggedness",
        "layer_linearity",
        "layer_depression_depth",
        "layer_natural_feature_penalty",
        "layer_modern_disturbance_penalty",
    )

    private val SAMPLED_LAYERS: List<TerrainDerivedLayer> = listOf(
        TerrainDerivedLayer.SLOPE,
        TerrainDerivedLayer.CURVATURE,
        TerrainDerivedLayer.LOCAL_RELIEF,
        TerrainDerivedLayer.MULTI_SCALE_RELIEF,
        TerrainDerivedLayer.HILLSHADE_COMPARISON,
        TerrainDerivedLayer.RUGGEDNESS,
        TerrainDerivedLayer.LINEARITY,
        TerrainDerivedLayer.DEPRESSION_DEPTH,
        TerrainDerivedLayer.NATURAL_FEATURE_PENALTY,
        TerrainDerivedLayer.MODERN_DISTURBANCE_PENALTY,
    )

    fun extract(
        candidate: TerrainFeatureCandidate,
        layers: TerrainDerivedLayers?,
    ): CandidateFeatureVector {
        val layerValues = FloatArray(SAMPLED_LAYERS.size)
        if (layers != null && layers.width > 0 && layers.height > 0) {
            val column = (candidate.xPercent.coerceIn(0f, 100f) / 100f * (layers.width - 1))
                .roundToInt().coerceIn(0, layers.width - 1)
            val row = (candidate.yPercent.coerceIn(0f, 100f) / 100f * (layers.height - 1))
                .roundToInt().coerceIn(0, layers.height - 1)
            val index = row * layers.width + column
            SAMPLED_LAYERS.forEachIndexed { position, layer ->
                val values = layers.values[layer]
                if (values != null && index < values.size && values[index].isFinite()) {
                    layerValues[position] = values[index]
                }
            }
        }
        return CandidateFeatureVector(
            schemaVersion = SCHEMA_VERSION,
            featureNames = FEATURE_NAMES,
            values = floatArrayOf(
                candidate.score,
                candidate.radiusMeters,
                candidate.evidence.size.toFloat(),
                *layerValues,
            ),
        )
    }
}
