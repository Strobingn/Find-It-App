package com.example.thermals

import com.example.geospatial.DaylightPlanner
import com.example.geospatial.SolarPosition
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Hunting thermal-wind engine.
 *
 * Thermals are the slow slope/valley breezes created by the sun heating and the night cooling
 * the ground. Cold, dense air drains *downhill* before sunrise and after the slope falls into
 * shade; once the sun warms a slope the air above it rises *uphill*. Where that hourly thermal
 * pushes scent depends on which way the slope faces (east faces warm first, west faces last),
 * how hard the day warms (a 32 °F → 66 °F morning drives strong thermals), cloud cover, and
 * whether the prevailing wind is strong enough to overpower the thermal entirely.
 *
 * The engine is pure Kotlin (no Android types) so it runs in unit tests and offline in the field.
 * Every bearing is degrees clockwise from true north. "Toward" bearings are where the air (and
 * your scent) is moving *to*; weather services report wind as the direction it blows *from*.
 */
enum class TerrainSetting(val label: String) {
    SLOPE("Hillside"),
    VALLEY("Valley / draw"),
    RIDGE("Ridge top"),
    FLAT("Flat ground"),
}

/** One hour of weather in site-local clock time. */
data class HourlyWeather(
    /** Local clock hour, 0..23. */
    val hour: Int,
    val temperatureF: Double,
    /** 10 m wind speed as reported by a forecast. */
    val windSpeedMph: Double,
    /** Direction the wind blows FROM (meteorological convention). */
    val windFromDegrees: Double,
    /** 0..100 % sky cover. */
    val cloudCoverPercent: Double,
    /** 10 m wind gust speed, when the forecast provides it. */
    val gustMph: Double? = null,
)

/**
 * Terrain at the stand. [uphillBearingDegrees] is the direction you walk to go uphill (for a
 * valley: the direction the valley floor rises / heads toward). Null means no usable slope.
 */
data class SiteTerrain(
    val setting: TerrainSetting,
    val uphillBearingDegrees: Double?,
    val slopeDegrees: Double,
    val elevationFeet: Double? = null,
    /** Where the terrain came from, for display ("Elevation model", "Manual"). */
    val source: String = "Manual",
)

data class ThermalInputs(
    val latitude: Double,
    val longitude: Double,
    val dayOfYear: Int,
    /** Site-local offset from UTC in minutes (e.g. −240 for EDT). */
    val utcOffsetMinutes: Int,
    /** Hourly weather; missing hours are interpolated. Needs at least one entry. */
    val hourly: List<HourlyWeather>,
    val terrain: SiteTerrain,
    /** Fraction of the 10 m forecast wind that reaches a hunter in cover (woods ≈ 0.45, open ≈ 0.75). */
    val groundWindFactor: Double = 0.55,
)

enum class ThermalPhase(val label: String) {
    DRAINING("Falling (downhill)"),
    SWITCHING("Switching / swirling"),
    RISING("Rising (uphill)"),
    WIND_DOMINATED("Wind overrides thermals"),
}

data class ThermalHour(
    val hour: Int,
    val temperatureF: Double,
    val warmingRateFPerHour: Double,
    val sunAltitudeDegrees: Double,
    val sunAzimuthDegrees: Double,
    /** cos(sun incidence) on the slope, clamped at 0 (0 = slope in shade). */
    val slopeSunExposure: Double,
    /** Signed thermal tendency; + = rising/upslope, − = draining/downslope. */
    val tendency: Double,
    val phase: ThermalPhase,
    /** Horizontal thermal speed (always ≥ 0). */
    val thermalSpeedMph: Double,
    /** Where the thermal pushes air, or null on flat ground / ridge tops (vertical motion). */
    val thermalTowardDegrees: Double?,
    val groundWindMph: Double,
    val windTowardDegrees: Double,
    /** Net drift of scent near the ground: thermal + prevailing wind. */
    val scentTowardDegrees: Double,
    val scentSpeedMph: Double,
    /** 0..1 share of the scent drift caused by the thermal rather than the prevailing wind. */
    val thermalShare: Double,
    val cloudCoverPercent: Double,
    /** 0..1 how much to trust this hour's call. */
    val confidence: Double,
    /** Gust speed at ground level (gust × cover factor), when the forecast provides gusts. */
    val gustMph: Double? = null,
)

