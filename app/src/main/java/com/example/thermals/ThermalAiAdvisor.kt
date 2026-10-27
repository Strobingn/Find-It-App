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
        onPartialText: ((String) -> Unit)? = null,
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
            // Short, structured answer: quick reasoning and streamed text so it starts showing in seconds.
            fast = true,
            onPartialText = onPartialText,
        )
    }

    companion object {
        const val DEFAULT_REQUEST =
            "Hunt plan for this slope: morning sit, midday, evening sit, and how to walk in and out without " +
                "letting deer smell me."

        /** Pure prompt builder so it can be unit tested without a network call. */
        fun buildContext(forecast: ThermalForecast, locationLabel: String, dateLabel: String): String = buildString {
            val inputs = forecast.inputs
            val terrain = inputs.terrain
            appendLine("ROLE: You are a whitetail/big-game hunting advisor who specializes in thermals and scent control.")
            appendLine("Base every direction on the computed data below. Do not invent weather. Bearings are degrees true; 'toward' means where scent/air moves TO.")
            appendLine("If the user asks something the data cannot answer (e.g. where deer bed), say what you'd need and give the rule of thumb.")
            appendLine("Be brief: under 170 words unless asked for more. Use these short sections: Morning sit · Midday · Evening sit · Entry/exit.")
            appendLine("Each section: 1–2 sentences with the clock time, where scent goes (plain compass words like NE, SW) and where to sit. No tables, no preamble.")
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
            forecast.hours.filter { it.hour in 5..20 }.forEach { h ->
                appendLine(
                    "%s | %.0f | %.0f | %s | %s | %.1f→%s | %.1f→%s | %.0f%% | %.0f%%".format(
                        ThermalWindEngine.formatHour(h.hour.toDouble()),
                        h.temperatureF,
                        h.cloudCoverPercent,
                        ThermalWindEngine.phaseText(h),
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
