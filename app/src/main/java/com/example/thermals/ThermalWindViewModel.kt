package com.example.thermals

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.TerrainAiGateway
import com.example.geospatial.DaylightPlanner
import com.example.geospatial.LocationTracker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class ThermalWeatherSource(val label: String) {
    FORECAST("Live forecast"),
    MANUAL("My temps"),
}

enum class ThermalTerrainMode(val label: String) {
    AUTO("Auto from map"),
    MANUAL("Set it myself"),
}

enum class GroundCover(val label: String, val windFactor: Double) {
    WOODS("Woods", 0.45),
    EDGE("Edge / brush", 0.6),
    OPEN("Open field", 0.75),
}

data class ManualReading(val hour: Int, val temperatureF: Double)

data class ThermalAiExchange(
    val question: String,
    val answer: String,
    val provider: String,
    /** Identifies the forecast this answer was written for, so an unchanged plan isn't re-requested. */
    val forecastKey: Int = 0,
)

data class ThermalUiState(
    val latitudeText: String = "",
    val longitudeText: String = "",
    val locationLabel: String = "",
    val source: ThermalWeatherSource = ThermalWeatherSource.MANUAL,
    val forecastDays: List<ThermalWeatherDay> = emptyList(),
    val selectedDayIndex: Int = 0,
    /** Defaults mirror the classic case: 32 °F at 6 AM warming to 66 °F by noon. */
    val manualReadings: List<ManualReading> = listOf(ManualReading(6, 32.0), ManualReading(12, 66.0)),
    val manualWindFromDegrees: Double = 315.0,
    val manualWindMph: Double = 5.0,
    val manualCloudPercent: Double = 10.0,
    val terrainMode: ThermalTerrainMode = ThermalTerrainMode.MANUAL,
    val autoTerrain: SiteTerrain? = null,
    val manualSetting: TerrainSetting = TerrainSetting.SLOPE,
    val manualUphillDegrees: Double = 90.0,
    val manualSlopeDegrees: Double = 12.0,
    val groundCover: GroundCover = GroundCover.WOODS,
    val loading: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val forecast: ThermalForecast? = null,
    val selectedHour: Int = 6,
    val aiBusy: Boolean = false,
    val aiStage: String? = null,
    val aiError: String? = null,
    val aiExchanges: List<ThermalAiExchange> = emptyList(),
    /** Text of the answer being streamed right now. */
    val aiStreamingText: String? = null,
    val aiProviderStatus: String = "",
    /** HRRR 15-minute wind steps for roughly the next 24 h. */
    val quarterHourly: List<QuarterWind> = emptyList(),
    /** Set when the NWS grid forecast disagrees with HRRR beyond the check thresholds. */
    val nwsBadge: String? = null,
) {
    val latitude: Double? get() = latitudeText.trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val longitude: Double? get() = longitudeText.trim().toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    val hasLocation: Boolean get() = latitude != null && longitude != null
    val selectedDay: ThermalWeatherDay? get() = forecastDays.getOrNull(selectedDayIndex)

    val activeTerrain: SiteTerrain
        get() = if (terrainMode == ThermalTerrainMode.AUTO && autoTerrain != null) {
            autoTerrain
        } else {
            SiteTerrain(
                setting = manualSetting,
                uphillBearingDegrees = if (manualSetting == TerrainSetting.FLAT) null else manualUphillDegrees,
                slopeDegrees = if (manualSetting == TerrainSetting.FLAT) 0.0 else manualSlopeDegrees,
                source = "Manual",
            )
        }
}

class ThermalWindViewModel(application: Application) : AndroidViewModel(application) {
    private val client = ThermalWeatherClient()
    private val advisor = ThermalAiAdvisor(application)
    private val locationTracker = LocationTracker(application)

    private val _state = MutableStateFlow(
        ThermalUiState(aiProviderStatus = TerrainAiGateway.providerStatus(application)),
    )
    val state: StateFlow<ThermalUiState> = _state.asStateFlow()

    private var fetchJob: Job? = null
    private var aiJob: Job? = null
    /** Last NWS-disagreement text already alerted for; null when NWS and HRRR agree. */
    private var lastNwsBadge: String? = null

    init {
        recompute()
    }

    fun setLatitude(text: String) = edit { copy(latitudeText = text, locationLabel = "") }

    fun setLongitude(text: String) = edit { copy(longitudeText = text, locationLabel = "") }

    /** Prefill from a loaded, georeferenced terrain site without overwriting a location the user typed. */
    fun offerSiteLocation(latitude: Double, longitude: Double, label: String) {
        if (_state.value.hasLocation) return
        useLocation(latitude, longitude, label)
    }

