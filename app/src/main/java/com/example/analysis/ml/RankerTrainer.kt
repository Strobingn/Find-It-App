package com.example.analysis.ml

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

data class RankerTrainingExample(
    val features: FloatArray,
    val productive: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is RankerTrainingExample &&
            productive == other.productive &&
            features.contentEquals(other.features)

    override fun hashCode(): Int = features.contentHashCode() * 31 + productive.hashCode()
}

data class RankerTrainingResult(
    val ranker: ExplainableRanker,
    val iterations: Int,
    val finalLogLoss: Float,
    val accuracy: Float,
)

/**
 * Trains the explainable logistic ranker with L2-regularized batch gradient descent, then fits
 * Platt calibration parameters on the training scores. Deterministic: same data and hyper-
 * parameters produce the same model, so a trained version is exactly reproducible.
 */
object RankerTrainer {
    private const val PLATT_ITERATIONS = 300
    private const val PLATT_LEARNING_RATE = 0.01f

    fun train(
        examples: List<RankerTrainingExample>,
        modelVersion: String,
        featureNames: List<String>,
        learningRate: Float = 0.2f,
        l2: Float = 0.001f,
        maxIterations: Int = 2_000,
        tolerance: Float = 1e-7f,
    ): RankerTrainingResult {
        require(examples.isNotEmpty()) { "training requires at least one example" }
        val featureCount = featureNames.size
        require(examples.all { it.features.size >= featureCount }) {
            "every example must provide all features"
        }

        val means = FloatArray(featureCount)
        for (example in examples) {
            for (index in 0 until featureCount) means[index] += example.features[index]
        }
        for (index in 0 until featureCount) means[index] /= examples.size
        val stds = FloatArray(featureCount)
        for (example in examples) {
            for (index in 0 until featureCount) {
                val delta = example.features[index] - means[index]
                stds[index] += delta * delta
            }
        }
        for (index in 0 until featureCount) {
            stds[index] = sqrt(stds[index] / examples.size).coerceAtLeast(1e-6f)
        }

        val standardized = examples.map { example ->
            FloatArray(featureCount) { index ->
                (example.features[index] - means[index]) / stds[index]
            }
        }
        val labels = examples.map { if (it.productive) 1f else 0f }

        val weights = FloatArray(featureCount)
        var bias = 0f
        var previousLoss = Float.MAX_VALUE
        var iterations = 0
        for (iteration in 1..maxIterations) {
            iterations = iteration
            val gradientW = FloatArray(featureCount)
            var gradientB = 0f
            var loss = 0f
            for (row in standardized.indices) {
                var raw = bias
                for (index in 0 until featureCount) raw += weights[index] * standardized[row][index]
                val probability = ExplainableRanker.sigmoid(raw).coerceIn(1e-7f, 1f - 1e-7f)
                val label = labels[row]
                loss -= label * ln(probability) + (1f - label) * ln(1f - probability)
                val error = probability - label
                for (index in 0 until featureCount) gradientW[index] += error * standardized[row][index]
                gradientB += error
            }
            loss /= examples.size
            for (index in 0 until featureCount) {
                loss += 0.5f * l2 * weights[index] * weights[index]
                weights[index] -= learningRate * (gradientW[index] / examples.size + l2 * weights[index])
            }
            bias -= learningRate * gradientB / examples.size
            if (abs(previousLoss - loss) < tolerance) {
                previousLoss = loss
                break
            }
            previousLoss = loss
        }

        var ranker = ExplainableRanker(
            modelVersion = modelVersion,
            featureNames = featureNames,
            weights = weights,
            bias = bias,
            featureMeans = means,
            featureStds = stds,
        )
        val (calibrationA, calibrationB) = fitPlattCalibration(
            rawScores = examples.map { ranker.rawScore(it.features) },
            labels = labels,
        )
        ranker = ranker.copy(calibrationA = calibrationA, calibrationB = calibrationB)

        var correct = 0
        var finalLoss = 0f
        for (row in examples.indices) {
            val probability = ranker.probability(examples[row].features).coerceIn(1e-7f, 1f - 1e-7f)
            val label = labels[row]
            finalLoss -= label * ln(probability) + (1f - label) * ln(1f - probability)
            if ((probability >= 0.5f) == examples[row].productive) correct++
        }
        return RankerTrainingResult(
            ranker = ranker,
            iterations = iterations,
            finalLogLoss = finalLoss / examples.size,
            accuracy = correct.toFloat() / examples.size,
        )
    }

    /**
     * Platt scaling: fits p = sigmoid(a·raw + b) by gradient descent on the calibration set,
     * using the usual softened targets (N+1)/(N+2) and 1/(N+2).
     */
    private fun fitPlattCalibration(rawScores: List<Float>, labels: List<Float>): Pair<Float, Float> {
        val positives = labels.count { it >= 0.5f }
        val negatives = labels.size - positives
        if (positives == 0 || negatives == 0) return 1f to 0f
        val hiTarget = (positives + 1f) / (positives + 2f)
        val loTarget = 1f / (negatives + 2f)
        val targets = labels.map { if (it >= 0.5f) hiTarget else loTarget }

        var a = 1f
        var b = 0f
        for (iteration in 0 until PLATT_ITERATIONS) {
            var gradientA = 0f
            var gradientB = 0f
            for (row in rawScores.indices) {
                val probability = ExplainableRanker.sigmoid(a * rawScores[row] + b)
                val error = probability - targets[row]
                gradientA += error * rawScores[row]
                gradientB += error
            }
            a -= PLATT_LEARNING_RATE * gradientA / rawScores.size
            b -= PLATT_LEARNING_RATE * gradientB / rawScores.size
        }
        return a to b
    }
}
