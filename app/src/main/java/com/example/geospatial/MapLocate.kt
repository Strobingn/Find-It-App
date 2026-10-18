package com.example.geospatial

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/** One-shot "center the map on me" for the Maps screen locate button. */
object MapLocate {
    enum class Outcome { NEED_PERMISSION, LOCATION_OFF, READY }

    /** Pure decision so it is unit-testable without Android. */
    fun decide(hasPermission: Boolean, gpsEnabled: Boolean, networkEnabled: Boolean): Outcome = when {
        !hasPermission -> Outcome.NEED_PERMISSION
        !gpsEnabled && !networkEnabled -> Outcome.LOCATION_OFF
        else -> Outcome.READY
    }

    fun message(outcome: Outcome?): String = when (outcome) {
        Outcome.NEED_PERMISSION -> "Location permission is needed to find your position."
        Outcome.LOCATION_OFF -> "Location is turned off. Turn on GPS/Location in settings and try again."
        Outcome.READY -> "Finding your position…"
        null -> "Could not get a GPS fix. Move to open sky and try again."
    }

    fun outcome(context: Context, tracker: LocationTracker): Outcome {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return decide(
            hasPermission = tracker.hasLocationPermission(),
            gpsEnabled = runCatching { lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }.getOrDefault(false),
            networkEnabled = runCatching { lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true }.getOrDefault(false),
        )
    }

    /** Last known fix (instant) if recent, else waits up to [timeoutMs] for a live one. */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(context: Context, tracker: LocationTracker, timeoutMs: Long = 15_000L): LocationFix? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val last = runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { lm?.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull()
        if (last != null && System.currentTimeMillis() - last.time < 2 * 60_000L) {
            return LocationFix(last.latitude, last.longitude, last.accuracy, last.time)
        }
        return withTimeoutOrNull(timeoutMs) { tracker.locationUpdates(1_000L, 0f).firstOrNull() }
            ?: last?.let { LocationFix(it.latitude, it.longitude, it.accuracy, it.time) }
    }
}
