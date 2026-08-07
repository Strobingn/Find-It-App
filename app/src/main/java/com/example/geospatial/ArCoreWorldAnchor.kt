package com.example.geospatial

import com.example.data.field.ArGuidance
import com.example.data.field.FieldNavigation
import kotlin.math.cos
import kotlin.math.sin

/**
 * World-anchor math for outdoor AR guidance.
 *
 * Places a virtual target relative to the device using GPS distance + bearing, then projects
 * that local ENU offset into camera-facing screen space. When Google ARCore is available the
 * UI can lock this pose as an ARCore [com.google.ar.core.Anchor]; when not, the same math
 * drives the greyscale reticle (visual-odometry-free fallback).
 *
 * This is **not** ARCore Geospatial / VPS (no cloud localization key required).
 */
object ArCoreWorldAnchor {

    data class LocalOffsetMeters(
        /** East meters from device. */
        val east: Double,
        /** North meters from device. */
        val north: Double,
        /** Up meters (0 for ground targets). */
        val up: Double,
        val distanceMeters: Double,
        val bearingTrueDegrees: Float,
    )

    data class ScreenProjection(
        /** Normalized x in [-1, 1] where 0 is center; +1 is right edge. */
        val x: Float,
        /** Normalized y in [-1, 1] where 0 is center; +1 is bottom (screen-down). */
        val y: Float,
        val depthMeters: Double,
        val inFront: Boolean,
    )

    data class AnchorState(
        val offset: LocalOffsetMeters?,
        val projection: ScreenProjection?,
        val instruction: String,
        val isArrived: Boolean,
        val modeLabel: String,
        val honestyLine: String =
            "AR world anchor is a field aim aid — LiDAR does not prove buried metal, age, or depth.",
    )

    fun localOffset(
        deviceLat: Double,
        deviceLon: Double,
        targetLat: Double,
        targetLon: Double,
    ): LocalOffsetMeters {
        val dist = FieldNavigation.distanceMeters(deviceLat, deviceLon, targetLat, targetLon)
        val bearing = FieldNavigation.bearingDegrees(deviceLat, deviceLon, targetLat, targetLon)
        val rad = Math.toRadians(bearing.toDouble())
        // ENU: east = sin(bearing)*d, north = cos(bearing)*d
        return LocalOffsetMeters(
            east = sin(rad) * dist,
            north = cos(rad) * dist,
            up = 0.0,
            distanceMeters = dist,
            bearingTrueDegrees = bearing,
        )
    }

    /**
     * Projects a local ENU offset into screen-normalized coords given device heading (degrees
     * clockwise from north) and a simple pinhole model (no full ARCore matrices required for
     * domain tests).
     *
     * @param headingDegrees device compass heading (magnetic or true; must match bearing frame)
     * @param horizontalFovDegrees approximate camera HFOV (default ~60°)
     */
    fun projectToScreen(
        offset: LocalOffsetMeters,
        headingDegrees: Float,
        horizontalFovDegrees: Float = 60f,
    ): ScreenProjection {
        val rad = Math.toRadians(headingDegrees.toDouble())
        // Rotate ENU into body frame: forward = N*cos + E*sin, right = E*cos - N*sin
        val forward = offset.north * cos(rad) + offset.east * sin(rad)
        val right = offset.east * cos(rad) - offset.north * sin(rad)
        val inFront = forward > 0.5
        val halfFov = Math.toRadians((horizontalFovDegrees / 2.0).coerceIn(15.0, 75.0))
        val x = if (forward > 0.25) {
            (kotlin.math.atan2(right, forward) / halfFov).toFloat().coerceIn(-1.5f, 1.5f)
                .coerceIn(-1f, 1f)
        } else {
            // Behind: push reticle to side
            if (right >= 0) 1f else -1f
        }
        val elev = kotlin.math.atan2(offset.up, kotlin.math.hypot(forward, right).coerceAtLeast(0.5))
        val y = (-elev / halfFov).toFloat().coerceIn(-1f, 1f)
        return ScreenProjection(
            x = x,
            y = y,
            depthMeters = forward.coerceAtLeast(0.0),
            inFront = inFront,
        )
    }

    fun compute(
        deviceLat: Double?,
        deviceLon: Double?,
        targetLat: Double,
        targetLon: Double,
        headingDegrees: Float?,
        arCoreAvailable: Boolean,
        accuracyMeters: Float? = null,
    ): AnchorState {
        if (deviceLat == null || deviceLon == null) {
            return AnchorState(
                offset = null,
                projection = null,
                instruction = "Waiting for GPS to place world anchor",
                isArrived = false,
                modeLabel = if (arCoreAvailable) "ARCore ready · need GPS" else "Anchor math · need GPS",
            )
        }
        val offset = localOffset(deviceLat, deviceLon, targetLat, targetLon)
        val projection = headingDegrees?.let { projectToScreen(offset, it) }
        val arrived = offset.distanceMeters <= ArGuidance.ARRIVAL_METERS
        val instruction = when {
            arrived -> "Arrived at anchor — start swinging · mark outcome when done"
            headingDegrees == null -> "Hold phone upright — waiting for compass for anchor lock"
            projection != null && !projection.inFront -> "Target behind — turn around toward reticle"
            projection != null && kotlin.math.abs(projection.x) > 0.35f ->
                if (projection.x > 0) "Turn right toward anchor" else "Turn left toward anchor"
            else -> "Walk toward world anchor · ${offset.distanceMeters.toInt()} m"
        }
        val accNote = accuracyMeters?.takeIf { it > 15f }?.let {
            " · GPS ±${it.toInt()} m"
        }.orEmpty()
        return AnchorState(
            offset = offset,
            projection = projection,
            instruction = instruction + accNote,
            isArrived = arrived,
            modeLabel = if (arCoreAvailable) {
                "ARCore world anchor (visual lock when session runs)"
            } else {
                "World-anchor math · ARCore not installed — compass projection"
            },
        )
    }
}
