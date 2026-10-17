package com.example.data

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.min

/**
 * Downloads LAZ/LAS files into persistent app storage.
 *
 * Bytes are streamed directly to a resumable temporary file and atomically promoted only after the
 * complete download succeeds, so multi-gigabyte datasets never need to fit in the app heap. Large
 * NYS/USGS transfers occasionally close a mobile socket mid-stream; retryable failures retain the
 * partial bytes and continue with an HTTP Range request instead of discarding the whole download.
 */
class LazDownloadManager(
    /** Parallel ranged connections for large files; 1 disables the multipart path. */
    private val parallelConnections: Int = DEFAULT_PARALLEL_CONNECTIONS,
    /** Faster byte-identical mirror lookup; null disables mirrors. */
    private val mirrorResolver: com.example.data.download.LazMirrorResolver? = sharedMirrorResolver,
    /** USGS 3DEP EPT rebuild of TNM tiles (fast S3); null disables it. */
    private val eptBuilder: com.example.data.download.EptTileBuilder? = com.example.data.download.EptTileBuilder.shared,
    /** OpenTopography catalog used to find 3DEP EPT resources; null disables that source. */
    private val openTopography: com.example.data.download.OpenTopographyCatalog? = sharedOpenTopography,
) {

    companion object {
        internal val sharedMirrorResolver = com.example.data.download.LazMirrorResolver()
        internal val sharedOpenTopography by lazy {
            com.example.data.download.OpenTopographyCatalog(apiKey = com.example.BuildConfig.OPENTOPO_API_KEY)
        }
        const val MAX_IMPORT_BYTES: Long = 10L * 1024L * 1024L * 1024L
        private const val MAX_REDIRECTS = 5
        private const val MAX_ATTEMPTS = 5
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val BUFFER_BYTES = 1024 * 1024
        private const val PROGRESS_STEP_BYTES = 4L * 1024L * 1024L
        private const val RETRY_BASE_DELAY_MS = 1_000L
        private const val RETRY_MAX_DELAY_MS = 8_000L
        internal const val DEFAULT_PARALLEL_CONNECTIONS = 12
        /** Below this, one connection is already near line rate and setup cost dominates. */
        /** USGS rockyweb serves ~0.35 MB/s per connection, so even 10 MB tiles gain from ranges. */
        private const val PARALLEL_MIN_BYTES = 2L * 1024L * 1024L
        /**
         * Work unit for the connection pool. Many small segments pulled from a queue keep every
         * connection busy to the end instead of waiting on the slowest of N equal slices.
         */
        internal const val SEGMENT_BYTES = 4L * 1024L * 1024L
        private const val MAX_SEGMENTS = 4_096
        private const val SEGMENT_STATE_SUFFIX = ".segments"

        /** Shared by LAS and LAZ; LAZ is LAS with compressed point data. */
        private val LIDAR_SIGNATURE = "LASF".toByteArray(Charsets.US_ASCII)
    }

    fun download(
        sourceUrl: String,
        destinationDirectory: File,
        progress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
        source: com.example.data.download.LazDownloadSource = com.example.data.download.LazDownloadSource.DEFAULT,
        onNotice: (String) -> Unit = {},
    ): File {
        val steps = source.steps
        var eptFailure: String? = null
        val eptTried = com.example.data.download.LazDownloadSource.Step.EPT in steps && eptBuilder != null
        if (eptTried) {
            try {
                val built = eptBuilder!!.build(sourceUrl.trim(), destinationDirectory, progress, shouldContinue)
                if (built != null) {
                    System.err.println("LAZ via EPT ${built.resource}: ${built.nodes} nodes, ${built.points} points")
                    val size = built.file.length()
                    progress?.invoke(size, size)
                    return built.file
                }
                eptFailure = "no EPT coverage"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // EPT unavailable or mismatched: the original download path below is unchanged.
                System.err.println("LAZ EPT rebuild failed (${error.message}); using original URL")
                eptFailure = error.message ?: error.javaClass.simpleName
            }
        }
        val otTried = com.example.data.download.LazDownloadSource.Step.OPENTOPOGRAPHY in steps &&
            eptBuilder != null && openTopography != null
        if (otTried) {
            try {
                val built = eptBuilder!!.build(
                    sourceUrl.trim(), destinationDirectory, progress, shouldContinue,
                    resourcesFor = { box ->
                        // Skip surveys the URL-derived EPT attempt already tried (no double fetch).
                        val tried = if (eptTried) {
                            com.example.data.download.EptTileBuilder.candidateResources(sourceUrl.trim()).toSet()
                        } else {
                            emptySet()
                        }
                        openTopography!!.usgs3depResources(box).filterNot { it in tried }
                    },
                )
                if (built != null) {
                    System.err.println("LAZ via OpenTopography/EPT ${built.resource}: ${built.nodes} nodes, ${built.points} points")
                    val size = built.file.length()
                    progress?.invoke(size, size)
                    return built.file
                }
                eptFailure = "no matching OpenTopography 3DEP survey"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                System.err.println("LAZ OpenTopography lookup failed (${error.message}); using original URL")
                eptFailure = error.message ?: error.javaClass.simpleName
            }
        }
        val chosenFirst = steps.firstOrNull()
        if (eptTried || otTried || chosenFirst == com.example.data.download.LazDownloadSource.Step.EPT ||
            chosenFirst == com.example.data.download.LazDownloadSource.Step.OPENTOPOGRAPHY
        ) {
            source.fallbackNotice(sourceUrl.substringAfterLast('/'), eptFailure ?: "source disabled")?.let(onNotice)
        }
        if (com.example.data.download.LazDownloadSource.Step.MIRROR in steps) {
            val mirror = runCatching { mirrorResolver?.resolve(sourceUrl.trim()) }.getOrNull()
            if (mirror != null) {
                try {
                    return downloadFrom(mirror, destinationDirectory, progress, shouldContinue, nameUrl = sourceUrl)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    // Mirror failed: fall back to the original URL (same file name, so a partial resumes).
                    System.err.println("LAZ mirror $mirror failed (${error.message}); using original URL")
                }
            }
        }
        return downloadFrom(sourceUrl, destinationDirectory, progress, shouldContinue, nameUrl = sourceUrl)
    }

    private fun downloadFrom(
        sourceUrl: String,
        destinationDirectory: File,
        progress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)?,
        shouldContinue: () -> Boolean,
        nameUrl: String,
    ): File {
        destinationDirectory.mkdirs()
        require(destinationDirectory.isDirectory) { "Unable to create LAZ storage directory" }

        val originalUrl = validateRemoteUrl(URL(sourceUrl.trim()))
        var destination: File? = runCatching {
            val urlName = URL(nameUrl.trim()).path.substringAfterLast('/').ifBlank { "lidar-download.laz" }
            uniqueDestination(destinationDirectory, sanitizeAndValidateName(urlName))
        }.getOrNull()
        var partial: File? = destination?.let { File(destinationDirectory, ".${it.name}.part") }
        var expectedTotal = -1L
        var lastFailure: Throwable? = null

        val parallelTarget = destination
        val parallelPartial = partial
        if (parallelConnections > 1 && parallelTarget != null && parallelPartial != null) {
            val done = downloadParallel(originalUrl, parallelTarget, parallelPartial, progress, shouldContinue)
            if (done != null) return done
            // Server does not support ranges (or the file is small): sequential path below.
        }

        repeat(MAX_ATTEMPTS) { attempt ->
            if (!shouldContinue()) throw CancellationException("LAZ download cancelled")

            var connection: HttpURLConnection? = null
            try {
                val existingBytes = partial?.takeIf(File::isFile)?.length()?.coerceAtLeast(0L) ?: 0L
                val opened = openFollowingRedirects(originalUrl, existingBytes)
                connection = opened.connection
                val status = opened.status

                if (status == HTTP_RANGE_NOT_SATISFIABLE) {
                    val serverLength = parseUnsatisfiedRangeTotal(connection.getHeaderField("Content-Range"))
                    val completedPartial = partial
                    val completedDestination = destination
                    if (existingBytes > 0L && serverLength == existingBytes &&
                        completedPartial != null && completedDestination != null
                    ) {
                        // Same gate as the streaming path: a retained partial holding the wrong
                        // payload must not be promoted just because its length matches.
                        validatePointCloudSignature(completedPartial)
                        promote(completedPartial, completedDestination)
                        progress?.invoke(existingBytes, existingBytes)
                        return completedDestination
                    }
                    completedPartial?.delete()
                    throw RetryableHttpException(status, "Server rejected the saved download range")
                }

                if (status == 408 || status == 425 || status == 429 || status in 500..599) {
                    throw RetryableHttpException(status, "Temporary download server error HTTP $status")
                }
                check(status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_PARTIAL) {
                    "Server returned HTTP $status"
                }

                if (destination == null || partial == null) {
                    val responseName = contentDispositionFileName(connection.getHeaderField("Content-Disposition"))
                    val urlName = opened.url.path.substringAfterLast('/').ifBlank { "lidar-download.laz" }
                    val finalName = sanitizeAndValidateName(responseName ?: urlName)
                    destination = uniqueDestination(destinationDirectory, finalName)
                    partial = File(destinationDirectory, ".${requireNotNull(destination).name}.part")
                }

                val target = requireNotNull(destination)
                val workingFile = requireNotNull(partial)
                var resumeFrom = workingFile.takeIf(File::isFile)?.length()?.coerceAtLeast(0L) ?: 0L
                var append = status == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0L

                if (status == HttpURLConnection.HTTP_PARTIAL) {
                    val range = parseContentRange(connection.getHeaderField("Content-Range"))
                        ?: throw IOException("Partial response had no valid Content-Range")
                    if (range.start != resumeFrom) {
                        workingFile.delete()
                        throw RetryableHttpException(
                            status,
                            "Server resumed at byte ${range.start}, expected $resumeFrom",
                        )
                    }
                    expectedTotal = range.total
                } else {
                    // Some servers ignore Range and return a complete 200 response. Restart this
                    // attempt from byte zero rather than appending a second copy to the partial file.
                    if (resumeFrom > 0L) {
                        FileOutputStream(workingFile, false).use { }
                        resumeFrom = 0L
                    }
                    append = false
                    expectedTotal = connection.contentLengthLong
                }

                check(expectedTotal <= 0L || expectedTotal <= MAX_IMPORT_BYTES) {
                    "LAZ file exceeds the 10 GB import limit"
                }

                var downloaded = resumeFrom
                var lastReported = downloaded
                progress?.invoke(downloaded, expectedTotal)

                connection.inputStream.use { rawInput ->
                    rawInput.buffered(BUFFER_BYTES).use { input ->
                        FileOutputStream(workingFile, append).buffered(BUFFER_BYTES).use { output ->
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
                                    progress?.invoke(downloaded, expectedTotal)
                                }
                            }
                            output.flush()
                        }
                    }
                }

                if (!shouldContinue()) throw CancellationException("LAZ download cancelled")
                check(downloaded > 0L) { "Downloaded file was empty" }
                if (expectedTotal > 0L && downloaded < expectedTotal) {
                    throw EOFException("Connection ended at $downloaded of $expectedTotal bytes")
                }
                if (expectedTotal > 0L && downloaded > expectedTotal) {
                    workingFile.delete()
                    error("Downloaded more data than the server declared")
                }
                validatePointCloudSignature(workingFile)

                promote(workingFile, target)
                progress?.invoke(downloaded, if (expectedTotal > 0L) expectedTotal else downloaded)
                return target
            } catch (cancelled: CancellationException) {
                // Deliberately retain the .part file. A later tap resumes instead of starting over.
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
                val canRetry = attempt < MAX_ATTEMPTS - 1 && isRetryable(failure)
                if (!canRetry) {
                    val retained = partial?.takeIf(File::isFile)?.length() ?: 0L
                    val suffix = if (retained > 0L) {
                        " ($retained bytes saved; tap again to resume)"
                    } else {
                        ""
                    }
                    throw IOException(
                        (failure.localizedMessage ?: "LAZ download failed") + suffix,
                        failure,
                    )
                }
                progress?.invoke(partial?.takeIf(File::isFile)?.length() ?: 0L, expectedTotal)
                waitForRetry(attempt, shouldContinue)
            } finally {
                connection?.disconnect()
            }
        }

        throw IOException(lastFailure?.localizedMessage ?: "LAZ download failed", lastFailure)
    }

    /**
     * Multipart download: N HTTP Range connections each fill a disjoint byte range of a
     * preallocated .part file. Per-segment progress is persisted in a .segments sidecar so a
     * cancelled or failed transfer resumes every segment where it stopped. Returns null (and
     * leaves no parallel state behind) when the server does not honour ranges or the file is small,
     * so the caller falls back to the single-connection path.
     */
    private fun downloadParallel(
        url: URL,
        target: File,
        partial: File,
        progress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)?,
        shouldContinue: () -> Boolean,
    ): File? {
        val stateFile = File(partial.path + SEGMENT_STATE_SUFFIX)
        val probe = runCatching { openFollowingRedirects(url, 0L, rangeEnd = 0L) }.getOrNull() ?: return null
        val finalUrl: URL
        val total: Long
        try {
            if (probe.status != HttpURLConnection.HTTP_PARTIAL) return null.also { clearParallelState(partial, stateFile) }
            val range = parseContentRange(probe.connection.getHeaderField("Content-Range"))
                ?: return null.also { clearParallelState(partial, stateFile) }
            if (range.start != 0L) return null.also { clearParallelState(partial, stateFile) }
            total = range.total
            finalUrl = probe.url
            probe.connection.inputStream.use { it.readBytes() }
        } finally {
            probe.connection.disconnect()
        }
        if (total < PARALLEL_MIN_BYTES) return null.also { clearParallelState(partial, stateFile) }
        check(total <= MAX_IMPORT_BYTES) { "LAZ file exceeds the 10 GB import limit" }

        val connections = parallelConnections.coerceIn(2, 16)
        val segments = parallelSegmentCount(total, connections)
        val starts = LongArray(segments) { total * it / segments }
        val ends = LongArray(segments) { total * (it + 1) / segments }
        val done = LongArray(segments)
        val resumed = readSegmentState(stateFile, total, segments)
        if (resumed != null && partial.isFile && partial.length() == total) {
            resumed.copyInto(done)
        } else {
            // A sequential .part (or stale state) cannot be mixed with segment offsets.
            partial.delete()
            java.io.RandomAccessFile(partial, "rw").use { it.setLength(total) }
        }
        val downloaded = java.util.concurrent.atomic.AtomicLong(done.sum())
        val stateLock = Any()
        fun persist() = synchronized(stateLock) {
            stateFile.writeText("$total $segments " + done.joinToString(" "))
        }
        persist()
        progress?.invoke(downloaded.get(), total)

        val pool = java.util.concurrent.Executors.newFixedThreadPool(connections) { task ->
            Thread(task, "laz-download").apply { isDaemon = true }
        }
        try {
            val futures = (0 until segments).map { index ->
                pool.submit(java.util.concurrent.Callable {
                    downloadSegment(finalUrl, partial, index, starts[index], ends[index], done, downloaded, total, stateLock, ::persist, progress, shouldContinue)
                })
            }
            var failure: Throwable? = null
            for (future in futures) {
                try {
                    future.get()
                } catch (error: java.util.concurrent.ExecutionException) {
                    if (failure == null) failure = error.cause ?: error
                }
            }
            persist()
            if (!shouldContinue()) throw CancellationException("LAZ download cancelled")
            failure?.let { cause ->
                if (cause is CancellationException) throw cause
                throw IOException(
                    (cause.localizedMessage ?: "LAZ download failed") +
                        " (${downloaded.get()} bytes saved; tap again to resume)",
                    cause,
                )
            }
        } finally {
            pool.shutdownNow()
        }
        validatePointCloudSignature(partial)
        promote(partial, target)
        stateFile.delete()
        progress?.invoke(total, total)
        return target
    }

    private fun downloadSegment(
        url: URL,
        partial: File,
        index: Int,
        start: Long,
        endExclusive: Long,
        done: LongArray,
        downloaded: java.util.concurrent.atomic.AtomicLong,
        total: Long,
        stateLock: Any,
        persist: () -> Unit,
        progress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)?,
        shouldContinue: () -> Boolean,
    ) {
        var lastFailure: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val offset = start + synchronized(stateLock) { done[index] }
            if (offset >= endExclusive) return
            if (!shouldContinue()) throw CancellationException("LAZ download cancelled")
            var connection: HttpURLConnection? = null
            try {
                val opened = openFollowingRedirects(url, offset, rangeEnd = endExclusive - 1)
                connection = opened.connection
                if (opened.status == 408 || opened.status == 429 || opened.status in 500..599) {
                    throw RetryableHttpException(opened.status, "Temporary download server error")
                }
                check(opened.status == HttpURLConnection.HTTP_PARTIAL) { "Server returned HTTP ${opened.status} for a range" }
                val range = parseContentRange(connection.getHeaderField("Content-Range"))
                    ?: throw IOException("Partial response had no valid Content-Range")
                check(range.start == offset && range.total == total) { "Server returned an unexpected range" }
                java.io.RandomAccessFile(partial, "rw").use { file ->
                    file.seek(offset)
                    val buffer = ByteArray(BUFFER_BYTES)
                    var position = offset
                    var sinceReport = 0L
                    connection.inputStream.use { input ->
                        while (position < endExclusive) {
                            if (!shouldContinue()) throw CancellationException("LAZ download cancelled")
                            val wanted = minOf(buffer.size.toLong(), endExclusive - position).toInt()
                            val count = input.read(buffer, 0, wanted)
                            if (count < 0) break
                            file.write(buffer, 0, count)
                            position += count
                            synchronized(stateLock) { done[index] = position - start }
                            val now = downloaded.addAndGet(count.toLong())
                            sinceReport += count
                            if (sinceReport >= PROGRESS_STEP_BYTES) {
                                sinceReport = 0L
                                persist()
                                progress?.invoke(now, total)
                            }
                        }
                    }
                    if (position < endExclusive) throw EOFException("Segment $index ended at $position of $endExclusive")
                }
                // Body fully drained and closed: leave the socket in the keep-alive pool so the
                // next segment skips the TCP/TLS handshake.
                connection = null
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
                if (attempt >= MAX_ATTEMPTS - 1 || !isRetryable(failure)) throw failure
                waitForRetry(attempt, shouldContinue)
            } finally {
                connection?.disconnect()
            }
        }
        throw lastFailure ?: IOException("Segment $index failed")
    }

    /** Deterministic from the size, so a resumed transfer reuses the same segment layout. */
    internal fun parallelSegmentCount(total: Long, connections: Int): Int =
        ((total + SEGMENT_BYTES - 1) / SEGMENT_BYTES).coerceIn(connections.toLong(), MAX_SEGMENTS.toLong()).toInt()

    private fun readSegmentState(stateFile: File, total: Long, segments: Int): LongArray? = runCatching {
        if (!stateFile.isFile) return null
        val parts = stateFile.readText().trim().split(' ').map(String::toLong)
        if (parts.size != segments + 2 || parts[0] != total || parts[1] != segments.toLong()) return null
        LongArray(segments) { parts[it + 2] }.takeIf { array ->
            array.withIndex().all { (i, v) -> v >= 0L && v <= total * (i + 1) / segments - total * i / segments }
        }
    }.getOrNull()

    private fun clearParallelState(partial: File, stateFile: File) {
        if (stateFile.isFile) {
            // A preallocated multipart .part is not a valid sequential prefix.
            stateFile.delete()
            partial.delete()
        }
    }

    private fun openFollowingRedirects(source: URL, resumeFrom: Long, rangeEnd: Long? = null): OpenedConnection {
        var currentUrl = source
        var redirects = 0
        while (true) {
            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("User-Agent", "Find-It-Android/1.0")
                setRequestProperty("Accept", "application/octet-stream,*/*")
                setRequestProperty("Accept-Encoding", "identity")
                if (rangeEnd != null) {
                    setRequestProperty("Range", "bytes=$resumeFrom-$rangeEnd")
                } else if (resumeFrom > 0L) {
                    setRequestProperty("Range", "bytes=$resumeFrom-")
                }
            }
            val status = connection.responseCode
            if (status !in 300..399) return OpenedConnection(currentUrl, connection, status)

            if (redirects++ >= MAX_REDIRECTS) {
                connection.disconnect()
                error("Too many redirects")
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            require(!location.isNullOrBlank()) { "Redirect had no destination" }
            currentUrl = validateRemoteUrl(URL(currentUrl, location))
        }
    }

    private fun waitForRetry(attempt: Int, shouldContinue: () -> Boolean) {
        val delayMillis = min(RETRY_MAX_DELAY_MS, RETRY_BASE_DELAY_MS shl attempt.coerceAtMost(3))
        var remaining = delayMillis
        while (remaining > 0L) {
            if (!shouldContinue()) throw CancellationException("LAZ download cancelled")
            val step = min(250L, remaining)
            Thread.sleep(step)
            remaining -= step
        }
    }

    private fun isRetryable(error: Throwable): Boolean = when (error) {
        is RetryableHttpException,
        is SocketTimeoutException,
        is SocketException,
        is EOFException,
        is IOException,
        -> true
        else -> false
    }

    /**
     * Rejects a completed transfer whose bytes are not a point cloud.
     *
     * Size checks alone pass a payload that is the wrong thing entirely — typically an HTML error
     * or maintenance page served with 200 and an honest Content-Length. Without this the file is
     * promoted, stored, and counted as a tile, and the mistake only surfaces much later when the
     * decoder rejects it, potentially after fetching the rest of a large mosaic around it.
     *
     * LAZ is LAS with compressed point data, so both carry the same signature.
     */
    internal fun validatePointCloudSignature(file: File) {
        val signature = ByteArray(LIDAR_SIGNATURE.size)
        val read = FileInputStream(file).use { it.read(signature) }
        if (read == LIDAR_SIGNATURE.size && signature.contentEquals(LIDAR_SIGNATURE)) return
        // Resuming a wrong payload would only ever append more of the wrong file, so drop it and
        // let a retry start the transfer cleanly.
        file.delete()
        error("Downloaded file is not a LAS/LAZ point cloud")
    }

    private fun promote(partial: File, destination: File) {
        if (!partial.renameTo(destination)) {
            partial.copyTo(destination, overwrite = false)
            partial.delete()
        }
    }

    private fun parseContentRange(header: String?): ContentRange? {
        if (header.isNullOrBlank()) return null
        val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
            .find(header.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start < 0L || end < start || total <= end) return null
        return ContentRange(start, end, total)
    }

    private fun parseUnsatisfiedRangeTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        return Regex("bytes\\s+\\*/(\\d+)", RegexOption.IGNORE_CASE)
            .find(header.trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
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

    private data class OpenedConnection(
        val url: URL,
        val connection: HttpURLConnection,
        val status: Int,
    )

    private data class ContentRange(
        val start: Long,
        val end: Long,
        val total: Long,
    )

    private class RetryableHttpException(status: Int, message: String) :
        IOException("$message (HTTP $status)")
}
