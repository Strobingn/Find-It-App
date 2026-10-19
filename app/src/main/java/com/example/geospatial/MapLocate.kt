package com.example.geospatial

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/** One-shot "center the map on me" for the Maps screen locate button. */
object MapLocate {
    enum class Outcome { NEED_PERMISSION, NEED_PRECISE, LOCATION_OFF, READY }

    /**
     * Pure decision so it is unit-testable without Android. [hasFine] false means only approximate
     * location was granted: that can use the network provider, but never GPS.
     */
    fun decide(
        hasPermission: Boolean,
        gpsEnabled: Boolean,
        networkEnabled: Boolean,
        hasFine: Boolean = true,
    ): Outcome = when {
        !hasPermission -> Outcome.NEED_PERMISSION
        !gpsEnabled && !networkEnabled -> Outcome.LOCATION_OFF
        !hasFine && !networkEnabled -> Outcome.NEED_PRECISE
        else -> Outcome.READY
    }

    fun message(outcome: Outcome?): String = when (outcome) {
        Outcome.NEED_PERMISSION -> "Location permission is needed to find your position."
        Outcome.NEED_PRECISE -> "Only approximate location is allowed and network location is off. " +
            "Allow precise location for Find It Grok (or turn on network location) and try again."
        Outcome.LOCATION_OFF -> "Location is turned off. Turn on GPS/Location in settings and try again."
        Outcome.READY -> "Finding your position…"
        null -> "Could not get a GPS fix. Move to open sky and try again."
    }

    private fun hasFine(context: Context) = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    fun outcome(context: Context, tracker: LocationTracker): Outcome {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return decide(
            hasPermission = tracker.hasLocationPermission(),
            gpsEnabled = runCatching { lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }.getOrDefault(false),
            networkEnabled = runCatching { lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true }.getOrDefault(false),
            hasFine = hasFine(context),
        )
    }

    /** Providers whose last-known fix this permission level may read (GPS needs precise). */
    fun lastKnownProviders(hasFine: Boolean): List<String> =
        if (hasFine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        else listOf(LocationManager.NETWORK_PROVIDER)

    /** Last known fix (instant) if recent, else waits up to [timeoutMs] for a live one. */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(context: Context, tracker: LocationTracker, timeoutMs: Long = 15_000L): LocationFix? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        // Each provider separately: one SecurityException must not discard the other's fix.
        val last: Location? = lastKnownProviders(hasFine(context))
            .mapNotNull { p -> runCatching { lm?.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 2 * 60_000L) {
            return LocationFix(last.latitude, last.longitude, last.accuracy, last.time)
        }
        return withTimeoutOrNull(timeoutMs) { tracker.locationUpdates(1_000L, 0f).firstOrNull() }
            ?: last?.let { LocationFix(it.latitude, it.longitude, it.accuracy, it.time) }
    }
}
