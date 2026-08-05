package com.example.data.field

/**
 * One excavation/check log for a field target. Every entry stays tied to its project
 * ([terrainKey]) and target ([targetId]) so a complete visit can be reconstructed offline.
 * Text fields are stored as plain Kotlin strings here; the Room entity applies the same
 * newline-safe URI codec as target signals.
 */
data class ExcavationLogEntry(
    val id: String,
    val targetId: Long,
    val terrainKey: String?,
    val startedAtMillis: Long,
    val completedAtMillis: Long?,
    val depthCentimeters: Int?,
    val soilNotes: String,
    val findsDescription: String,
    val findsCount: Int,
    val photoUris: List<String>,
    val voiceNoteUris: List<String>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    val isComplete: Boolean
        get() = completedAtMillis != null

    fun complete(atMillis: Long): ExcavationLogEntry =
        copy(completedAtMillis = atMillis, updatedAtMillis = atMillis)

    fun withFinds(description: String, count: Int, atMillis: Long): ExcavationLogEntry =
        copy(
            findsDescription = description,
            findsCount = count.coerceAtLeast(0),
            updatedAtMillis = atMillis,
        )
}
