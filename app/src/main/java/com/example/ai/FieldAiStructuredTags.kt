package com.example.ai

/**
 * Parsers for optional machine lines emitted by pack-3 Field AI features
 * ([NAV_TARGET], [VIZ_MODE], [METAL_TYPE], [OUTCOME], [STATUS], [NOTES]).
 * Style matches [FieldAiCopilot.parseLightingRecommendation] (case-insensitive, flexible spacing).
 */
object FieldAiStructuredTags {

    private val navTargetPattern = Regex(
        """NAV_TARGET\s+id\s*=\s*([0-9]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val vizModePattern = Regex(
        """VIZ_MODE\s*=\s*([0-9]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val metalTypePattern = Regex(
        """METAL_TYPE\s*=\s*(.+)""",
        RegexOption.IGNORE_CASE,
    )
    private val outcomePattern = Regex(
        """OUTCOME\s*=\s*(.+)""",
        RegexOption.IGNORE_CASE,
    )
    private val statusPattern = Regex(
        """STATUS\s*=\s*(.+)""",
        RegexOption.IGNORE_CASE,
    )
    private val notesPattern = Regex(
        """NOTES\s*=\s*(.+)""",
        RegexOption.IGNORE_CASE,
    )

    /** Ordered unique signal ids from `NAV_TARGET id=<long>` lines. */
    fun parseNavTargetIds(text: String): List<Long> {
        val seen = LinkedHashSet<Long>()
        navTargetPattern.findAll(text).forEach { match ->
            match.groupValues.getOrNull(1)?.toLongOrNull()?.let { seen.add(it) }
        }
        return seen.toList()
    }

    /** First `VIZ_MODE=<0-8>` value, or null if missing/out of range. */
    fun parseVizMode(text: String): Int? {
        val raw = vizModePattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        return raw.takeIf { it in 0..8 }
    }

    fun parseMetalTypeSuggestion(text: String): String? =
        firstTagValue(metalTypePattern, text)

    fun parseOutcomeSuggestion(text: String): String? =
        firstTagValue(outcomePattern, text)

    fun parseStatusSuggestion(text: String): String? =
        firstTagValue(statusPattern, text)

    fun parseNotesSuggestion(text: String): String? =
        firstTagValue(notesPattern, text)

    private fun firstTagValue(pattern: Regex, text: String): String? {
        val raw = pattern.find(text)?.groupValues?.getOrNull(1) ?: return null
        val cleaned = raw
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.trimEnd('\r')
            ?.takeWhile { it != '\n' }
            ?.trim()
            .orEmpty()
        return cleaned.takeIf { it.isNotBlank() }
    }
}