data class ThermalForecast(
    val inputs: ThermalInputs,
    val hours: List<ThermalHour>,
    /** Fractional local hour the morning thermal flips from falling to rising, if it does. */
    val morningSwitchHour: Double?,
    /** Fractional local hour the evening thermal flips back to falling, if it does. */
    val eveningSwitchHour: Double?,
    val peakRisingHour: Int?,
    val sunriseLocalHour: Double?,
    val sunsetLocalHour: Double?,
    val dailyRangeF: Double,
) {
    fun hour(hour: Int): ThermalHour? = hours.firstOrNull { it.hour == hour }
}

object ThermalWindEngine {

    /** Below this |tendency| the thermal is too weak to have a clear direction. */
    private const val SWITCH_BAND = 0.05

    /** Mph of upslope thermal per unit of tendency at a "typical" 25 °F daily range. */
    private const val RISING_GAIN = 6.0
    private const val DRAINING_GAIN = 5.0

    /** Ground wind (mph) at which slope thermals are cut roughly in half by mixing. */
    private const val WIND_MIXING_MPH = 8.0

    fun forecast(inputs: ThermalInputs): ThermalForecast {
        require(inputs.hourly.isNotEmpty()) { "Thermal forecast needs at least one hour of weather" }
        val weather = WeatherSeries(inputs.hourly)
        val daylight = DaylightPlanner.compute(inputs.latitude, inputs.longitude, inputs.dayOfYear)
        val rangeF = weather.maxTemp() - weather.minTemp()
        val rangeFactor = (rangeF / 25.0).coerceIn(0.3, 1.6)

        val hours = (0..23).map { hour -> evaluateHour(inputs, weather, daylight.solarNoonUtcMinutes, hour.toDouble(), rangeFactor, rangeF) }

        // Switch detection at 10-minute resolution so the times read like "8:20 AM", not "8 AM".
        val fine = (0..(24 * 6)).map { step ->
            val t = step / 6.0
            t to tendencyAt(inputs, weather, daylight.solarNoonUtcMinutes, t)
        }
        val morningSwitch = firstCrossing(fine, fromHour = 3.0, toHour = 14.0, upward = true)
        val eveningSwitch = firstCrossing(fine, fromHour = morningSwitch ?: 11.0, toHour = 23.5, upward = false)
        val peak = hours.filter { it.tendency > SWITCH_BAND }.maxByOrNull { it.tendency }?.hour

        val offsetHours = inputs.utcOffsetMinutes / 60.0
        return ThermalForecast(
            inputs = inputs,
            hours = hours,
            morningSwitchHour = morningSwitch,
            eveningSwitchHour = eveningSwitch,
            peakRisingHour = peak,
            sunriseLocalHour = daylight.sunriseUtcMinutes?.let { wrapHour(it / 60.0 + offsetHours) },
            sunsetLocalHour = daylight.sunsetUtcMinutes?.let { wrapHour(it / 60.0 + offsetHours) },
            dailyRangeF = rangeF,
        )
    }

