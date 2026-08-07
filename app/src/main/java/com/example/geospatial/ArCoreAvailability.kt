package com.example.geospatial

import android.content.Context
import android.content.pm.PackageManager

/**
 * Detects whether Google ARCore can be used on this device.
 *
 * Uses package / meta-data probes so unit tests and devices without Play Services AR still
 * compile and run. When ARCore APIs are on the classpath, [probeArCoreApk] may be refined
 * later; the UI always falls back to camera/compass guidance.
 */
object ArCoreAvailability {

    const val ARCORE_PACKAGE = "com.google.ar.core"

    enum class Status {
        /** ARCore present or installable — world-anchor session may start. */
        SUPPORTED,
        /** No ARCore package; use camera + compass world-anchor math only. */
        UNSUPPORTED,
        /** Unknown (e.g. query failed). */
        UNKNOWN,
    }

    fun status(context: Context): Status = try {
        // Classpath probe: ARCore SDK present in APK.
        val sdkPresent = runCatching {
            Class.forName("com.google.ar.core.Session")
            true
        }.getOrDefault(false)
        val pm = context.packageManager
        val apkInstalled = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ARCORE_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        when {
            apkInstalled && sdkPresent -> Status.SUPPORTED
            sdkPresent -> Status.UNSUPPORTED // SDK linked but service APK missing
            else -> Status.UNSUPPORTED
        }
    } catch (_: Exception) {
        Status.UNKNOWN
    }

    fun isSupported(context: Context): Boolean = status(context) == Status.SUPPORTED

    fun statusLabel(status: Status): String = when (status) {
        Status.SUPPORTED -> "ARCore available"
        Status.UNSUPPORTED -> "ARCore not installed — using compass world-anchor projection"
        Status.UNKNOWN -> "ARCore status unknown"
    }
}
