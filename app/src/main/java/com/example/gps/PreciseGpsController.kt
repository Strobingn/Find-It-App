package com.example.gps

import android.content.Context
import com.example.gps.averaging.PreciseFixAverager
import com.example.gps.crs.GeodeticFrame
import com.example.gps.ntrip.NtripAuthException
import com.example.gps.ntrip.NtripClient
import com.example.gps.ntrip.NtripPresets
import com.example.gps.nmea.NmeaParser
import com.example.gps.source.BluetoothGnssReceiver
import com.example.gps.source.GnssStatusSnapshot
import com.example.gps.source.PhoneGnssSource
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RtkSessionState(
    val connected: Boolean = false,
    val bytesReceived: Long = 0,
    val message: String? = null,
)

data class PreciseGpsState(
    val fix: GnssFix? = null,
    val status: GnssStatusSnapshot = GnssStatusSnapshot(),
    val externalReceiver: Boolean = false,
    val receiverConnected: Boolean = false,
    val rtk: RtkSessionState = RtkSessionState(),
    val averaging: PreciseFixAverager.Progress? = null,
    /** Last completed precise (averaged) fix. */
    val preciseFix: GnssFix? = null,
    val error: String? = null,
)

/**
 * Facade over phone GNSS, an external Bluetooth receiver and the NTRIP client. Self-contained:
 * depends only on Android framework/androidx and this package.
 */
class PreciseGpsController(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val phone = PhoneGnssSource(appContext)
    private val receiver = BluetoothGnssReceiver(appContext)
    val settingsStore = GpsSettingsStore(appContext)
    private val _state = MutableStateFlow(PreciseGpsState())
    val state: StateFlow<PreciseGpsState> = _state.asStateFlow()
    private val _fixes = MutableSharedFlow<GnssFix>(extraBufferCapacity = 16)
    /** Every live fix (and the final precise fix) for map placement and logging. */
    val fixes: SharedFlow<GnssFix> = _fixes.asSharedFlow()
    private var sourceJob: Job? = null
    private var ntripJob: Job? = null
    private var averager: PreciseFixAverager? = null
    @Volatile private var lastGga: String? = null
    private val rtcmBytes = AtomicLong(0)

    fun bondedReceivers() = receiver.bondedDevices()

    fun start() {
        if (sourceJob?.isActive == true) return
        val settings = settingsStore.load()
        _state.value = _state.value.copy(externalReceiver = settings.useExternalReceiver, error = null)
        sourceJob = scope.launch {
            // Phone GPS always runs. An external receiver is extra, not a replacement,
            // so AR still gets a position when the receiver is off or not paired.
            launch { phone.status.collect { s -> _state.value = _state.value.copy(status = s) } }
            launch {
                phone.fixes().catch { e ->
                    _state.value = _state.value.copy(error = e.message ?: "Phone GPS stopped")
                }.collect(::onFix)
            }
            if (settings.useExternalReceiver && settings.receiverAddress.isNotBlank()) {
                launch(Dispatchers.IO) { runExternal(settings) }
            }
        }
    }

    fun stop() {
        sourceJob?.cancel(); sourceJob = null
        ntripJob?.cancel(); ntripJob = null
        receiver.close()
        _state.value = _state.value.copy(receiverConnected = false, rtk = RtkSessionState())
    }

    fun restart() { stop(); start() }

    private suspend fun runExternal(settings: GpsSettings) {
        val job = coroutineContext[Job] ?: return
        val preset = NtripPresets.byId(settings.presetId)
        val correctedFrame = if (preset.correctedFrameIsNad83) GeodeticFrame.NAD83_2011 else GeodeticFrame.ITRF
        if (settings.ntripEnabled && settings.ntripConfig().isComplete) startNtrip(settings)
        var backoff = 2_000L
        while (job.isActive) {
            try {
                _state.value = _state.value.copy(receiverConnected = false, error = null)
                receiver.run(
                    address = settings.receiverAddress,
                    correctedFrame = correctedFrame,
                    onFix = { fix, _ ->
                        _state.value = _state.value.copy(receiverConnected = true)
                        lastGga = NmeaParser.buildGga(fix.latitude, fix.longitude, fix.altitudeMeters ?: 0.0, System.currentTimeMillis(), satellites = fix.satellitesUsed ?: 12)
                        onFix(fix)
                    },
                    shouldContinue = { job.isActive },
                )
            } catch (e: SecurityException) {
                _state.value = _state.value.copy(error = "Bluetooth permission needed for the external receiver")
                return
            } catch (e: Exception) {
                _state.value = _state.value.copy(receiverConnected = false, error = "Receiver: ${e.message}")
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun startNtrip(settings: GpsSettings) {
        ntripJob?.cancel()
        ntripJob = scope.launch(Dispatchers.IO) {
            val client = NtripClient(settings.ntripConfig())
            var backoff = 3_000L
            while (isActive) {
                try {
                    _state.value = _state.value.copy(rtk = _state.value.rtk.copy(message = "Connecting to ${settings.host}…"))
                    client.stream(
                        gga = { lastGga },
                        onRtcm = { bytes, count ->
                            receiver.writeCorrections(bytes, count)
                            val total = rtcmBytes.addAndGet(count.toLong())
                            if (!_state.value.rtk.connected || total % 8_192 < count) {
                                _state.value = _state.value.copy(rtk = RtkSessionState(true, total, "Corrections streaming"))
                            }
                        },
                        shouldContinue = { isActive },
                    )
                } catch (e: NtripAuthException) {
                    _state.value = _state.value.copy(rtk = RtkSessionState(false, rtcmBytes.get(), e.message))
                    return@launch
                } catch (e: Exception) {
                    _state.value = _state.value.copy(rtk = RtkSessionState(false, rtcmBytes.get(), "NTRIP: ${e.message} — retrying"))
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun onFix(fix: GnssFix) {
        val avg = averager
        val progress = avg?.add(fix)
        var precise = _state.value.preciseFix
        if (progress != null && progress.done) {
            averager = null
            precise = progress.estimate
            precise?.let { _fixes.tryEmit(it) }
        }
        _state.value = _state.value.copy(fix = fix, averaging = progress ?: _state.value.averaging?.takeIf { !it.done }, preciseFix = precise)
        _fixes.tryEmit(fix)
    }

    /** Starts a 30–60 s accuracy-weighted average (stops early once the estimate is ≤ 1 m). */
    fun startPreciseFix() {
        averager = PreciseFixAverager()
        _state.value = _state.value.copy(averaging = PreciseFixAverager.Progress(0, 0, 0, null, false, false), preciseFix = null)
        start()
    }

    fun cancelPreciseFix() {
        averager = null
        _state.value = _state.value.copy(averaging = null)
    }

    fun fetchMountpoints(settings: GpsSettings, onResult: (Result<List<com.example.gps.ntrip.NtripMountpoint>>) -> Unit) {
        scope.launch {
            val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { NtripClient(settings.ntripConfig()).fetchSourceTable() }
            }
            onResult(result)
        }
    }
}
