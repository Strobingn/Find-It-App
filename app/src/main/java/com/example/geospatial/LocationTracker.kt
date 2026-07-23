package com.example.geospatial

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A device GPS fix, decoupled from any Android location type so callers stay testable. */
data class LocationFix(val latitude: Double, val longitude: Double, val accuracyMeters: Float)

/**
 * Wraps the platform [LocationManager] behind a plain [Flow] — no Google Play Services /
 * play-services-location dependency, so GPS tracking works on AOSP and Play-Services-less
 * devices too. Prefers GPS_PROVIDER (this app is built for outdoor field survey use) and falls
 * back to NETWORK_PROVIDER only if GPS itself is unavailable/disabled.
 */
class LocationTracker(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun locationUpdates(minIntervalMillis: Long = 3_000L, minDistanceMeters: Float = 1f): Flow<LocationFix> =
        callbackFlow {
            if (!hasLocationPermission()) {
                close()
                return@callbackFlow
            }
            // GPS_PROVIDER requires ACCESS_FINE_LOCATION specifically — a coarse-only grant still
            // passes hasLocationPermission() above, so this must be checked separately or
            // requestLocationUpdates() throws SecurityException at runtime (lint's
            // @SuppressLint("MissingPermission") only silences the warning, not the actual throw).
            val hasFineLocation = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            val provider = when {
                hasFineLocation && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                    LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                    LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider == null) {
                close()
                return@callbackFlow
            }
            val listener = LocationListener { location: Location ->
                trySend(LocationFix(location.latitude, location.longitude, location.accuracy))
            }
            val requested = runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    minIntervalMillis,
                    minDistanceMeters,
                    listener,
                    Looper.getMainLooper(),
                )
            }.isSuccess
            if (!requested) {
                close()
                return@callbackFlow
            }
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()?.let { last ->
                trySend(LocationFix(last.latitude, last.longitude, last.accuracy))
            }
            awaitClose { locationManager.removeUpdates(listener) }
        }
}
