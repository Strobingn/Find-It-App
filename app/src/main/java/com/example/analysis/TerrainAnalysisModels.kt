package com.example.analysis

import kotlin.math.sqrt

/** Roadmap grouping used by the Analysis UI. Hydrology remains implemented but is intentionally last. */
enum class TerrainAnalysisPhase {
    CORE,
    HYDROLOGY,
}

/** Every locally-computed LiDAR/DEM product supported by the engine. */
enum class TerrainAnalysisType(
    val title: String,
    val description: String,
    val unit: String,
    val phase: TerrainAnalysisPhase,
    val diverging: Boolean = false,
    val categorical: Boolean = false,
    val usesLocalRadius: Boolean = false,
    val usesHorizon: Boolean = false,
) {
    MULTI_HILLSHADE(
        title = "Multi-direction hillshade",
        description = "Blends illumination from four directions to reduce directional bias.",
        unit = "intensity",
        phase = TerrainAnalysisPhase.CORE,
    ),
    SKY_VIEW_FACTOR(
        title = "Sky-View Factor",
        description = "Estimates how much of the sky hemisphere is visible from each cell.",
        unit = "fraction",
        phase = TerrainAnalysisPhase.CORE,
        usesHorizon = true,
    ),
    LOCAL_RELIEF_MODEL(
        title = "Local Relief Model",
        description = "Removes broad terrain trend to expose subtle banks, pits, platforms and walls.",
        unit = "m",
        phase = TerrainAnalysisPhase.CORE,
        diverging = true,
        usesLocalRadius = true,
    ),
    POSITIVE_OPENNESS(
        title = "Positive openness",
        description = "Highlights exposed ridges, banks and raised structures.",
        unit = "degrees",
        phase = TerrainAnalysisPhase.CORE,
        usesHorizon = true,
    ),
    NEGATIVE_OPENNESS(
        title = "Negative openness",
        description = "Highlights enclosed hollows, ditches, pits and cellar depressions.",
        unit = "degrees",
        phase = TerrainAnalysisPhase.CORE,
        usesHorizon = true,
    ),
    SLOPE(
        title = "Slope",
        description = "Maximum terrain gradient calculated with a Horn 3×3 operator.",
        unit = "degrees",
        phase = TerrainAnalysisPhase.CORE,
    ),
    CURVATURE(
        title = "Curvature",
        description = "Second derivative surface separating convex and concave landforms.",
        unit = "1/m",
        phase = TerrainAnalysisPhase.CORE,
        diverging = true,
    ),
    ASPECT(
        title = "Aspect",
        description = "Downslope compass direction; flat cells are marked neutral.",
        unit = "degrees",
        phase = TerrainAnalysisPhase.CORE,
        categorical = true,
    ),
    RUGGEDNESS_INDEX(
        title = "Terrain Ruggedness Index",
        description = "RMS elevation difference between each cell and its neighbors.",
        unit = "m",
        phase = TerrainAnalysisPhase.CORE,
    ),
    FLOW_ACCUMULATION(
        title = "Flow accumulation",
        description = "D8 upstream contributing-cell count using the bare-earth surface.",
        unit = "cells",
        phase = TerrainAnalysisPhase.HYDROLOGY,
    ),
    WATERSHED(
        title = "Watersheds",
        description = "Labels drainage basins by their terminal outlet or internal sink.",
        unit = "basin",
        phase = TerrainAnalysisPhase.HYDROLOGY,
        categorical = true,
    ),
    DEPRESSION_DEPTH(
        title = "Depression finder",
        description = "Priority-flood fill depth for closed pits and enclosed depressions.",
        unit = "m",
        phase = TerrainAnalysisPhase.HYDROLOGY,
    ),
    ANCIENT_STREAM_LIKELIHOOD(
        title = "Ancient stream reconstruction",
        description = "Ranks valley-like corridors from flow, concavity and low-gradient terrain.",
        unit = "score",
        phase = TerrainAnalysisPhase.HYDROLOGY,
    ),
    EROSION_SIMULATION(
        title = "Erosion simulation",
        description = "Relative erosion/deposition estimate from drainage, slope and curvature.",
        unit = "relative change",
        phase = TerrainAnalysisPhase.HYDROLOGY,
        diverging = true,
    ),
    ;

    companion object {
        /** The complete Phase 1 catalog, in the order shown to the user. */
        val phaseOneEntries: List<TerrainAnalysisType> = entries.filter { it.phase == TerrainAnalysisPhase.CORE }
    }
}

data class TerrainAnalysisOptions(
    val localRadiusMeters: Float = 12f,
    val horizonRadiusMeters: Float = 40f,
    val directionCount: Int = 12,
    val erosionIterations: Int = 30,
    val rainfallFactor: Float = 1f,
) {
    fun normalized(cellSizeMeters: Float): TerrainAnalysisOptions = copy(
        localRadiusMeters = localRadiusMeters.coerceAtLeast(cellSizeMeters),
        horizonRadiusMeters = horizonRadiusMeters.coerceAtLeast(cellSizeMeters * 2f),
        directionCount = directionCount.coerceIn(8, 24),
        erosionIterations = erosionIterations.coerceIn(1, 100),
        rainfallFactor = rainfallFactor.coerceIn(0.1f, 5f),
    )
}

data class TerrainAnalysisLayer(
    val type: TerrainAnalysisType,
    val width: Int,
    val height: Int,
    val values: FloatArray,
    val validData: BooleanArray,
    val cellSizeMeters: Float,
    val minimum: Float,
    val maximum: Float,
    val mean: Float,
    val percentile95: Float,
    val summary: String,
) {
    init {
        require(width > 0 && height > 0)
        require(values.size == width * height)
        require(validData.size == width * height)
        require(cellSizeMeters > 0f && cellSizeMeters.isFinite())
    }

    val validCellCount: Int
        get() = validData.count { it }

    val coveragePercent: Float
        get() = if (validData.isEmpty()) 0f else validCellCount * 100f / validData.size

    val standardDeviation: Float
        get() {
            var squaredDifferenceSum = 0.0
            var count = 0
            for (index in values.indices) {
                if (!validData[index]) continue
                val value = values[index]
                if (!value.isFinite() || type == TerrainAnalysisType.ASPECT && value < 0f) continue
                val difference = value - mean
                squaredDifferenceSum += difference * difference
                count++
            }
            return if (count == 0) 0f else sqrt(squaredDifferenceSum / count).toFloat()
        }

    fun aiSummary(): String = buildString {
        append(type.title)
        append(": ")
        append(summary)
        append(" Grid ")
        append(width)
        append('x')
        append(height)
        append(", cell size ")
        append("%.2f".format(cellSizeMeters))
        append(" m, valid coverage ")
        append("%.1f".format(coveragePercent))
        append("%, min ")
        append("%.4f".format(minimum))
        append(", mean ")
        append("%.4f".format(mean))
        append(", standard deviation ")
        append("%.4f".format(standardDeviation))
        append(", p95 ")
        append("%.4f".format(percentile95))
        append(", max ")
        append("%.4f".format(maximum))
        append(' ')
        append(type.unit)
        append('.')
    }
}
