package com.example.geospatial

/**
 * ARCore Geospatial / VPS pose abstraction with explicit degrade to world-anchor math.
 *
 * Full Earth tracking requires Play Services AR + Geospatial API configuration at runtime.
 * This module centralizes **decision logic** so UI never hard-fails when VPS is unavailable
 * (woods / no coverage / no install).
 */
object GeospatialPoseProvider {

    enum class TrackingMode {
        /** Geospatial Earth pose available (VPS or high-accuracy Earth). */
        GEOSPATIAL,
        /** Local ENU world-anchor projection (GPS + compass). */
        WORLD_ANCHOR,
        /** Compass/camera only — no stable anchor. */
        COMPASS_FALLBACK,
    }

    data class PoseSample(
        val mode: TrackingMode,
        val latitude: Double?,
        val longitude: Double?,
        val headingDegrees: Float?,
        val horizontalAccuracyMeters: Float?,
        val label: String,
        val geospatialReady: Boolean,
    )

    data class DegradeDecision(
        val mode: TrackingMode,
        val reason: String,
        val useGeospatialApi: Boolean,
    )

    /**
     * Pure degrade decision from capability probes (unit-testable).
     *
     * @param arCoreInstalled Play Services AR / ARCore package present
     * @param geospatialApiAvailable device reports Geospatial mode supported
     * @param vpsCoverageLikely optional hint (false under canopy / rural)
     * @param hasGps device has a fix
     * @param hasHeading compass available
     */
    fun decideMode(
        arCoreInstalled: Boolean,
        geospatialApiAvailable: Boolean,
        vpsCoverageLikely: Boolean = true,
        hasGps: Boolean,
        hasHeading: Boolean,
    ): DegradeDecision {
        if (arCoreInstalled && geospatialApiAvailable && vpsCoverageLikely && hasGps) {
            return DegradeDecision(
                mode = TrackingMode.GEOSPATIAL,
                reason = "ARCore Geospatial available with GPS",
                useGeospatialApi = true,
            )
        }
        if (hasGps && hasHeading) {
            val why = when {
                !arCoreInstalled -> "ARCore not installed"
                !geospatialApiAvailable -> "Geospatial API unavailable"
                !vpsCoverageLikely -> "VPS coverage unlikely (woods/rural) — world anchor"
                else -> "GPS + compass world anchor"
            }
            return DegradeDecision(
                mode = TrackingMode.WORLD_ANCHOR,
                reason = why,
                useGeospatialApi = false,
            )
        }
        return DegradeDecision(
            mode = TrackingMode.COMPASS_FALLBACK,
            reason = when {
                !hasGps && !hasHeading -> "No GPS or compass"
                !hasGps -> "No GPS fix"
                else -> "No compass heading"
            },
            useGeospatialApi = false,
        )
    }

    /**
     * Builds a pose sample for AR UI. When Geospatial is not active, reuses world-anchor math inputs.
     */
    fun sample(
        decision: DegradeDecision,
        deviceLat: Double?,
        deviceLon: Double?,
        headingDegrees: Float?,
        accuracyMeters: Float?,
    ): PoseSample {
        val label = when (decision.mode) {
            TrackingMode.GEOSPATIAL -> "Geospatial (VPS/Earth)"
            TrackingMode.WORLD_ANCHOR -> "World anchor · ${decision.reason}"
            TrackingMode.COMPASS_FALLBACK -> "Compass fallback · ${decision.reason}"
        }
        return PoseSample(
            mode = decision.mode,
            latitude = deviceLat,
            longitude = deviceLon,
            headingDegrees = headingDegrees,
            horizontalAccuracyMeters = accuracyMeters,
            label = label,
            geospatialReady = decision.useGeospatialApi,
        )
    }

    /**
     * Probe Geospatial API class presence without requiring a live Session.
     * Returns false on unit tests / missing ARCore geospatial classes.
     */
    fun isGeospatialApiOnClasspath(): Boolean = runCatching {
        Class.forName("com.google.ar.core.Earth")
        true
    }.getOrDefault(false)

    fun modeLabel(mode: TrackingMode): String = when (mode) {
        TrackingMode.GEOSPATIAL -> "Geospatial"
        TrackingMode.WORLD_ANCHOR -> "World anchor"
        TrackingMode.COMPASS_FALLBACK -> "Compass fallback"
    }
}
