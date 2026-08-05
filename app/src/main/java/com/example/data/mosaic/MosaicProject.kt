package com.example.data.mosaic

import com.example.geospatial.GeoSpatialLibrary
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The durable state of a logical collection of downloaded LiDAR source files.
 *
 * A project is created before its transfers begin. This keeps an interrupted area selection
 * recoverable instead of leaving a collection of otherwise valid files with no way to resume the
 * requested mosaic.
 */
enum class MosaicProjectState {
    DOWNLOADING,
    READY,
    NEEDS_ATTENTION,
}

data class MosaicProjectTile(
    val displayName: String,
    val localFileName: String,
    val sourceUrl: String,
    val bounds: GeoSpatialLibrary.GeographicBounds,
)

data class MosaicProject(
    val id: String,
    val displayName: String,
    val tiles: List<MosaicProjectTile>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val state: MosaicProjectState = MosaicProjectState.READY,
    val recoveryMessage: String? = null,
    /** Original rectangle, radius, or polygon that resolved this source-tile set, when applicable. */
    val areaSelectionDescription: String? = null,
)

/** Versioned, line-oriented manifest that avoids treating untrusted source URLs as executable data. */
internal fun MosaicProject.tilesToManifest(): String = buildString {
    append("v1\n")
    tiles.forEach { tile ->
        append(encode(tile.displayName)).append('\t')
        append(encode(tile.localFileName)).append('\t')
        append(encode(tile.sourceUrl)).append('\t')
        append(tile.bounds.minLat).append('\t')
        append(tile.bounds.maxLat).append('\t')
        append(tile.bounds.minLon).append('\t')
        append(tile.bounds.maxLon).append('\n')
    }
}

internal fun mosaicTilesFromManifest(manifest: String): List<MosaicProjectTile> {
    val lines = manifest.lineSequence().toList()
    if (lines.firstOrNull() != "v1") return emptyList()
    return lines.drop(1).mapNotNull { row ->
        val fields = row.split('\t')
        if (fields.size != 7) return@mapNotNull null
        val minLat = fields[3].toDoubleOrNull() ?: return@mapNotNull null
        val maxLat = fields[4].toDoubleOrNull() ?: return@mapNotNull null
        val minLon = fields[5].toDoubleOrNull() ?: return@mapNotNull null
        val maxLon = fields[6].toDoubleOrNull() ?: return@mapNotNull null
        if (minLat !in -90.0..90.0 || maxLat !in -90.0..90.0 ||
            minLon !in -180.0..180.0 || maxLon !in -180.0..180.0 ||
            minLat >= maxLat || minLon >= maxLon
        ) return@mapNotNull null
        runCatching {
            MosaicProjectTile(
                displayName = decode(fields[0]),
                localFileName = decode(fields[1]),
                sourceUrl = decode(fields[2]),
                bounds = GeoSpatialLibrary.GeographicBounds(minLat, maxLat, minLon, maxLon),
            )
        }.getOrNull()
    }
}

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