    fun useLocation(latitude: Double, longitude: Double, label: String) {
        edit {
            copy(
                latitudeText = String.format(Locale.US, "%.5f", latitude),
                longitudeText = String.format(Locale.US, "%.5f", longitude),
                locationLabel = label,
                forecastDays = emptyList(),
                autoTerrain = null,
            )
        }
        refreshFromNetwork()
    }

    fun useDeviceLocation() {
        if (!locationTracker.hasLocationPermission()) {
            _state.update { it.copy(error = "Location permission is needed to use GPS.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, status = "Getting GPS fix…", error = null) }
            val fix = withTimeoutOrNull(20_000L) { locationTracker.locationUpdates(minIntervalMillis = 1_000L).firstOrNull() }
            if (fix == null) {
                _state.update { it.copy(loading = false, status = null, error = "No GPS fix yet — step into the open or type coordinates.") }
            } else {
                _state.update { it.copy(loading = false, status = null) }
                useLocation(fix.latitude, fix.longitude, "My location (±%.0f m)".format(fix.accuracyMeters))
            }
        }
    }

    /** Pulls the hourly forecast and the elevation-model terrain for the current location. */
    fun refreshFromNetwork() {
        val lat = _state.value.latitude
        val lon = _state.value.longitude
        if (lat == null || lon == null) {
            _state.update { it.copy(error = "Enter a valid latitude and longitude first.") }
            return
        }
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, status = "Loading forecast and terrain…", error = null) }
            val problems = mutableListOf<String>()
            val days = try {
                client.fetchForecast(lat, lon)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                problems += "Forecast: ${error.localizedMessage ?: "unavailable"}"
                null
            }
            val terrain = try {
                client.fetchTerrain(lat, lon)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                problems += "Terrain: ${error.localizedMessage ?: "unavailable"}"
                null
            }
            val quarters = if (days != null) client.fetchQuarterHourly(lat, lon) else null
            val nws = if (days != null) client.fetchNwsWind(lat, lon) else null
            val nwsBadge = if (days != null && !nws.isNullOrEmpty()) nwsDisagreement(days, nws) else null
            // Alert once per new disagreement; resets to allow a fresh alert when models agree again.
            if (nwsBadge != null && nwsBadge != lastNwsBadge) notifyNwsDisagreement(nwsBadge)
            lastNwsBadge = nwsBadge
            _state.update { current ->
                current.copy(
                    loading = false,
                    status = null,
                    error = problems.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let { "$it — you can still enter values by hand." },
                    forecastDays = days ?: current.forecastDays,
                    selectedDayIndex = 0,
                    source = if (days.isNullOrEmpty()) current.source else ThermalWeatherSource.FORECAST,
                    autoTerrain = terrain ?: current.autoTerrain,
                    terrainMode = if (terrain != null) ThermalTerrainMode.AUTO else current.terrainMode,
                    quarterHourly = quarters ?: current.quarterHourly,
                    nwsBadge = nwsBadge,
                )
            }
            recompute()
        }
    }

    fun setSource(source: ThermalWeatherSource) {
        edit { copy(source = source) }
        if (source == ThermalWeatherSource.FORECAST && _state.value.forecastDays.isEmpty() && _state.value.hasLocation) {
            refreshFromNetwork()
        }
    }

    fun selectDay(index: Int) = edit { copy(selectedDayIndex = index.coerceIn(0, (forecastDays.size - 1).coerceAtLeast(0))) }

    fun setReading(index: Int, reading: ManualReading) = edit {
        copy(manualReadings = manualReadings.toMutableList().also { if (index in it.indices) it[index] = reading })
    }

    fun addReading() = edit {
        val nextHour = ((manualReadings.maxOfOrNull { it.hour } ?: 6) + 3).coerceAtMost(21)
        val lastTemp = manualReadings.lastOrNull()?.temperatureF ?: 50.0
        copy(manualReadings = manualReadings + ManualReading(nextHour, lastTemp))
    }

    fun removeReading(index: Int) = edit {
        if (manualReadings.size <= 1) this else copy(manualReadings = manualReadings.filterIndexed { i, _ -> i != index })
    }

    fun setManualWind(fromDegrees: Double? = null, mph: Double? = null, cloudPercent: Double? = null) = edit {
        copy(
            manualWindFromDegrees = fromDegrees ?: manualWindFromDegrees,
            manualWindMph = mph ?: manualWindMph,
            manualCloudPercent = cloudPercent ?: manualCloudPercent,
        )
    }

    fun setTerrainMode(mode: ThermalTerrainMode) {
        edit { copy(terrainMode = mode) }
        if (mode == ThermalTerrainMode.AUTO && _state.value.autoTerrain == null && _state.value.hasLocation) {
            refreshFromNetwork()
        }
    }

    fun setManualTerrain(setting: TerrainSetting? = null, uphillDegrees: Double? = null, slopeDegrees: Double? = null) = edit {
        copy(
            terrainMode = ThermalTerrainMode.MANUAL,
            manualSetting = setting ?: manualSetting,
            manualUphillDegrees = uphillDegrees ?: manualUphillDegrees,
            manualSlopeDegrees = slopeDegrees ?: manualSlopeDegrees,
        )
    }

    fun setGroundCover(cover: GroundCover) = edit { copy(groundCover = cover) }

    fun selectHour(hour: Int) = _state.update { it.copy(selectedHour = hour.coerceIn(0, 23)) }

    fun askAi(question: String?) {
        val snapshot = _state.value
        val forecast = snapshot.forecast ?: return
        val forecastKey = forecast.inputs.hashCode()
        val isPlan = question.isNullOrBlank()
        // Same conditions, same plan: reuse it instead of waiting on the cloud again.
        if (isPlan && snapshot.aiExchanges.any { it.question == PLAN_TITLE && it.forecastKey == forecastKey }) {
            val cached = snapshot.aiExchanges.last { it.question == PLAN_TITLE && it.forecastKey == forecastKey }
            _state.update { it.copy(aiExchanges = it.aiExchanges - cached + cached, aiError = null) }
            return
        }
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            _state.update { it.copy(aiBusy = true, aiError = null, aiStage = "Preparing thermal data…", aiStreamingText = null) }
            var lastPaint = 0L
            try {
                val answer = advisor.brief(
                    forecast = forecast,
                    locationLabel = snapshot.locationLabel.ifBlank { "Hunting stand" },
                    dateLabel = dateLabel(snapshot),
                    question = question,
                    history = snapshot.aiExchanges.takeLast(2).map { it.question to it.answer },
                    onStage = { stage -> _state.update { it.copy(aiStage = stage) } },
                    onPartialText = { partial ->
                        val now = System.currentTimeMillis()
                        if (partial.isEmpty() || now - lastPaint >= 66L) {
                            lastPaint = now
                            _state.update { it.copy(aiStreamingText = partial.ifEmpty { null }) }
                        }
                    },
                )
                val provider = answer.provider.label + (answer.fallbackReason?.let { " (fallback after $it)" } ?: "")
                _state.update {
                    it.copy(
                        aiBusy = false,
                        aiStage = null,
                        aiStreamingText = null,
                        aiExchanges = it.aiExchanges + ThermalAiExchange(
                            question = if (isPlan) PLAN_TITLE else question!!.trim(),
                            answer = answer.text,
                            provider = provider,
                            forecastKey = forecastKey,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(aiBusy = false, aiStage = null, aiStreamingText = null) }
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        aiBusy = false,
                        aiStage = null,
                        aiStreamingText = null,
                        aiError = (error.localizedMessage ?: "AI request failed") +
                            " The local thermal read above still applies.",
                    )
                }
            }
        }
    }

    fun clearAi() = _state.update { it.copy(aiExchanges = emptyList(), aiError = null) }

    /** Posts a one-shot heads-up that the NWS grid disagrees with the app forecast. */
    private fun notifyNwsDisagreement(badge: String) {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(NWS_CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(NWS_CHANNEL_ID, "Forecast cross-check", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(app, NWS_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(app)
        }
        val notification = builder
            .setContentTitle("NWS disagrees with the app forecast")
            .setContentText(badge)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NWS_NOTIFICATION_ID, notification) }
    }

    private inline fun edit(crossinline change: ThermalUiState.() -> ThermalUiState) {
        _state.update { it.change() }
        recompute()
    }

    private fun recompute() {
        val s = _state.value
        val forecast = runCatching { buildInputs(s)?.let(ThermalWindEngine::forecast) }.getOrNull()
        _state.update { it.copy(forecast = forecast) }
    }

    companion object {
        const val PLAN_TITLE = "Hunt plan"
        private const val NWS_CHANNEL_ID = "forecast_cross_check"
        private const val NWS_NOTIFICATION_ID = 4201

        /**
         * Flags hours where the NWS grid and the app forecast disagree materially:
         * speed off by more than 4 mph, or direction off by more than 30° while the wind
         * is strong enough to matter (≥ 2 mph). Returns null when they broadly agree.
         */
        fun nwsDisagreement(days: List<ThermalWeatherDay>, nws: List<NwsWindSample>): String? {
            var compared = 0
            var speedHits = 0
            var dirHits = 0
            var worstSpeed = 0.0
            var worstDir = 0.0
            for (day in days) {
                // Local hour → UTC epoch: local = utc + offset.
                val date = parseIsoDate(day.date) ?: continue
                for (hour in day.hourly) {
                    val localMidnightUtc = date.timeInMillis - day.utcOffsetMinutes * 60_000L
                    val utcMillis = localMidnightUtc + hour.hour * 3_600_000L
                    val sample = nws.firstOrNull { it.utcStartMillis <= utcMillis && it.utcEndMillis > utcMillis } ?: continue
                    compared++
                    val speedDiff = kotlin.math.abs(sample.speedMph - hour.windSpeedMph)
                    var dirDiff = kotlin.math.abs(sample.fromDegrees - hour.windFromDegrees) % 360.0
                    if (dirDiff > 180.0) dirDiff = 360.0 - dirDiff
                    worstSpeed = maxOf(worstSpeed, speedDiff)
                    worstDir = maxOf(worstDir, dirDiff)
                    if (speedDiff > 4.0) speedHits++
                    if (dirDiff > 30.0 && maxOf(sample.speedMph, hour.windSpeedMph) >= 2.0) dirHits++
                }
            }
            if (compared == 0 || speedHits + dirHits == 0) return null
            return "NWS disagrees $speedHits+$dirHits of $compared h (worst %.0f mph / %.0f°)".format(worstSpeed, worstDir)
        }

        /** Assemble engine inputs from UI state; null until there is a location. */
        fun buildInputs(s: ThermalUiState, today: Calendar = Calendar.getInstance()): ThermalInputs? {
            val lat = s.latitude ?: return null
            val lon = s.longitude ?: return null
            val day = s.selectedDay
            val useForecast = s.source == ThermalWeatherSource.FORECAST && day != null

            val date = (if (useForecast) parseIsoDate(day!!.date) else null) ?: today
            val offsetMinutes = day?.utcOffsetMinutes ?: (deviceOffsetMillis(date) / 60_000)
            val dayOfYear = DaylightPlanner.dayOfYear(
                date.get(Calendar.YEAR),
                date.get(Calendar.MONTH) + 1,
                date.get(Calendar.DAY_OF_MONTH),
            )

            val hourly = if (useForecast) {
                day!!.hourly
            } else {
                val daylight = DaylightPlanner.compute(lat, lon, dayOfYear)
                val sunrise = daylight.sunriseUtcMinutes?.let { (it / 60.0 + offsetMinutes / 60.0 + 24.0) % 24.0 } ?: 7.0
                val curve = DiurnalTemperatureCurve.build(
                    s.manualReadings.map { DiurnalTemperatureCurve.Reading(it.hour.toDouble(), it.temperatureF) },
                    sunriseHour = sunrise,
                )
                curve.mapIndexed { hour, temp ->
                    HourlyWeather(
                        hour = hour,
                        temperatureF = temp,
                        windSpeedMph = s.manualWindMph,
                        windFromDegrees = s.manualWindFromDegrees,
                        cloudCoverPercent = s.manualCloudPercent,
                    )
                }
            }
            return ThermalInputs(
                latitude = lat,
                longitude = lon,
                dayOfYear = dayOfYear,
                utcOffsetMinutes = offsetMinutes,
                hourly = hourly,
                terrain = s.activeTerrain,
                groundWindFactor = s.groundCover.windFactor,
            )
        }

        /** "2026-10-27" → local-noon Calendar, or null if malformed. */
        fun parseIsoDate(iso: String): Calendar? {
            val parts = iso.trim().split("-").mapNotNull { it.toIntOrNull() }
            if (parts.size != 3 || parts[1] !in 1..12 || parts[2] !in 1..31) return null
            return Calendar.getInstance().apply {
                clear()
                set(parts[0], parts[1] - 1, parts[2], 12, 0, 0)
            }
        }

        private fun deviceOffsetMillis(date: Calendar): Int =
            TimeZone.getDefault().getOffset(date.timeInMillis)

        fun dateLabel(s: ThermalUiState): String {
            val day = s.selectedDay
            val date = (if (s.source == ThermalWeatherSource.FORECAST && day != null) parseIsoDate(day.date) else null)
                ?: Calendar.getInstance()
            return SimpleDateFormat("EEE MMM d", Locale.US).format(date.time)
        }
    }
}
