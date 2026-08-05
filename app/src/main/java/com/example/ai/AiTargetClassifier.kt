package com.example.ai

import android.graphics.Bitmap
import com.example.data.ai.AnomalyClassification
import com.example.data.ai.AnomalyRegion
import com.example.data.ai.TerrainSessionContext
import java.io.ByteArrayOutputStream

/** Sends the clustered disturbance regions to the selected cloud provider for typed labels. */
internal object AiTargetClassifier {
    suspend fun classify(
        gateway: TerrainAiGateway,
        regions: List<AnomalyRegion>,
        context: TerrainSessionContext,
        requestedProvider: TerrainAiProvider?,
        onStage: (String) -> Unit = {},
    ): List<AnomalyClassification> = regions.mapIndexed { index, region ->
        val prompt = """
            Classify this LiDAR disturbance candidate for archaeological field checking.
            Candidate ${index + 1}: ${region.cellCount} cells, mean disturbance score ${"%.2f".format(region.meanScore)},
            grid bounds ${region.boundsLeft},${region.boundsTop} to ${region.boundsRight},${region.boundsBottom}.
            Reply with exactly LABEL|CONFIDENCE|DESCRIPTION on one line.
            LABEL must be one of: Foundation, Ditch/Trench, Pit/Well, Mound, Road/Path, Natural erosion, Modern disturbance, Uncertain.
            Confidence is 0.0 to 1.0. Treat the image as measured terrain evidence; never claim buried objects as fact.
        """.trimIndent()
        val answer = gateway.generate(
            conversation = listOf(GeminiConversationTurn("user", prompt)),
            systemContext = """
                You are an archaeological remote-sensing classifier. Use morphology, relief, slope breaks,
                geometric regularity, and natural-drainage context. Prefer Natural erosion or Uncertain
                when the evidence is ambiguous. Terrain context: ${context.terrainSummary}
            """.trimIndent(),
            image = region.croppedBitmap?.let(::encode),
            requestedProvider = requestedProvider,
            onProviderStage = { stage -> onStage("Candidate ${index + 1}/${regions.size}: $stage") },
        )
        parse(answer.text, region)
    }

    private fun parse(text: String, region: AnomalyRegion): AnomalyClassification {
        val line = text.lineSequence().firstOrNull { it.count { c -> c == '|' } >= 2 } ?: text.trim()
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) return AnomalyClassification(region, "Uncertain", 0f, line.ifBlank { "No classification received" })
        return AnomalyClassification(
            region = region,
            label = parts[0].trim().ifBlank { "Uncertain" },
            confidence = parts[1].trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f,
            description = parts[2].trim().ifBlank { "No description received" },
        )
    }

    private fun encode(bitmap: Bitmap): GeminiImageInput? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val bytes = ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) return null
            output.toByteArray()
        }
        return bytes.takeIf { it.size <= 3 * 1024 * 1024 }?.let {
            GeminiImageInput(it, "image/jpeg", "cropped disturbance candidate")
        }
    }
}
