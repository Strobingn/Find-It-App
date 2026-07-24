package com.example.data

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Downloads LAZ/LAS files into persistent app storage.
 *
 * Bytes are streamed directly to a temporary file and atomically promoted only after the
 * complete download succeeds, so multi-gigabyte datasets never need to fit in the app heap.
 */
class LazDownloadManager {

    companion object {
        const val MAX_IMPORT_BYTES: Long = 10L * 1024L * 1024L * 1024L
        private const val MAX_REDIRECTS = 5
        private const val BUFFER_BYTES = 1024 * 1024
        private const val PROGRESS_STEP_BYTES = 4L * 1024L * 1024L
    }

    fun download(
        sourceUrl: String,
        destinationDirectory: File,
        progress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
    ): File {
        destinationDirectory.mkdirs()
        require(destinationDirectory.isDirectory) { "Unable to create LAZ storage directory" }

        var currentUrl = validateRemoteUrl(URL(sourceUrl.trim()))
        var connection: HttpURLConnection? = null
        var redirects = 0

        try {
            while (true) {
                if (!shouldContinue()) throw CancellationException("LAZ download cancelled")
                connection?.disconnect()
                connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 90_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Find-It-Android/1.0")
                    setRequestProperty("Accept", "application/octet-stream,*/*")
                }

                val status = connection.responseCode
                if (status in 300..399) {
                    check(redirects++ < MAX_REDIRECTS) { "Too many redirects" }
                    val location = connection.getHeaderField("Location")
                        ?: error("Redirect had no destination")
                    currentUrl = validateRemoteUrl(URL(currentUrl, location))
                    continue
                }

                check(status == HttpURLConnection.HTTP_OK) { "Server returned HTTP $status" }
                break
            }

            val active = requireNotNull(connection)
            val declaredSize = active.contentLengthLong
            check(declaredSize <= 0L || declaredSize <= MAX_IMPORT_BYTES) {
                "LAZ file exceeds the 10 GB import limit"
            }

            val responseName = contentDispositionFileName(active.getHeaderField("Content-Disposition"))
            val urlName = currentUrl.path.substringAfterLast('/').ifBlank { "lidar-download.laz" }
            val finalName = sanitizeAndValidateName(responseName ?: urlName)
            val destination = uniqueDestination(destinationDirectory, finalName)
            val partial = File(destinationDirectory, ".${destination.name}.part")
            partial.delete()

            var downloaded = 0L
            var lastReported = 0L
            progress?.invoke(0L, declaredSize)

            try {
                active.inputStream.use { input ->
                    FileOutputStream(partial).buffered(BUFFER_BYTES).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            if (!shouldContinue()) throw CancellationException("LAZ download cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue

                            downloaded += count
                            check(downloaded <= MAX_IMPORT_BYTES) {
                                "LAZ file exceeds the 10 GB import limit"
                            }
                            output.write(buffer, 0, count)

                            if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                                lastReported = downloaded
                                progress?.invoke(downloaded, declaredSize)
                            }
                        }
                        output.flush()
                    }
                }

                if (!shouldContinue()) throw CancellationException("LAZ download cancelled")
                check(downloaded > 0L) { "Downloaded file was empty" }
                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = false)
                    partial.delete()
                }
                progress?.invoke(downloaded, if (declaredSize > 0L) declaredSize else downloaded)
                return destination
            } catch (error: Throwable) {
                partial.delete()
                throw error
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun sanitizeAndValidateName(rawName: String): String {
        val stripped = rawName.substringAfterLast('/').substringBefore('?').trim(' ', '"', '\'')
        val safe = stripped.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "lidar-download.laz" }
        val extension = safe.substringAfterLast('.', "").lowercase(Locale.US)
        require(extension == "laz" || extension == "las") {
            "The URL must resolve to a direct LAZ or LAS file"
        }
        return safe
    }

    private fun uniqueDestination(directory: File, requestedName: String): File {
        val first = File(directory, requestedName)
        if (!first.exists()) return first

        val extension = requestedName.substringAfterLast('.', "")
        val base = requestedName.removeSuffix(if (extension.isBlank()) "" else ".$extension")
        var index = 2
        while (true) {
            val candidate = File(directory, "$base-$index.$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun contentDispositionFileName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(header)
            ?.groupValues
            ?.getOrNull(1)
        if (!encoded.isNullOrBlank()) return java.net.URLDecoder.decode(encoded, Charsets.UTF_8.name())

        return Regex("filename=\\\"?([^;\\\"]+)", RegexOption.IGNORE_CASE)
            .find(header)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    private fun validateRemoteUrl(url: URL): URL {
        require(url.protocol.equals("https", ignoreCase = true)) { "Only HTTPS downloads are allowed" }
        require(url.userInfo == null) { "URLs containing credentials are not allowed" }
        val host = url.host.lowercase(Locale.US)
        require(host.isNotBlank() && host != "localhost" && !host.endsWith(".localhost")) {
            "Invalid download host"
        }
        InetAddress.getAllByName(host).forEach { address ->
            val bytes = address.address
            val uniqueLocalV6 = bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
            require(
                !address.isAnyLocalAddress &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress &&
                    !address.isSiteLocalAddress &&
                    !address.isMulticastAddress &&
                    !uniqueLocalV6,
            ) { "Private and local network downloads are blocked" }
        }
        return url
    }
}
