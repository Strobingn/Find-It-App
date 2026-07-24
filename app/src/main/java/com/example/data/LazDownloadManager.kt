package com.example.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads LAZ/LAS files into app storage instead of handing URLs to external downloaders.
 * Streams bytes to disk so large datasets do not consume RAM.
 */
class LazDownloadManager {

    companion object {
        private const val MAX_IMPORT_BYTES = 10L * 1024 * 1024 * 1024
    }

    fun download(
        sourceUrl: String,
        destination: File,
        progress: ((Long, Long) -> Unit)? = null,
    ): File {
        require(sourceUrl.startsWith("http")) { "Invalid LAZ URL" }

        val connection = URL(sourceUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        connection.requestMethod = "GET"

        connection.connect()

        val size = connection.contentLengthLong
        require(size <= 0 || size <= MAX_IMPORT_BYTES) {
            "LAZ file exceeds 10GB import limit"
        }

        destination.parentFile?.mkdirs()

        connection.inputStream.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024)
                var total = 0L
                var read: Int

                while (input.read(buffer).also { read = it } >= 0) {
                    output.write(buffer, 0, read)
                    total += read
                    progress?.invoke(total, size)
                }
            }
        }

        connection.disconnect()
        return destination
    }
}
