package com.example.analysis.ml

import kotlin.math.exp

/** Per-feature contribution to a candidate's score; the backbone of every explanation. */
data class FeatureContribution(
    val featureName: String,
    val standardizedValue: Float,
    val weight: Float,
    val contribution: Float,
)

/**
 * A calibrated logistic candidate ranker. Models are immutable values identified by
 * [modelVersion]: a production model is never edited in place, only replaced by a new version
 * through the registry. Scores decompose into per-feature contributions, so every ranked target
 * stays explainable against the rule-based baseline.
 */
data class ExplainableRanker(
    val modelVersion: String,
    val featureNames: List<String>,
    val weights: FloatArray,
    val bias: Float,
    val featureMeans: FloatArray,
    val featureStds: FloatArray,
    val calibrationA: Float = 1f,
    val calibrationB: Float = 0f,
) {
    init {
        require(weights.size == featureNames.size) { "weights must match feature names" }
        require(featureMeans.size == featureNames.size) { "means must match feature names" }
        require(featureStds.size == featureNames.size) { "stds must match feature names" }
    }

    fun standardized(values: FloatArray): FloatArray =
        FloatArray(featureNames.size) { index ->
            val std = if (featureStds[index] > 1e-6f) featureStds[index] else 1f
            ((values.getOrElse(index) { 0f } - featureMeans[index]) / std)
        }

    fun rawScore(values: FloatArray): Float {
        val z = standardized(values)
        var raw = bias
        for (index in weights.indices) raw += weights[index] * z[index]
        return raw
    }

    /** Calibrated productive-probability in [0, 1]. */
    fun probability(values: FloatArray): Float =
        sigmoid(calibrationA * rawScore(values) + calibrationB)

    /** Contributions sum (plus the bias) to the raw score; sorted by magnitude. */
    fun contributions(values: FloatArray): List<FeatureContribution> {
        val z = standardized(values)
        return featureNames.indices.map { index ->
            FeatureContribution(
                featureName = featureNames[index],
                standardizedValue = z[index],
                weight = weights[index],
                contribution = weights[index] * z[index],
            )
        }.sortedByDescending { kotlin.math.abs(it.contribution) }
    }

    fun toStorage(): String = buildString {
        appendLine(STORAGE_HEADER)
        appendLine("version\t${encode(modelVersion)}")
        appendLine("names\t${featureNames.joinToString(" ") { encode(it) }}")
        appendLine("weights\t${weights.joinToString(" ")}")
        appendLine("bias\t$bias")
        appendLine("means\t${featureMeans.joinToString(" ")}")
        appendLine("stds\t${featureStds.joinToString(" ")}")
        appendLine("calibration\t$calibrationA $calibrationB")
    }

    override fun equals(other: Any?): Boolean =
        other is ExplainableRanker &&
            modelVersion == other.modelVersion &&
            featureNames == other.featureNames &&
            weights.contentEquals(other.weights) &&
            bias == other.bias &&
            featureMeans.contentEquals(other.featureMeans) &&
            featureStds.contentEquals(other.featureStds) &&
            calibrationA == other.calibrationA &&
            calibrationB == other.calibrationB

    override fun hashCode(): Int = modelVersion.hashCode() * 31 + weights.contentHashCode()

    companion object {
        const val STORAGE_HEADER = "FINDIT_RANKER_V1"

        fun fromStorage(value: String): ExplainableRanker? {
            try {
                val fields = HashMap<String, String>()
                val lines = value.lines().filter { it.isNotBlank() }
                if (lines.firstOrNull() != STORAGE_HEADER) return null
                for (line in lines.drop(1)) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return null
                    fields[line.substring(0, tab)] = line.substring(tab + 1)
                }
                val version = fields["version"]?.let { decode(it) } ?: return null
                val names = fields["names"]?.split(' ')?.filter { it.isNotEmpty() }
                    ?.map { decode(it) } ?: return null
                val weights = parseFloats(fields["weights"]) ?: return null
                val means = parseFloats(fields["means"]) ?: return null
                val stds = parseFloats(fields["stds"]) ?: return null
                val bias = fields["bias"]?.toFloatOrNull() ?: return null
                val calibration = parseFloats(fields["calibration"])
                if (weights.size != names.size || means.size != names.size || stds.size != names.size) {
                    return null
                }
                return ExplainableRanker(
                    modelVersion = version,
                    featureNames = names,
                    weights = weights,
                    bias = bias,
                    featureMeans = means,
                    featureStds = stds,
                    calibrationA = calibration?.getOrElse(0) { 1f } ?: 1f,
                    calibrationB = calibration?.getOrElse(1) { 0f } ?: 0f,
                )
            } catch (exception: Exception) {
                return null
            }
        }

        private fun parseFloats(value: String?): FloatArray? {
            if (value == null) return null
            val parts = value.split(' ').filter { it.isNotEmpty() }
            val parsed = FloatArray(parts.size)
            for (index in parts.indices) {
                parsed[index] = parts[index].toFloatOrNull() ?: return null
                if (!parsed[index].isFinite()) return null
            }
            return parsed
        }

        private fun encode(value: String): String =
            java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

        private fun decode(value: String): String =
            java.net.URLDecoder.decode(value, Charsets.UTF_8.name())

        internal fun sigmoid(value: Float): Float =
            (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()
    }
}
