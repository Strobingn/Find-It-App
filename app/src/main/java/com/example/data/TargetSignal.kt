package com.example.data

import java.io.Serializable

/**
 * Represents a detected and logged metallic target signal on our map grid.
 */
data class TargetSignal(
    val id: Long = System.currentTimeMillis() + (0..1000).random(),
    val gridX: Float, // X position (0 to 100) on our DEM grid
    val gridY: Float, // Y position (0 to 100) on our DEM grid
    val metalType: MetalType,
    val signalStrength: Float, // percentage (0f to 100f)
    val depthCm: Int, // Calculated approximate depth (e.g., 5 to 50 cm)
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String = "",
    val status: String = "Logged" // "Logged", "Excavated", "Anomalous", "Trash"
) : Serializable

enum class MetalType(val label: String, val colorHex: Long) {
    GOLD("Gold Coin/Ring", 0xFFFFD700),      // Glowing gold
    SILVER("Silver Relic", 0xFFC0C0C0),     // Silver / grey
    BRONZE("Bronze artifact", 0xFFCD7F32),  // Bronze / copper brown
    IRON("Iron Nail/Spike", 0xFF8B0000)      // Crimson / dark red (usually ferrous scrap)
}