    private fun evaluateHour(
        inputs: ThermalInputs,
        weather: WeatherSeries,
        solarNoonUtcMinutes: Float,
        t: Double,
        rangeFactor: Double,
        rangeF: Double,
    ): ThermalHour {
        val sun = sunAt(inputs, solarNoonUtcMinutes, t)
        val exposure = slopeExposure(inputs.terrain, sun.first, sun.second)
        val tendency = tendencyAt(inputs, weather, solarNoonUtcMinutes, t)
        val terrainFactor = terrainFactor(inputs.terrain)
        val gain = if (tendency >= 0) RISING_GAIN else DRAINING_GAIN
        val groundWind = weather.windSpeed(t) * inputs.groundWindFactor
        // Wind stirs the near-ground air and mixes slope flows out; thermals survive in calm air.
        val windMixing = 1.0 / (1.0 + groundWind / WIND_MIXING_MPH)
        val thermalSpeed = (abs(tendency) * gain * rangeFactor * terrainFactor * windMixing).coerceAtMost(6.0)

        val thermalToward: Double? = inputs.terrain.uphillBearingDegrees
            ?.takeIf { hasHorizontalThermal(inputs.terrain) && abs(tendency) > SWITCH_BAND }
            ?.let { uphill -> if (tendency > 0) normalize(uphill) else normalize(uphill + 180.0) }
        val horizontalThermal = if (thermalToward != null) thermalSpeed else 0.0

        val windToward = normalize(weather.windFrom(t) + 180.0)

        // Vector sum (east, north components) of prevailing wind and thermal.
        var east = groundWind * sin(Math.toRadians(windToward))
        var north = groundWind * cos(Math.toRadians(windToward))
        if (thermalToward != null) {
            east += horizontalThermal * sin(Math.toRadians(thermalToward))
            north += horizontalThermal * cos(Math.toRadians(thermalToward))
        }
        val scentSpeed = hypot(east, north)
        val scentToward = if (scentSpeed < 0.05) {
            thermalToward ?: windToward
        } else {
            normalize(Math.toDegrees(atan2(east, north)))
        }
        val share = if (thermalSpeed + groundWind <= 0.0) 0.0 else thermalSpeed / (thermalSpeed + groundWind)

        val windDominated = groundWind >= 5.0 && groundWind >= 2.5 * thermalSpeed
        val phase = when {
            windDominated -> ThermalPhase.WIND_DOMINATED
            tendency > SWITCH_BAND -> ThermalPhase.RISING
            tendency < -SWITCH_BAND -> ThermalPhase.DRAINING
            else -> ThermalPhase.SWITCHING
        }

        val cloud = weather.cloud(t)
        val confidence = (
            0.35 +
                0.35 * min(rangeF / 25.0, 1.0) +
                0.15 * min(abs(tendency) / 0.25, 1.0) -
                0.25 * (cloud / 100.0) -
                (if (phase == ThermalPhase.SWITCHING) 0.15 else 0.0) -
                (if (!hasHorizontalThermal(inputs.terrain)) 0.1 else 0.0)
            ).coerceIn(0.1, 0.95)

        return ThermalHour(
            hour = floor(t).toInt().coerceIn(0, 23),
            temperatureF = weather.temperature(t),
            warmingRateFPerHour = weather.warmingRate(t),
            sunAltitudeDegrees = sun.first,
            sunAzimuthDegrees = sun.second,
            slopeSunExposure = exposure,
            tendency = tendency,
            phase = phase,
            thermalSpeedMph = thermalSpeed,
            thermalTowardDegrees = thermalToward,
            groundWindMph = groundWind,
            windTowardDegrees = windToward,
            scentTowardDegrees = scentToward,
            scentSpeedMph = scentSpeed,
            thermalShare = share,
            cloudCoverPercent = cloud,
            confidence = confidence,
            gustMph = weather.gust(t)?.let { it * inputs.groundWindFactor },
        )
    }

    /**
     * Signed thermal tendency at fractional local hour [t].
     *
     * Heating uses sun exposure on the actual slope ~45 minutes earlier (the ground has to warm
     * before the air over it rises), dimmed by cloud. Cooling is a constant radiative loss that is
     * stronger under clear skies. The air-temperature trend nudges the balance either way.
     */
    internal fun tendencyAt(
        inputs: ThermalInputs,
        weather: WeatherSeries,
        solarNoonUtcMinutes: Float,
        t: Double,
    ): Double {
        val lagged = t - 0.75
        val sun = sunAt(inputs, solarNoonUtcMinutes, lagged)
        val cloud = weather.cloud(t) / 100.0
        val heating = slopeExposure(inputs.terrain, sun.first, sun.second) * (1.0 - 0.75 * cloud)
        val cooling = 0.10 + 0.10 * (1.0 - cloud)
        val trend = (weather.warmingRate(t) * 0.03).coerceIn(-0.15, 0.15)
        return heating - cooling + trend
    }

