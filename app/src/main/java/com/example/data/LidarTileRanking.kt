package com.example.data

/** Newer, denser surveys first so Refine has more native points to work with. */
fun rankLidarTiles(tiles: List<NysHistoricLazTileCatalog.Tile>): List<NysHistoricLazTileCatalog.Tile> {
    return tiles.sortedWith(
        compareByDescending<NysHistoricLazTileCatalog.Tile> { it.acquiredYear ?: Int.MIN_VALUE }
            .thenByDescending { it.pointCount ?: 0L }
            .thenByDescending { it.sizeBytes ?: 0L }
            .thenBy { it.name },
    )
}

fun lidarTileSubtitle(tile: NysHistoricLazTileCatalog.Tile): String {
    val parts = buildList {
        tile.acquiredYear?.let { add(it.toString()) }
        tile.pointCount?.takeIf { it > 0L }?.let { add(formatPointCount(it)) }
        tile.sizeBytes?.takeIf { it > 0L }?.let { add(formatCompactBytes(it)) }
        add(tile.hostLabel.ifBlank { tile.project.ifBlank { tile.source.shortHost } })
    }
    return parts.joinToString(" · ")
}

internal fun formatPointCount(count: Long): String = when {
    count >= 1_000_000L -> "${count / 1_000_000L}M pts"
    count >= 1_000L -> "${count / 1_000L}k pts"
    else -> "$count pts"
}

fun CopcAsset.toDownloadTile(): NysHistoricLazTileCatalog.Tile {
    val year = datetime?.let { Regex("(20\\d{2}|19\\d{2})").find(it)?.value?.toIntOrNull() }
    val fileName = href.substringAfterLast('/').substringBefore('?').ifBlank { "$id.copc.laz" }
    return NysHistoricLazTileCatalog.Tile(
        objectId = id.hashCode().toLong(),
        name = fileName,
        downloadUrl = href,
        minLongitude = bounds?.minLon,
        minLatitude = bounds?.minLat,
        maxLongitude = bounds?.maxLon,
        maxLatitude = bounds?.maxLat,
        project = title,
        source = LidarDownloadSource.FAST_AZURE,
        hostLabel = LidarDownloadSource.FAST_AZURE.shortHost,
        pointCount = pointCount,
        acquiredYear = year,
    )
}

internal fun formatCompactBytes(bytes: Long): String {
    val mib = bytes / (1024.0 * 1024.0)
    return if (mib >= 10.0) {
        "${mib.toInt()} MiB"
    } else {
        java.lang.String.format(java.util.Locale.US, "%.1f MiB", mib)
    }
}
