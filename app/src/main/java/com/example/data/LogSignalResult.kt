package com.example.data

/**
 * Result of attempting to log a marker, including optional proximity warnings when another
 * find already sits nearby (field duplicate check).
 */
data class LogSignalResult(
    val signal: TargetSignal,
    val nearbyFind: TargetSignal? = null,
    val nearbyDistanceMeters: Double? = null,
)
