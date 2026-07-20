package com.strobingn.findit.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.strobingn.findit.data.model.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Live GPS for log-find + map follow-me.
 * [lowPower] uses balanced priority and slower updates for all-day hunts.
 */
class LocationTracker(private val context: Context) {
  private val client = LocationServices.getFusedLocationProviderClient(context)

  private val _lastFix = MutableStateFlow<LocationFix?>(null)
  val lastFix: StateFlow<LocationFix?> = _lastFix.asStateFlow()

  fun hasPermission(): Boolean {
    val fine =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val coarse =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    return fine || coarse
  }

  @SuppressLint("MissingPermission")
  suspend fun lastKnownOrNull(): LocationFix? {
    if (!hasPermission()) return null
    return suspendCancellableCoroutine { cont ->
      client.lastLocation
        .addOnSuccessListener { loc ->
          val fix = loc?.toFix()
          if (fix != null) _lastFix.value = fix
          cont.resume(fix)
        }
        .addOnFailureListener { cont.resume(null) }
    }
  }

  @SuppressLint("MissingPermission")
  fun updates(lowPower: Boolean): Flow<LocationFix> = callbackFlow {
    if (!hasPermission()) {
      close()
      return@callbackFlow
    }
    val interval = if (lowPower) 8_000L else 2_000L
    val request =
      LocationRequest.Builder(
          if (lowPower) Priority.PRIORITY_BALANCED_POWER_ACCURACY
          else Priority.PRIORITY_HIGH_ACCURACY,
          interval,
        )
        .setMinUpdateIntervalMillis(interval / 2)
        .setMinUpdateDistanceMeters(if (lowPower) 5f else 1f)
        .build()

    val callback =
      object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
          val loc = result.lastLocation ?: return
          val fix = loc.toFix()
          _lastFix.value = fix
          trySend(fix)
        }
      }

    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    runCatching {
      client.lastLocation.addOnSuccessListener { loc ->
        loc?.toFix()?.let {
          _lastFix.value = it
          trySend(it)
        }
      }
    }
    awaitClose { client.removeLocationUpdates(callback) }
  }

  data class LocationFix(
    val point: GeoPoint,
    val accuracyM: Float?,
    val epochMs: Long,
  )

  private fun Location.toFix(): LocationFix =
    LocationFix(
      point =
        GeoPoint(
          lat = latitude,
          lng = longitude,
          elevM = if (hasAltitude()) altitude else null,
        ),
      accuracyM = if (hasAccuracy()) accuracy else null,
      epochMs = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
    )

  companion object {
    @Volatile private var instance: LocationTracker? = null

    fun get(context: Context): LocationTracker =
      instance
        ?: synchronized(this) {
          instance ?: LocationTracker(context.applicationContext).also { instance = it }
        }
  }
}
