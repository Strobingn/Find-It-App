package com.example.data.historicmap

import com.example.data.field.BoundaryVertex
import java.util.Locale
import java.util.UUID

/**
 * Historic map feature drafts: local ink extract always; optional cloud enhance when online.
 * Never auto-writes finds — only [HistoricMapFeature] drafts for operator review.
 */
object MapVectorizationGateway {

    enum class Mode { LOCAL, CLOUD_ENHANCE }

    data class VectorizationResult(
        val mode: Mode,
        val features: List<HistoricMapFeature>,
        val note: String,
        val providerLabel: String?,
    )

    fun extractLocal(
        pixels: IntArray,
        width: Int,
        height: Int,
        mapId: String,
        transform: GeoReferenceTransform,
        nowMillis: Long = System.currentTimeMillis(),
    ): VectorizationResult {
        val raw = HistoricMapFeatureExtractor.extract(
            pixels = pixels,
            width = width,
            height = height,
            mapId = mapId,
            transform = transform,
            nowMillis = nowMillis,
        )
        val tagged = raw.features.map {
            it.copy(note = "Local ink · ${it.note}")
        }
        return VectorizationResult(
            mode = Mode.LOCAL,
            features = tagged,
            note = raw.note,
            providerLabel = null,
        )
    }

    fun cloudSystemPrompt(): String = """
        You help vectorize historic map ink into draft GIS features.
        Hard rules: do not claim buried metal, age, or value. Drafts only.
        After brief analysis emit zero or more lines exactly:
        MAP_FEATURE type=ROAD|STRUCTURE|WALL|BOUNDARY conf=0.0-1.0 note=short text lat1,lon1;lat2,lon2
        Use only coordinates near those provided. Prefer refining local drafts.
    """.trimIndent()

    fun cloudUserPrompt(local: VectorizationResult, terrainContext: String): String = buildString {
        appendLine("Terrain / map context:")
        appendLine(terrainContext.take(2_000))
        appendLine()
        appendLine("Local ink drafts (${local.features.size}):")
        local.features.take(20).forEach { f ->
            val pts = f.points.joinToString(";") {
                String.format(Locale.US, "%.5f,%.5f", it.latitude, it.longitude)
            }
            appendLine("${f.type.name} conf=${f.confidence} pts=$pts note=${f.note.take(80)}")
        }
        appendLine()
        appendLine("Emit improved MAP_FEATURE lines.")
    }

    /**
     * Merge cloud model text with local drafts. Caller performs the network call
     * (keeps [TerrainAiGateway] internal to the AI package).
     */
    fun mergeCloudAnswer(
        local: VectorizationResult,
        answerText: String,
        mapId: String,
        providerLabel: String?,
        fallbackReason: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): VectorizationResult {
        val parsed = parseMapFeatureLines(answerText, mapId, nowMillis)
        val merged = if (parsed.isEmpty()) {
            local.features
        } else {
            (local.features + parsed).distinctBy {
                "${it.type}|${it.points.firstOrNull()?.latitude}|${it.points.firstOrNull()?.longitude}"
            }.take(40)
        }
        return VectorizationResult(
            mode = Mode.CLOUD_ENHANCE,
            features = merged.map {
                if (it.note.startsWith("Cloud")) it else it.copy(note = "Cloud enhance · ${it.note}")
            },
            note = if (parsed.isEmpty()) {
                "Cloud returned no MAP_FEATURE lines — kept ${local.features.size} local draft(s). ${fallbackReason.orEmpty()}"
            } else {
                "Cloud added/refined ${parsed.size} draft(s) via ${providerLabel ?: "AI"}; total ${merged.size}."
            },
            providerLabel = providerLabel,
        )
    }

    internal fun parseMapFeatureLines(
        text: String,
        mapId: String,
        nowMillis: Long,
    ): List<HistoricMapFeature> {
        val linePattern = Regex(
            """MAP_FEATURE\s+type=(\w+)\s+conf=([0-9.]+)\s+note=([^\n]*?)\s+([-\d.,;]+)""",
            RegexOption.IGNORE_CASE,
        )
        return linePattern.findAll(text).mapNotNull { m ->
            val type = MapFeatureType.entries.firstOrNull {
                it.name.equals(m.groupValues[1], ignoreCase = true)
            } ?: return@mapNotNull null
            val conf = m.groupValues[2].toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f
            val note = m.groupValues[3].trim().ifBlank { "cloud draft" }
            val pts = m.groupValues[4].split(';').mapNotNull { pair ->
                val parts = pair.split(',')
                if (parts.size < 2) return@mapNotNull null
                val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val lon = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
                BoundaryVertex(lat, lon)
            }
            if (pts.isEmpty()) return@mapNotNull null
            HistoricMapFeature(
                id = UUID.randomUUID().toString(),
                mapId = mapId,
                type = type,
                points = pts,
                confidence = conf,
                note = "Cloud · $note",
                createdAtMillis = nowMillis,
            )
        }.toList()
    }
}
