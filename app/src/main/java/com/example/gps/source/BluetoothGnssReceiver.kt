package com.example.gps.source

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.gps.FixQuality
import com.example.gps.FixSource
import com.example.gps.GnssFix
import com.example.gps.crs.GeodeticFrame
import com.example.gps.nmea.GgaSentence
import com.example.gps.nmea.NmeaParser
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

data class BondedReceiver(val name: String, val address: String)

/**
 * External GNSS/RTK receiver over Bluetooth Classic SPP. Reads NMEA (GGA quality + GST
 * accuracy) and accepts RTCM corrections to write back to the receiver.
 */
class BluetoothGnssReceiver(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null

    fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BondedReceiver> {
        if (!hasConnectPermission()) return emptyList()
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching { adapter.bondedDevices.map { BondedReceiver(it.name ?: it.address, it.address) } }.getOrDefault(emptyList())
    }

    /** Blocking read loop. [frame] is the datum the receiver outputs once corrected (NYSNet: NAD83). */
    @SuppressLint("MissingPermission")
    fun run(
        address: String,
        correctedFrame: GeodeticFrame,
        onFix: (GnssFix, GgaSentence) -> Unit,
        shouldContinue: () -> Boolean,
    ) {
        if (!hasConnectPermission()) throw SecurityException("Bluetooth permission not granted")
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("Bluetooth not available")
        val device = adapter.getRemoteDevice(address)
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        runCatching { adapter.cancelDiscovery() }
        s.connect()
        socket = s
        output = s.outputStream
        try {
            val reader = s.inputStream.bufferedReader(Charsets.US_ASCII)
            var lastSigma: Double? = null
            while (shouldContinue()) {
                val line = reader.readLine() ?: throw IOException("Receiver disconnected")
                NmeaParser.parseGst(line)?.let { lastSigma = it.horizontalSigmaMeters }
                val gga = NmeaParser.parseGga(line) ?: continue
                if (gga.quality == 0) continue
                val quality = when (gga.quality) {
                    4 -> FixQuality.RTK_FIXED
                    5 -> FixQuality.RTK_FLOAT
                    2, 3, 9 -> FixQuality.DGPS
                    else -> FixQuality.AUTONOMOUS
                }
                val corrected = quality == FixQuality.RTK_FIXED || quality == FixQuality.RTK_FLOAT || quality == FixQuality.DGPS
                // Without GST, fall back to HDOP × typical UERE per solution type (an estimate).
                val sigma = lastSigma ?: ((gga.hdop ?: 2.0) * when (quality) {
                    FixQuality.RTK_FIXED -> 0.02
                    FixQuality.RTK_FLOAT -> 0.3
                    FixQuality.DGPS -> 0.7
                    else -> 2.5
                })
                onFix(
                    GnssFix(
                        latitude = gga.latitude,
                        longitude = gga.longitude,
                        altitudeMeters = gga.altitudeMeters,
                        horizontalAccuracyMeters = sigma.toFloat(),
                        source = FixSource.EXTERNAL_RECEIVER,
                        quality = quality,
                        frame = if (corrected) correctedFrame else GeodeticFrame.ITRF,
                        timeMillis = System.currentTimeMillis(),
                        satellitesUsed = gga.satellites,
                        accuracyFromReceiver = lastSigma != null,
                    ),
                    gga,
                )
            }
        } finally {
            close()
        }
    }

    /** Forwards RTCM correction bytes from NTRIP to the receiver. */
    fun writeCorrections(bytes: ByteArray, count: Int) {
        runCatching { output?.write(bytes, 0, count); output?.flush() }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