    /** cos of sun incidence on the slope surface, clamped to ≥ 0. Flat ground uses sin(altitude). */
    internal fun slopeExposure(terrain: SiteTerrain, sunAltitudeDegrees: Double, sunAzimuthDegrees: Double): Double {
        if (sunAltitudeDegrees <= 0.0) return 0.0
        val alt = Math.toRadians(sunAltitudeDegrees)
        val uphill = terrain.uphillBearingDegrees
        if (uphill == null || terrain.setting == TerrainSetting.FLAT || terrain.slopeDegrees < 1.0) {
            return sin(alt).coerceAtLeast(0.0)
        }
        // A valley floor is mostly level; its side slopes heat in turn, so blend toward flat.
        val slopeDeg = if (terrain.setting == TerrainSetting.VALLEY) terrain.slopeDegrees * 0.5 else terrain.slopeDegrees
        val slope = Math.toRadians(slopeDeg.coerceIn(0.0, 60.0))
        val aspect = Math.toRadians(normalize(uphill + 180.0)) // direction the slope faces
        val az = Math.toRadians(sunAzimuthDegrees)
        val cosIncidence = sin(alt) * cos(slope) + cos(alt) * sin(slope) * cos(az - aspect)
        return cosIncidence.coerceAtLeast(0.0)
    }

    /**
     * Flow field for the map: evaluates the thermal for any patch of ground (its own slope and
     * facing) at any fractional hour, sharing the day's weather. East faces turn uphill before
     * west faces, draws drain while ridges lift — each cell behaves like its own stand.
     */
    class FlowField internal constructor(
        private val inputs: ThermalInputs,
        private val weather: WeatherSeries,
        private val solarNoonUtcMinutes: Float,
        private val rangeFactor: Double,
        private val rangeF: Double,
    ) {
        fun at(t: Double, terrain: SiteTerrain = inputs.terrain): ThermalHour =
            evaluateHour(inputs.copy(terrain = terrain), weather, solarNoonUtcMinutes, t.coerceIn(0.0, 23.99), rangeFactor, rangeF)
    }

    fun flowField(inputs: ThermalInputs): FlowField {
        val weather = WeatherSeries(inputs.hourly)
        val daylight = DaylightPlanner.compute(inputs.latitude, inputs.longitude, inputs.dayOfYear)
        val rangeF = weather.maxTemp() - weather.minTemp()
        return FlowField(inputs, weather, daylight.solarNoonUtcMinutes, (rangeF / 25.0).coerceIn(0.3, 1.6), rangeF)
    }

    private fun terrainFactor(terrain: SiteTerrain): Double = when (terrain.setting) {
        TerrainSetting.FLAT -> 0.35
        TerrainSetting.RIDGE -> 0.5
        TerrainSetting.VALLEY -> 1.1 // valleys channel and concentrate thermal flow
        TerrainSetting.SLOPE -> 0.5 + min(terrain.slopeDegrees, 30.0) / 30.0
    }

    private fun hasHorizontalThermal(terrain: SiteTerrain): Boolean =
        terrain.uphillBearingDegrees != null &&
            terrain.setting != TerrainSetting.FLAT &&
            terrain.setting != TerrainSetting.RIDGE &&
            (terrain.slopeDegrees >= 1.0 || terrain.setting == TerrainSetting.VALLEY)

    /** (altitude°, azimuth°) for fractional local clock hour [t]. */
    private fun sunAt(inputs: ThermalInputs, solarNoonUtcMinutes: Float, t: Double): Pair<Double, Double> {
        val clockUtcMinutes = t * 60.0 - inputs.utcOffsetMinutes
        var solarHour = 12.0 + (clockUtcMinutes - solarNoonUtcMinutes) / 60.0
        solarHour = ((solarHour % 24.0) + 24.0) % 24.0
        val pos = SolarPosition.calculate(inputs.latitude, inputs.dayOfYear, solarHour.toFloat())
        return pos.altitudeDegrees.toDouble() to pos.azimuthDegrees.toDouble()
    }

