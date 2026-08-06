package com.example.data.field

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos

/**
 * Pure AR field-guidance math for camera + compass overlays.
 *
 * This is **not** Google ARCore world-mesh anchoring. It projects a GPS target onto a live
 * camera / fallback view using device heading and distance — reliable under canopy with a
 * non-AR (compass-only) fallback when the camera is unavailable.
 */
object ArGuidance {

    const val ARRIVAL_METERS: Float = 15f
    const val NEAR_METERS: Float = 40f

    data class OverlayState(
        val distanceMeters: Double?,
        val targetBearingTrue: Float?,
        val turnDegrees: Float?,
        val instruction: String,
        /** Horizontal reticle offset in [-1, 1]; negative = left of center. */
        val reticleOffsetX: Float,
        /** Vertical offset bias: closer targets sit slightly lower in the view (0..1). */
        val reticleOffsetY: Float,
        val isArrived: Boolean,
        val isNear: Boolean,
        val hasGps: Boolean,
        val hasHeading: Boolean,
        val accuracyWarning: String?,
        val honestyLine: String = "GPS + compass aid only — LiDAR does not prove buried metal, age, or depth.",
    )

    /**
     * @param magneticTargetBearing preferred when available (phone compass is magnetic).
     * @param trueTargetBearing used when magnetic is null.
     * @param headingDegrees device compass heading (magnetic if magnetic bearing is used).
     */
    fun compute(
        distanceMeters: Double?,
        trueTargetBearing: Float?,
        magneticTargetBearing: Float?,
        headingDegrees: Float?,
        accuracyMeters: Float? = null,
    ): OverlayState {
        val bearingForTurn = magneticTargetBearing ?: trueTargetBearing
        val turn = if (bearingForTurn != null && headingDegrees != null) {
            FieldNavigation.signedTurnDegrees(headingDegrees, bearingForTurn)
        } else {
            null
        }
        val hasGps = distanceMeters != null
        val arrived = distanceMeters != null && distanceMeters <= ARRIVAL_METERS
        val near = distanceMeters != null && distanceMeters <= NEAR_METERS
        val offsetX = reticleOffsetX(turn)
        val offsetY = reticleOffsetY(distanceMeters)
        val instruction = when {
            !hasGps -> "Waiting for GPS — enable location to aim AR"
            headingDegrees == null -> "Hold phone upright — waiting for compass"
            arrived -> "Arrived — start swinging · mark outcome when done"
            turn != null && abs(turn) >= 8f -> FieldNavigation.turnInstruction(turn)
            near -> "Near target — slow walk, keep reticle centered"
            else -> "Target ahead — walk toward reticle"
        }
        val accuracyWarning = when {
            accuracyMeters == null -> null
            accuracyMeters > 25f -> "GPS accuracy ~${accuracyMeters.toInt()} m — reticle may drift"
            accuracyMeters > 12f -> "GPS accuracy ~${accuracyMeters.toInt()} m"
            else -> null
        }
        return OverlayState(
            distanceMeters = distanceMeters,
            targetBearingTrue = trueTargetBearing,
            turnDegrees = turn,
            instruction = instruction,
            reticleOffsetX = offsetX,
            reticleOffsetY = offsetY,
            isArrived = arrived,
            isNear = near,
            hasGps = hasGps,
            hasHeading = headingDegrees != null,
            accuracyWarning = accuracyWarning,
        )
    }

    fun reticleOffsetX(turnDegrees: Float?): Float {
        if (turnDegrees == null) return 0f
        // Map ±90° turn to ±1 screen half-width; beyond that clamp.
        return (turnDegrees / 90f).coerceIn(-1f, 1f)
    }

    fun reticleOffsetY(distanceMeters: Double?): Float {
        if (distanceMeters == null) return 0.15f
        // Far: higher in frame; near: lower (feet).
        val t = (distanceMeters / 120.0).coerceIn(0.0, 1.0)
        return (0.35 - 0.25 * t).toFloat()
    }

    /** Unit direction of the turn for drawing an arrow (dx, dy) in screen space (y down). */
    fun turnArrowUnit(turnDegrees: Float?): Pair<Float, Float> {
        if (turnDegrees == null) return 0f to -1f
        val rad = Math.toRadians(turnDegrees.toDouble())
        // 0° = up; positive turn = right
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        return dx to dy
    }
}
