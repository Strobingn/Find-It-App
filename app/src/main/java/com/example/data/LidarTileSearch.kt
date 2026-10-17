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
    openTopographyCatalog: OpenTopographyCatalog,
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
        LidarDownloadSource.OPEN_TOPOGRAPHY -> {
            val openTopo = runCatching {
                openTopographyCatalog.tilesInBounds(
                    bounds.minLon,
                    bounds.minLat,
                    bounds.maxLon,
                    bounds.maxLat,
                )
            }
            val tiles = openTopo.getOrDefault(emptyList())
            if (tiles.isEmpty() && openTopo.exceptionOrNull() != null) {
                throw openTopo.exceptionOrNull()!!
            }
            LidarTileSearchResult(
                tiles = tiles,
                copc = emptyList(),
                status = openTopographyStatus(tiles.size),
                error = openTopo.exceptionOrNull(),
            )
        }
    }
}

suspend fun signPublicLidarDownloadUrl(
    url: String,
    copcCatalog: CopcStacCatalog,
    openTopographyApiKey: String,
): String {
    val azureSigned = runCatching { copcCatalog.signIfNeeded(url) }.getOrDefault(url)
    return OpenTopographyCatalog.attachApiKeyIfNeeded(azureSigned, openTopographyApiKey)
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

internal fun openTopographyStatus(count: Int): String =
    if (count == 0) {
        "No OpenTopography point clouds intersect that area. Try Fast Azure for nationwide 3DEP."
    } else {
        "Found $count OpenTopography dataset${if (count == 1) "" else "s"} — each download is cropped to your box."
    }