    private fun firstCrossing(
        samples: List<Pair<Double, Double>>,
        fromHour: Double,
        toHour: Double,
        upward: Boolean,
    ): Double? {
        val window = samples.filter { it.first in fromHour..toHour }
        for (i in 1 until window.size) {
            val (t0, v0) = window[i - 1]
            val (t1, v1) = window[i]
            val crosses = if (upward) v0 <= 0.0 && v1 > 0.0 else v0 >= 0.0 && v1 < 0.0
            if (crosses) {
                val frac = if (v1 == v0) 0.0 else -v0 / (v1 - v0)
                return t0 + (t1 - t0) * frac
            }
        }
        return null
    }

    fun normalize(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0

    private fun wrapHour(h: Double): Double = ((h % 24.0) + 24.0) % 24.0

    private val COMPASS_16 = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    fun compass(degrees: Double): String = COMPASS_16[((normalize(degrees) / 22.5) + 0.5).toInt() % 16]

    /** "8:20 AM" for a fractional local hour. */
    fun formatHour(hour: Double): String {
        val totalMinutes = ((hour * 60.0).roundToInt() % 1440 + 1440) % 1440
        val h24 = totalMinutes / 60
        val minute = totalMinutes % 60
        val suffix = if (h24 < 12) "AM" else "PM"
        val h12 = when {
            h24 == 0 -> 12
            h24 > 12 -> h24 - 12
            else -> h24
        }
        return "%d:%02d %s".format(h12, minute, suffix)
    }

    /**
     * Plain-language read of the day, computed locally (works offline, no AI key needed).
     * The cloud AI briefing builds on this rather than replacing it.
     */
    fun summarize(forecast: ThermalForecast): List<String> {
        val lines = mutableListOf<String>()
        val terrain = forecast.inputs.terrain
        val uphill = terrain.uphillBearingDegrees
        val downhillWord = uphill?.let { compass(it + 180.0) }
        val uphillWord = uphill?.let { compass(it) }
        val horizontal = hasHorizontalThermal(terrain)

        lines += "Daily swing %.0f°F (%.0f°F → %.0f°F) — %s thermals.".format(
            forecast.dailyRangeF,
            forecast.hours.minOf { it.temperatureF },
            forecast.hours.maxOf { it.temperatureF },
            when {
                forecast.dailyRangeF >= 25 -> "strong"
                forecast.dailyRangeF >= 15 -> "moderate"
                else -> "weak"
            },
        )

        val morning = forecast.morningSwitchHour
        val evening = forecast.eveningSwitchHour
        if (horizontal && downhillWord != null && uphillWord != null) {
            lines += if (morning != null) {
                "Morning: cold air drains downhill toward the $downhillWord until about ${formatHour(morning)}, then the thermal switches and starts rising uphill toward the $uphillWord."
            } else {
                "Morning: cold air keeps draining downhill toward the $downhillWord; this slope never warms enough to switch."
            }
            forecast.peakRisingHour?.let { peak ->
                val h = forecast.hour(peak)
                if (h != null) {
                    lines += "Strongest rising thermal around ${formatHour(peak.toDouble())} (≈%.1f mph uphill).".format(h.thermalSpeedMph)
                }
            }
            if (evening != null) {
                lines += "Evening: the slope shades and thermals fall back downhill toward the $downhillWord around ${formatHour(evening)}."
            }
        } else {
            val where = if (terrain.setting == TerrainSetting.RIDGE) "On the ridge top" else "On flat ground"
            lines += "$where thermals mostly move scent up (midday) or let it pool low (dawn/dusk) instead of pushing it sideways — the prevailing wind sets your direction."
            if (morning != null) lines += "Air starts lifting about ${formatHour(morning)}."
            if (evening != null) lines += "Scent starts settling and pooling about ${formatHour(evening)}."
        }

        forecast.sunriseLocalHour?.let { sr ->
            forecast.sunsetLocalHour?.let { ss ->
                lines += "Sunrise ${formatHour(sr)} · sunset ${formatHour(ss)}."
            }
        }

        val windHours = forecast.hours.filter { it.phase == ThermalPhase.WIND_DOMINATED && it.hour in 5..20 }
        if (windHours.isNotEmpty()) {
            val first = windHours.first()
            lines += "Prevailing wind (≈%.0f mph in cover, blowing toward %s) overpowers thermals %s–%s.".format(
                first.groundWindMph,
                compass(first.windTowardDegrees),
                formatHour(windHours.first().hour.toDouble()),
                formatHour(windHours.last().hour + 1.0),
            )
        }

        val switchWindows = listOfNotNull(morning, evening)
        if (switchWindows.isNotEmpty()) {
            lines += "Expect swirling, unpredictable scent for ~30–45 min around each switch (${switchWindows.joinToString(" and ") { formatHour(it) }}). Avoid moving or sitting tight to bedding then."
        }
        return lines
    }

    /** Local hunt advice for one hour: which side of you scent goes, so you know where deer must not be. */
    fun adviceFor(hour: ThermalHour): String {
        val toward = compass(hour.scentTowardDegrees)
        val from = compass(hour.scentTowardDegrees + 180.0)
        val driver = when (hour.phase) {
            ThermalPhase.WIND_DOMINATED -> "the prevailing wind"
            ThermalPhase.SWITCHING -> "a switching, swirling thermal"
            ThermalPhase.RISING -> "a rising thermal"
            ThermalPhase.DRAINING -> "a falling thermal"
        }
        return "Scent drifts toward the $toward (≈%.1f mph, mostly from %s). Keep deer you expect to the $from side of you; anything to your $toward will smell you.".format(
            hour.scentSpeedMph,
            driver,
        )
    }
}

/** Hourly weather with linear interpolation; hours outside the supplied span hold the nearest value. */
internal class WeatherSeries(points: List<HourlyWeather>) {
    private val sorted = points.sortedBy { it.hour }

