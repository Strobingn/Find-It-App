package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LazImportRepository(
    private val downloader: LazDownloadManager,
) {
    suspend fun importFromUrl(
        url: String,
        destination: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File? = withContext(Dispatchers.IO) {
        downloader.download(url, destination, onProgress)
    }

    fun isLazUrl(url: String): Boolean {
        return url.lowercase().substringBefore('?').endsWith(".laz") ||
            url.lowercase().substringBefore('?').endsWith(".las")
    }
}

object NoaaLidarCatalog {
    const val DATA_VIEWER_URL = "https://coast.noaa.gov/dataviewer/#/lidar/search"
}
