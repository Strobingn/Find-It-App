package com.example.gps.source

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.example.gps.FixQuality
import com.example.gps.FixSource
import com.example.gps.GnssFix
import com.example.gps.crs.GeodeticFrame
import java.util.concurrent.Executor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/** Live constellation status for the on-screen GNSS panel. */
data class GnssStatusSnapshot(
    val satellitesVisible: Int = 0,
    val satellitesUsed: Int = 0,
    /** Used satellites tracked on L5/E5a (1176.45 MHz): the phone is computing dual-frequency. */
    val l5SatellitesUsed: Int = 0,
    /** The chipset reported any L5/E5a signal, so the hardware is dual-frequency capable. */
    val dualFrequencyCapable: Boolean = false,
    /** Raw GNSS measurements are being delivered (full tracking requested where supported). */
    val rawMeasurementsActive: Boolean = false,
    val constellations: Set<String> = emptySet(),
)

/**
 * Phone GNSS at the highest quality and fastest rate the platform allows. Uses the GPS
 * provider directly (no Play Services), so fixes are raw chipset output in ITRF/WGS84.
 * Mock-provider fixes (Lefebure NTRIP, SW Maps, external receivers) are reported as such.
 */
class PhoneGnssSource(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _status = MutableStateFlow(GnssStatusSnapshot())
    val status: StateFlow<GnssStatusSnapshot> = _status.asStateFlow()

    fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun fixes(): Flow<GnssFix> = callbackFlow {
        if (!hasFinePermission() || !LocationManagerCompat.isLocationEnabled(locationManager)) {
            close(); return@callbackFlow
        }
        val handler = Handler(Looper.getMainLooper())
        val executor = Executor { handler.post(it) }
        val listener = LocationListenerCompat { location -> trySend(location.toFix()) }
        val request = LocationRequestCompat.Builder(0L)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(0L)
            .setMinUpdateDistanceMeters(0f)
            .build()
        val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else {
            LocationManager.FUSED_PROVIDER.takeIf { Build.VERSION.SDK_INT >= 31 } ?: LocationManager.NETWORK_PROVIDER
        }
        val started = runCatching {
            LocationManagerCompat.requestLocationUpdates(locationManager, provider, request, executor, listener)
        }.isSuccess
        if (!started) { close(); return@callbackFlow }

        val statusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                var l5 = 0
                var dual = _status.value.dualFrequencyCapable
                val constellations = HashSet<String>()
                for (i in 0 until status.satelliteCount) {
                    val isL5 = Build.VERSION.SDK_INT >= 26 && status.hasCarrierFrequencyHz(i) &&
                        status.getCarrierFrequencyHz(i).let { it in 1.164e9f..1.189e9f }
                    if (isL5) dual = true
                    if (status.usedInFix(i)) {
                        used++
                        if (isL5) l5++
                        constellations += constellationName(status.getConstellationType(i))
                    }
                }
                _status.value = _status.value.copy(
                    satellitesVisible = status.satelliteCount,
                    satellitesUsed = used,
                    l5SatellitesUsed = l5,
                    dualFrequencyCapable = dual,
                    constellations = constellations,
                )
            }
        }
        runCatching { locationManager.registerGnssStatusCallback(statusCallback, handler) }

        val measurementCallback = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                val dual = event.measurements.any { it.hasCarrierFrequencyHz() && it.carrierFrequencyHz in 1.164e9f..1.189e9f }
                _status.value = _status.value.copy(
                    rawMeasurementsActive = true,
                    dualFrequencyCapable = _status.value.dualFrequencyCapable || dual,
                )
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                // Full tracking disables duty cycling: continuous carrier tracking on L1 and L5.
                val req = android.location.GnssMeasurementRequest.Builder().setFullTracking(true).build()
                locationManager.registerGnssMeasurementsCallback(req, executor, measurementCallback)
            } else {
                @Suppress("DEPRECATION")
                locationManager.registerGnssMeasurementsCallback(measurementCallback, handler)
            }
        }

        awaitClose {
            LocationManagerCompat.removeUpdates(locationManager, listener)
            runCatching { locationManager.unregisterGnssStatusCallback(statusCallback) }
            runCatching { locationManager.unregisterGnssMeasurementsCallback(measurementCallback) }
        }
    }

    private fun Location.toFix(): GnssFix {
        val mock = LocationCompat.isMock(this)
        return GnssFix(
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = if (hasAltitude()) altitude else null,
            horizontalAccuracyMeters = if (hasAccuracy()) accuracy else Float.NaN,
            source = if (mock) FixSource.MOCK_PROVIDER else FixSource.PHONE,
            // Mock providers carry no standard fix-type field, so they stay "estimated".
            quality = if (mock) FixQuality.ESTIMATED else FixQuality.AUTONOMOUS,
            frame = GeodeticFrame.ITRF,
            timeMillis = time,
            satellitesUsed = _status.value.satellitesUsed.takeIf { it > 0 },
        )
    }

    private fun constellationName(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        else -> "Other"
    }
}