    private fun interp(t: Double, value: (HourlyWeather) -> Double): Double {
        if (sorted.size == 1) return value(sorted[0])
        if (t <= sorted.first().hour) return value(sorted.first())
        if (t >= sorted.last().hour) return value(sorted.last())
        val i = sorted.indexOfLast { it.hour <= t }.coerceIn(0, sorted.size - 2)
        val a = sorted[i]
        val b = sorted[i + 1]
        val span = (b.hour - a.hour).toDouble()
        val frac = if (span <= 0.0) 0.0 else (t - a.hour) / span
        return value(a) + (value(b) - value(a)) * frac
    }

    fun temperature(t: Double): Double = interp(t) { it.temperatureF }
    fun windSpeed(t: Double): Double = interp(t) { it.windSpeedMph }.coerceAtLeast(0.0)
    fun cloud(t: Double): Double = interp(t) { it.cloudCoverPercent }.coerceIn(0.0, 100.0)

    /** Gust speed interpolated like wind; null when the source had no gust data at all. */
    fun gust(t: Double): Double? {
        if (sorted.all { it.gustMph == null }) return null
        return interp(t) { it.gustMph ?: it.windSpeedMph }.coerceAtLeast(0.0)
    }

    /** Wind direction interpolated along the shortest arc (so 350° → 10° passes through 0°). */
    fun windFrom(t: Double): Double {
        if (sorted.size == 1 || t <= sorted.first().hour) return sorted.first().windFromDegrees
        if (t >= sorted.last().hour) return sorted.last().windFromDegrees
        val i = sorted.indexOfLast { it.hour <= t }.coerceIn(0, sorted.size - 2)
        val a = sorted[i]
        val b = sorted[i + 1]
        val span = (b.hour - a.hour).toDouble()
        val frac = if (span <= 0.0) 0.0 else (t - a.hour) / span
        var delta = b.windFromDegrees - a.windFromDegrees
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        return ThermalWindEngine.normalize(a.windFromDegrees + delta * frac)
    }

