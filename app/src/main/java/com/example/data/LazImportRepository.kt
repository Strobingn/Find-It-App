package com.example.data

import java.io.File
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class LazImportRepository(
    private val downloader: LazDownloadManager,
) {
    suspend fun importFromUrl(
        url: String,
        store: LazDatasetStore,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        require(isSupportedRemoteUrl(url)) { "Enter a direct HTTPS LAZ or LAS download URL" }
        val downloadContext = currentCoroutineContext()
        downloader.download(
            sourceUrl = url,
            destinationDirectory = store.directory,
            progress = onProgress,
            shouldContinue = { downloadContext.isActive },
        )
    }

    fun isSupportedRemoteUrl(url: String): Boolean {
        return runCatching {
            val parsed = URL(url.trim())
            parsed.protocol.equals("https", ignoreCase = true) && parsed.host.isNotBlank()
        }.getOrDefault(false)
    }

    fun looksLikeLazUrl(url: String): Boolean {
        val path = runCatching { URL(url.trim()).path }.getOrDefault(url)
        return path.substringAfterLast('.', "").lowercase(Locale.US) in setOf("laz", "las")
    }
}

object NoaaLidarCatalog {
    const val DATA_VIEWER_URL = "https://coast.noaa.gov/dataviewer/#/lidar/search"
    const val NYS_LAS_TILE_INDEX_URL = "https://orthos.its.ny.gov/arcgis/rest/services/vector/las_indexes/MapServer"
    const val NYS_SOUTHEAST_4_COUNTY_LAYER_URL = "$NYS_LAS_TILE_INDEX_URL/4"
    const val NYS_SOUTHEAST_4_COUNTY_USGS_LAZ_DIRECTORY =
        "https://rockyweb.usgs.gov/vdelivery/Datasets/Staged/Elevation/LPC/Projects/NY_SouthEast4County_A22/NY_SE4County_1_A22/LAZ/"
}
