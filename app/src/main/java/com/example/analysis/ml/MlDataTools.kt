package com.example.analysis.ml

import kotlin.math.floor

/**
 * Assigns reviewed examples to training/evaluation folds by spatial block, never at random, so
 * evaluation areas are geographically separated from training areas — near-duplicates across a
 * fence line cannot leak into both sides of an evaluation.
 */
object SpatialFoldSplitter {
    /** ~1 km blocks from coordinates; ~10% grid blocks when only raster position is known. */
    fun foldIndex(
        latitude: Double?,
        longitude: Double?,
        xPercent: Float,
        yPercent: Float,
        foldCount: Int,
    ): Int {
        require(foldCount >= 2) { "at least two folds are required" }
        val blockX: Int
        val blockY: Int
        if (latitude != null && longitude != null) {
            blockX = floor(longitude * 100.0).toInt()
            blockY = floor(latitude * 100.0).toInt()
        } else {
            blockX = floor(xPercent.coerceIn(0f, 99.999f) / 10f).toInt()
            blockY = floor(yPercent.coerceIn(0f, 99.999f) / 10f).toInt()
        }
        var hash = blockX.toLong() * 0x8DA6B343L xor blockY.toLong() * 0xD8163841L
        hash = hash xor (hash ushr 29)
        return ((hash % foldCount) + foldCount).toInt() % foldCount
    }

    fun <T> split(
        examples: List<T>,
        foldCount: Int,
        location: (T) -> FoldLocation,
    ): List<List<T>> {
        val folds = List(foldCount) { ArrayList<T>() }
        for (example in examples) {
            val where = location(example)
            folds[foldIndex(where.latitude, where.longitude, where.xPercent, where.yPercent, foldCount)]
                .add(example)
        }
        return folds.map { it.toList() }
    }

    data class FoldLocation(
        val latitude: Double?,
        val longitude: Double?,
        val xPercent: Float,
        val yPercent: Float,
    )
}

/**
 * Hard-negative mining: surfaces the rejected examples a model scores highest — the false
 * positives most worth reviewing and weighting in the next training round.
 */
object HardNegativeMiner {
    fun <T> select(
        rejectedExamples: List<T>,
        score: (T) -> Float,
        limit: Int,
    ): List<T> = rejectedExamples
        .sortedByDescending(score)
        .take(limit.coerceAtLeast(0))
}

/**
 * Versioned model registry with explicit activation and rollback. The active model is never
 * mutated in place and nothing activates without an explicit call, so production ranking can
 * never change silently.
 */
class ModelRegistry(
    val activeModel: ExplainableRanker? = null,
    private val retained: Map<String, ExplainableRanker> = emptyMap(),
) {
    val activeVersion: String?
        get() = activeModel?.modelVersion

    val knownVersions: Set<String>
        get() = retained.keys + listOfNotNull(activeModel?.modelVersion)

    fun activate(model: ExplainableRanker): ModelRegistry {
        val next = retained.toMutableMap()
        activeModel?.let { next[it.modelVersion] = it }
        next[model.modelVersion] = model
        return ModelRegistry(activeModel = model, retained = next)
    }

    /** Returns a registry with [version] active, or null when the version was never registered. */
    fun rollback(version: String): ModelRegistry? {
        val target = modelFor(version) ?: return null
        val next = retained.toMutableMap()
        activeModel?.let { next[it.modelVersion] = it }
        next[version] = target
        return ModelRegistry(activeModel = target, retained = next)
    }

    fun modelFor(version: String): ExplainableRanker? =
        if (activeModel?.modelVersion == version) activeModel else retained[version]
}