    /** Central-difference warming rate in °F per hour. */
    fun warmingRate(t: Double): Double = (temperature(t + 0.5) - temperature(t - 0.5))

    fun maxTemp(): Double = sorted.maxOf { it.temperatureF }
    fun minTemp(): Double = sorted.minOf { it.temperatureF }
}

/**
 * Builds a full 24-hour temperature curve from a few readings the hunter knows
 * (e.g. 6 AM = 32 °F, 12 PM = 66 °F) using the standard diurnal shape: minimum near sunrise,
 * sine-shaped rise to a mid-afternoon maximum, then a cooling decay overnight. The curve is then
 * corrected so it passes exactly through every reading supplied.
 */
object DiurnalTemperatureCurve {

    data class Reading(val hour: Double, val temperatureF: Double)

    fun build(readings: List<Reading>, sunriseHour: Double = 7.0, peakHour: Double = 14.5): List<Double> {
        require(readings.isNotEmpty()) { "Need at least one temperature reading" }
        val sorted = readings.sortedBy { it.hour }
        if (sorted.size == 1) return List(24) { sorted[0].temperatureF }

        var tMin = sorted.minOf { it.temperatureF }
        var tMax = sorted.maxOf { it.temperatureF }
        // Last reading still before the afternoon peak and rising → the day keeps warming a bit.
        val last = sorted.last()
        val prev = sorted[sorted.size - 2]
        val lastRate = (last.temperatureF - prev.temperatureF) / max(last.hour - prev.hour, 0.5)
        if (last.hour < peakHour && lastRate > 0 && last.temperatureF >= tMax) {
            tMax += (peakHour - last.hour) * lastRate * 0.35
        }
        // First reading after sunrise and already warm → the true low came earlier.
        val first = sorted.first()
        if (first.hour > sunriseHour + 1 && first.temperatureF <= tMin) {
            tMin -= (first.hour - sunriseHour) * 1.0
        }

        val base = DoubleArray(24) { h -> shape(h.toDouble(), tMin, tMax, sunriseHour, peakHour) }
        // Residual correction so the curve honors every reading exactly.
        val residuals = sorted.map { it.hour to (it.temperatureF - shape(it.hour, tMin, tMax, sunriseHour, peakHour)) }
        return List(24) { h ->
            base[h] + interpolateResidual(residuals, h.toDouble())
        }
    }

    private fun shape(h: Double, tMin: Double, tMax: Double, sunrise: Double, peak: Double): Double {
        val amp = tMax - tMin
        return when {
            h in sunrise..peak -> {
                val f = (h - sunrise) / (peak - sunrise)
                tMin + amp * sin(f * Math.PI / 2.0)
            }
            h > peak -> {
                // Cool toward a night value ~25 % of the swing above the minimum.
                val night = tMin + amp * 0.25
                night + (tMax - night) * kotlin.math.exp(-(h - peak) / 4.0)
            }
            else -> {
                // Before sunrise: continuing slow overnight cooling to tMin at sunrise.
                val hoursToSunrise = sunrise - h
                tMin + min(hoursToSunrise, 8.0) * amp * 0.012
            }
        }
    }

    private fun interpolateResidual(residuals: List<Pair<Double, Double>>, h: Double): Double {
        if (h <= residuals.first().first) return residuals.first().second * decay(residuals.first().first - h)
        if (h >= residuals.last().first) return residuals.last().second * decay(h - residuals.last().first)
        val i = residuals.indexOfLast { it.first <= h }.coerceIn(0, residuals.size - 2)
        val (h0, r0) = residuals[i]
        val (h1, r1) = residuals[i + 1]
        val frac = if (h1 == h0) 0.0 else (h - h0) / (h1 - h0)
        return r0 + (r1 - r0) * frac
    }

    /** Residual corrections fade out over ~4 h away from the nearest reading. */
    private fun decay(distanceHours: Double): Double = max(0.0, 1.0 - distanceHours / 4.0)

    /** Rounds to whole-hour readings for UI chips. */
    fun hourLabel(hour: Double): String = ThermalWindEngine.formatHour(floor(hour))
}
