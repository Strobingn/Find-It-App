package com.example.thermals

import android.content.Context
import com.example.ai.GeminiConversationTurn
import com.example.ai.TerrainAiAnswer
import com.example.ai.TerrainAiGateway

/**
 * Cloud AI layer on top of [ThermalWindEngine]. The engine does the physics locally (and keeps
 * working offline); the AI turns the hour-by-hour numbers into a hunt plan — stand side, entry
 * and exit routes, and when to move — and answers follow-up questions about the same forecast.
 */
class ThermalAiAdvisor(context: Context) {
    private val gateway = TerrainAiGateway(context.applicationContext)

    suspend fun brief(
        forecast: ThermalForecast,
        locationLabel: String,
        dateLabel: String,
        question: String? = null,
        history: List<Pair<String, String>> = emptyList(),
        onStage: (String) -> Unit = {},
    ): TerrainAiAnswer {
        val context = buildContext(forecast, locationLabel, dateLabel)
        val turns = mutableListOf<GeminiConversationTurn>()
        history.forEach { (q, a) ->
            turns += GeminiConversationTurn(role = "user", text = q)
            turns += GeminiConversationTurn(role = "model", text = a)
        }
        turns += GeminiConversationTurn(
            role = "user",
            text = question?.takeIf { it.isNotBlank() } ?: DEFAULT_REQUEST,
        )
        return gateway.generate(
            conversation = turns,
            systemContext = context,
            onProviderStage = onStage,
            featureName = "thermal_wind",
        )
    }

    companion object {
        const val DEFAULT_REQUEST =
            "Give me a thermal and scent plan for this hunt: when the thermals switch, which way my scent goes " +
                "through the morning sit, midday and the evening sit, where to set up relative to bedding/feeding " +
                "on this slope, and how to walk in and out without blowing deer out."

        /** Pure prompt builder so it can be unit tested without a network call. */
        fun buildContext(forecast: ThermalForecast, locationLabel: String, dateLabel: String): String = buildString {
            val inputs = forecast.inputs
            val terrain = inputs.terrain
            appendLine("ROLE: You are a whitetail/big-game hunting advisor who specializes in thermals and scent control.")
            appendLine("Base every direction on the computed data below. Do not invent weather. Bearings are degrees true; 'toward' means where scent/air moves TO.")
            appendLine("If the user asks something the data cannot answer (e.g. where deer bed), say what you'd need and give the rule of thumb.")
            appendLine("Answer in short sections with plain compass words (NE, SW…) and clock times. No markdown tables.")
            appendLine()
            appendLine("SITE: $locationLabel  (lat %.5f, lon %.5f)".format(inputs.latitude, inputs.longitude))
            appendLine("DATE: $dateLabel")
            append("TERRAIN: ${terrain.setting.label}")
            terrain.uphillBearingDegrees?.let {
                append(", uphill toward ${ThermalWindEngine.compass(it)} (%.0f°), slope %.0f°".format(it, terrain.slopeDegrees))
            }
            terrain.elevationFeet?.let { append(", elevation %.0f ft".format(it)) }
            appendLine(" [source: ${terrain.source}]")
            forecast.sunriseLocalHour?.let { appendLine("SUNRISE: ${ThermalWindEngine.formatHour(it)}") }
            forecast.sunsetLocalHour?.let { appendLine("SUNSET: ${ThermalWindEngine.formatHour(it)}") }
            forecast.morningSwitchHour?.let { appendLine("MORNING THERMAL SWITCH (fall→rise): ${ThermalWindEngine.formatHour(it)}") }
            forecast.eveningSwitchHour?.let { appendLine("EVENING THERMAL SWITCH (rise→fall): ${ThermalWindEngine.formatHour(it)}") }
            appendLine("DAILY TEMP RANGE: %.0f°F".format(forecast.dailyRangeF))
            appendLine()
            appendLine("HOURLY (local): time | temp°F | sky% | thermal phase | thermal mph→dir | ground wind mph→dir | NET SCENT mph→dir | thermal share | confidence")
            forecast.hours.filter { it.hour in 4..21 }.forEach { h ->
                appendLine(
                    "%s | %.0f | %.0f | %s | %s | %.1f→%s | %.1f→%s | %.0f%% | %.0f%%".format(
                        ThermalWindEngine.formatHour(h.hour.toDouble()),
                        h.temperatureF,
                        h.cloudCoverPercent,
                        h.phase.label,
                        h.thermalTowardDegrees?.let { "%.1f→%s".format(h.thermalSpeedMph, ThermalWindEngine.compass(it)) }
                            ?: "%.1f vertical".format(h.thermalSpeedMph),
                        h.groundWindMph,
                        ThermalWindEngine.compass(h.windTowardDegrees),
                        h.scentSpeedMph,
                        ThermalWindEngine.compass(h.scentTowardDegrees),
                        h.thermalShare * 100,
                        h.confidence * 100,
                    ),
                )
            }
            appendLine()
            appendLine("LOCAL ENGINE SUMMARY:")
            ThermalWindEngine.summarize(forecast).forEach { appendLine("- $it") }
        }
    }
}
