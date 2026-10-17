package com.example.data

import com.example.geospatial.GeoSpatialLibrary

data class LidarTileSearchResult(
    val tiles: List<NysHistoricLazTileCatalog.Tile>,
    val copc: List<CopcAsset>,
    val status: String,
    val error: Throwable? = null,
)

suspend fun searchPublicLidarTiles(
    source: LidarDownloadSource,
    bounds: GeoSpatialLibrary.GeographicBounds,
    usgsCatalog: NysHistoricLazTileCatalog,
    copcCatalog: CopcStacCatalog,
    noaaCatalog: NoaaCoastalLidarCatalog,
): LidarTileSearchResult {
    return when (source) {
        LidarDownloadSource.FAST_AZURE -> {
            val copcResult = runCatching { copcCatalog.search(bounds) }
            val assets = copcResult.getOrDefault(emptyList())
            val azureTiles = rankLidarTiles(assets.map { it.toDownloadTile() })
            if (azureTiles.isNotEmpty()) {
                LidarTileSearchResult(
                    tiles = azureTiles,
                    copc = assets,
                    status = azureCopcStatus(azureTiles.size),
                )
            } else {
                val usgs = runCatching {
                    usgsCatalog.tilesInBounds(bounds.minLon, bounds.minLat, bounds.maxLon, bounds.maxLat)
                }
                val usgsTiles = usgs.getOrDefault(emptyList())
                if (usgsTiles.isEmpty()) {
                    copcResult.exceptionOrNull()?.let { throw it }
                    usgs.exceptionOrNull()?.let { throw it }
                }
                LidarTileSearchResult(
                    tiles = usgsTiles,
                    copc = emptyList(),
                    status = azureFallbackStatus(usgsTiles.size),
                    error = usgs.exceptionOrNull() ?: copcResult.exceptionOrNull(),
                )
            }
        }
        LidarDownloadSource.USGS_TNM -> {
            val usgs = runCatching {
                usgsCatalog.tilesInBounds(bounds.minLon, bounds.minLat, bounds.maxLon, bounds.maxLat)
            }
            val usgsTiles = usgs.getOrDefault(emptyList())
            if (usgsTiles.isEmpty()) usgs.exceptionOrNull()?.let { throw it }
            LidarTileSearchResult(
                tiles = usgsTiles,
                copc = emptyList(),
                status = usgsTnmStatus(usgsTiles.size),
                error = usgs.exceptionOrNull(),
            )
        }
        LidarDownloadSource.NOAA_COAST -> {
            val noaa = runCatching {
                noaaCatalog.tilesInBounds(bounds.minLon, bounds.minLat, bounds.maxLon, bounds.maxLat)
            }
            val noaaTiles = noaa.getOrDefault(emptyList())
            if (noaaTiles.isEmpty() && noaa.exceptionOrNull() != null) {
                throw noaa.exceptionOrNull()!!
            }
            LidarTileSearchResult(
                tiles = noaaTiles,
                copc = emptyList(),
                status = noaaCoastStatus(noaaTiles.size),
                error = noaa.exceptionOrNull(),
            )
        }
    }
}

internal fun azureCopcStatus(count: Int): String =
    "Found $count Azure 3DEP COPC tile${if (count == 1) "" else "s"} " +
        "(fast download). Newer / denser surveys are listed first."

internal fun azureFallbackStatus(count: Int): String =
    if (count == 0) {
        "No Azure COPC or USGS tiles cover that area."
    } else {
        "Azure had no COPC here — using $count slower USGS TNM file${if (count == 1) "" else "s"}."
    }

internal fun usgsTnmStatus(count: Int): String = when {
    count == 0 -> "No USGS TNM tiles intersect that area."
    count >= NysHistoricLazTileCatalog.MAX_NATIONAL_MAP_RESULTS ->
        "Found $count USGS files — the search hit its cap. Narrow the box."
    else ->
        "Found $count USGS TNM file${if (count == 1) "" else "s"}. " +
            "These rockyweb downloads are the slow official copies."
}

internal fun noaaCoastStatus(count: Int): String =
    if (count == 0) {
        "No NOAA Digital Coast COPC intersects that area. Inland sites usually need Fast Azure."
    } else {
        "Found $count NOAA coastal COPC tile${if (count == 1) "" else "s"} on AWS S3."
    }
