package com.example.thermals

/**
 * What to tell the user about a Mapbox loading error. A failed style, sprite or source means the
 * map really didn't load; a single failed tile (rate limit, flaky signal) is retried by the SDK and
 * the rest of the map is fine, so it gets a quiet, self-clearing note instead of a red banner.
 */
data class MapLoadIssue(val text: String, val fatal: Boolean) {
    companion object {
        /** How long a tile note stays up after the last tile error before it clears itself. */
        const val TILE_NOTE_MS = 8_000L

        /** [type] is the MapLoadingErrorType name (STYLE, SPRITE, SOURCE, GLYPHS, TILE). */
        fun from(type: String, message: String, sourceId: String?): MapLoadIssue {
            val tokenProblem = message.contains("token", ignoreCase = true) ||
                Regex("""\b40[13]\b""").containsMatchIn(message)
            if (tokenProblem) {
                return MapLoadIssue(
                    "Map failed to load: $message — check that MAPBOX_ACCESS_TOKEN is a valid public (pk.) token, then rebuild.",
                    fatal = true,
                )
            }
            if (!type.equals("TILE", ignoreCase = true)) {
                return MapLoadIssue("Map failed to load: $message", fatal = true)
            }
            val what = when {
                sourceId == null -> "Some map tiles"
                sourceId.contains("dem", ignoreCase = true) -> "Some 3D terrain tiles"
                else -> "Some map tiles"
            }
            val text = if (Regex("""\b429\b""").containsMatchIn(message) || message.contains("Too Many Requests", ignoreCase = true)) {
                "$what are being rate-limited by Mapbox — retrying."
            } else {
                "$what didn't load — retrying."
            }
            return MapLoadIssue(text, fatal = false)
        }
    }
}
