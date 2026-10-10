package com.example.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class LazDownloadManager(
    httpClient: OkHttpClient = LazHttp.shared,
    private val allowPrivateHosts: Boolean = false,
    private val maxParallelSegments: Int = DEFAULT_MAX_PARALLEL_SEGMENTS,
    private val minParallelBytes: Long = MIN_PARALLEL_BYTES,
    private val targetSegmentBytes: Long = TARGET_SEGMENT_BYTES,
) {
    private val http: OkHttpClient = httpClient.newBuilder()
        .addNetworkInterceptor { chain ->
            if (!allowPrivateHosts) {
                validateRemoteUrl(chain.request().url.toUrl())
            }
            val request = chain.request().newBuilder()
                .header("User-Agent", LazHttp.USER_AGENT)
                .header("Accept", "application/octet-stream,*/*")
                .header("Accept-Encoding", "identity")
                .build()
            chain.proceed(request)
        }
        .build()

    fun download(
        sourceUrl: String,
        destinationDirectory: File,
        progress: (LazTransferProgress) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
        onCopcDetected: () -> Unit = {},
    ): File {
        val source = URL(sourceUrl.trim())
        if (!allowPrivateHosts) validateRemoteUrl(source)
        destinationDirectory.mkdirs()
        val destination = File(destinationDirectory, uniqueFileName(source))
        if (destination.exists() && destination.length() > 0L) {
            validatePointCloudSignature(destination)
            progress(LazTransferProgress(destination.length(), destination.length()))
            if (LazDownloadMirrors.looksLikeCopcUrl(sourceUrl)) onCopcDetected()
            return destination
        }

        val chosenUrl = pickFastestMirror(sourceUrl)
        if (LazDownloadMirrors.looksLikeCopcUrl(chosenUrl) || LazDownloadMirrors.looksLikeCopcUrl(sourceUrl)) {
            onCopcDetected()
        }

        val partial = File(destinationDirectory, "${destination.name}.part")
        val probe = probe(chosenUrl)
        val resumeFrom = if (partial.exists()) partial.length() else 0L
        val file = when {
            // A leftover single-stream .part is cheaper to finish than to throw away for
            // parallel segments that would re-fetch those bytes.
            resumeFrom > 0L && (probe.totalBytes < 0L || resumeFrom < probe.totalBytes) ->
                downloadSingle(chosenUrl, destination, partial, resumeFrom, probe.totalBytes, progress, shouldContinue)
            probe.supportsRanges &&
                probe.totalBytes >= minParallelBytes &&
                maxParallelSegments > 1 ->
                downloadParallelOrSingle(chosenUrl, destination, partial, probe.totalBytes, progress, shouldContinue)
            else ->
                downloadSingle(chosenUrl, destination, partial, 0L, probe.totalBytes, progress, shouldContinue)
        }
        if (looksLikeCopcFile(file)) onCopcDetected()
        return file
    }

    private fun downloadParallelOrSingle(
        sourceUrl: String,
        destination: File,
        partial: File,
        totalBytes: Long,
        progress: (LazTransferProgress) -> Unit,
        shouldContinue: () -> Boolean,
    ): File = try {
        downloadParallel(sourceUrl, destination, partial, totalBytes, progress, shouldContinue)
    } catch (cancelled: DownloadCancelledException) {
        throw cancelled
    } catch (_: Exception) {
        val resumeFrom = if (partial.exists()) partial.length() else 0L
        downloadSingle(sourceUrl, destination, partial, resumeFrom, totalBytes, progress, shouldContinue)
    }

    fun validatePointCloudSignature(file: File) {
        val header = ByteArray(LAS_SIGNATURE.size)
        val read = file.inputStream().use { input -> input.read(header) }
        if (read < LAS_SIGNATURE.size || !header.contentEquals(LAS_SIGNATURE)) {
            file.delete()
            throw IllegalStateException(
                "Downloaded file is not a LAS/LAZ point cloud (missing LASF signature). " +
                    "The server likely returned an HTML error page.",
            )
        }
    }

    internal fun planSegments(totalBytes: Long, maxSegments: Int = maxParallelSegments): List<ByteRange> {
        require(totalBytes > 0L)
        val count = segmentCount(totalBytes, maxSegments)
        val size = totalBytes / count
        return (0 until count).map { index ->
            val start = index * size
            val endInclusive = if (index == count - 1) totalBytes - 1L else start + size - 1L
            ByteRange(index, start, endInclusive)
        }
    }

    internal fun pickFastestMirror(sourceUrl: String): String {
        val candidates = LazDownloadMirrors.candidates(sourceUrl)
        if (candidates.size == 1) return candidates.first()
        val probeClient = http.newBuilder()
            .callTimeout(MIRROR_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(MIRROR_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(MIRROR_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val executor = Executors.newFixedThreadPool(candidates.size)
        val completion = ExecutorCompletionService<String>(executor)
        try {
            candidates.forEach { candidate ->
                completion.submit {
                    val request = Request.Builder()
                        .url(candidate)
                        .header("Range", "bytes=0-0")
                        .build()
                    probeClient.newCall(request).execute().use { response ->
                        if (response.code == 200 || response.code == 206) candidate
                        else throw IOException("mirror probe HTTP ${response.code}")
                    }
                }
            }
            repeat(candidates.size) {
                val winner = runCatching { completion.take().get() }.getOrNull()
                if (winner != null) return winner
            }
        } catch (_: Exception) {
            // Fall through to the original listing URL.
        } finally {
            executor.shutdownNow()
        }
        return sourceUrl
    }

    private fun downloadParallel(
        sourceUrl: String,
        destination: File,
        partial: File,
        totalBytes: Long,
        progress: (LazTransferProgress) -> Unit,
        shouldContinue: () -> Boolean,
    ): File {
        if (totalBytes > MAX_IMPORT_BYTES) {
            throw IllegalStateException("Remote LAS/LAZ exceeds the ${MAX_IMPORT_BYTES / (1024 * 1024)} MB import limit")
        }
        val partsDir = File(destination.parentFile, "${destination.name}.parts")
        partsDir.mkdirs()
        val segments = planSegments(totalBytes)
        val alreadyHave = segments.sumOf { segmentFile(partsDir, it).length().coerceAtMost(it.size) }
        val downloaded = AtomicLong(alreadyHave)
        val ticker = ProgressTicker(progress)
        ticker.emit(downloaded.get(), totalBytes)
        val failed = AtomicBoolean(false)
        val calls = ConcurrentLinkedQueue<Call>()
        val workers = Executors.newFixedThreadPool(segments.size.coerceAtMost(maxParallelSegments))
        try {
            val futures = segments.map { segment ->
                workers.submit(Callable {
                    downloadSegment(
                        sourceUrl = sourceUrl,
                        partsDir = partsDir,
                        segment = segment,
                        downloaded = downloaded,
                        totalBytes = totalBytes,
                        ticker = ticker,
                        shouldContinue = shouldContinue,
                        failed = failed,
                        calls = calls,
                    )
                })
            }
            var firstError: Exception? = null
            futures.forEach { future ->
                try {
                    future.get()
                } catch (error: Exception) {
                    failed.set(true)
                    calls.forEach { it.cancel() }
                    if (error.cause is DownloadCancelledException || error is DownloadCancelledException) {
                        throw DownloadCancelledException()
                    }
                    if (firstError == null) firstError = unwrap(error)
                }
            }
            firstError?.let { throw it }
            assembleSegments(partsDir, segments, partial)
            return promote(partial, destination, totalBytes, progress)
        } finally {
            workers.shutdownNow()
            partsDir.deleteRecursively()
        }
    }

    private fun downloadSegment(
        sourceUrl: String,
        partsDir: File,
        segment: ByteRange,
        downloaded: AtomicLong,
        totalBytes: Long,
        ticker: ProgressTicker,
        shouldContinue: () -> Boolean,
        failed: AtomicBoolean,
        calls: ConcurrentLinkedQueue<Call>,
    ) {
        val file = segmentFile(partsDir, segment)
        val already = file.length().coerceAtMost(segment.size)
        if (already == segment.size) return
        val start = segment.start + already
        val request = Request.Builder()
            .url(sourceUrl)
            .header("Range", "bytes=$start-${segment.endInclusive}")
            .build()
        val call = http.newCall(request)
        calls.add(call)
        try {
            call.execute().use { response ->
                if (failed.get() || !shouldContinue()) throw DownloadCancelledException()
                when (response.code) {
                    206 -> Unit
                    200 -> throw IOException("Server ignored the Range header; falling back to a single stream")
                    else -> throw IOException("Segment HTTP ${response.code} for bytes=$start-${segment.endInclusive}")
                }
                val body = response.body ?: throw IOException("Empty segment body")
                FileOutputStream(file, already > 0L).use { output ->
                    copyStream(
                        input = body.byteStream(),
                        output = output,
                        expectedRemaining = segment.size - already,
                        downloaded = downloaded,
                        totalBytes = totalBytes,
                        ticker = ticker,
                        shouldContinue = { shouldContinue() && !failed.get() },
                    )
                }
            }
        } finally {
            calls.remove(call)
        }
        if (file.length() != segment.size) {
            throw IOException("Segment ${segment.index} ended at ${file.length()} bytes, expected ${segment.size}")
        }
    }

    private fun downloadSingle(
        sourceUrl: String,
        destination: File,
        partial: File,
        resumeFrom: Long,
        knownTotal: Long,
        progress: (LazTransferProgress) -> Unit,
        shouldContinue: () -> Boolean,
    ): File {
        if (resumeFrom > MAX_IMPORT_BYTES) {
            throw IllegalStateException("Remote LAS/LAZ exceeds the ${MAX_IMPORT_BYTES / (1024 * 1024)} MB import limit")
        }
        val requestBuilder = Request.Builder().url(sourceUrl)
        if (resumeFrom > 0L) requestBuilder.header("Range", "bytes=$resumeFrom-")
        http.newCall(requestBuilder.build()).execute().use { response ->
            if (!shouldContinue()) throw DownloadCancelledException()
            val acceptedResume = resumeFrom > 0L && response.code == 206
            if (resumeFrom > 0L && !acceptedResume && response.code != 200) {
                throw IOException("Resume failed with HTTP ${response.code}")
            }
            if (response.code !in 200..206) {
                throw IOException("Download failed with HTTP ${response.code}")
            }
            if (resumeFrom > 0L && response.code == 200) {
                partial.delete()
            }
            val body = response.body ?: throw IOException("Empty download body")
            val total = resolveTotal(response, if (acceptedResume) resumeFrom else 0L, knownTotal)
            if (total > MAX_IMPORT_BYTES) {
                throw IllegalStateException("Remote LAS/LAZ exceeds the ${MAX_IMPORT_BYTES / (1024 * 1024)} MB import limit")
            }
            val downloaded = AtomicLong(if (acceptedResume) resumeFrom else 0L)
            val ticker = ProgressTicker(progress)
            ticker.emit(downloaded.get(), total)
            FileOutputStream(partial, acceptedResume).use { output ->
                copyStream(
                    input = body.byteStream(),
                    output = output,
                    expectedRemaining = if (total > 0L) total - downloaded.get() else -1L,
                    downloaded = downloaded,
                    totalBytes = total,
                    ticker = ticker,
                    shouldContinue = shouldContinue,
                )
            }
            return promote(partial, destination, total, progress)
        }
    }

    private fun copyStream(
        input: InputStream,
        output: FileOutputStream,
        expectedRemaining: Long,
        downloaded: AtomicLong,
        totalBytes: Long,
        ticker: ProgressTicker,
        shouldContinue: () -> Boolean,
    ) {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var remaining = expectedRemaining
        while (true) {
            if (!shouldContinue()) throw DownloadCancelledException()
            val limit = when {
                remaining < 0L -> buffer.size
                remaining == 0L -> break
                else -> minOf(buffer.size.toLong(), remaining).toInt()
            }
            val read = input.read(buffer, 0, limit)
            if (read < 0) break
            output.write(buffer, 0, read)
            val now = downloaded.addAndGet(read.toLong())
            if (now > MAX_IMPORT_BYTES) {
                throw IllegalStateException("Remote LAS/LAZ exceeds the ${MAX_IMPORT_BYTES / (1024 * 1024)} MB import limit")
            }
            if (remaining > 0L) remaining -= read.toLong()
            ticker.maybeEmit(now, totalBytes)
        }
        output.fd.sync()
        ticker.emit(downloaded.get(), totalBytes)
    }

    private fun assembleSegments(partsDir: File, segments: List<ByteRange>, partial: File) {
        FileOutputStream(partial).use { output ->
            val channel = output.channel
            var position = 0L
            segments.forEach { segment ->
                val file = segmentFile(partsDir, segment)
                file.inputStream().channel.use { input ->
                    var remaining = file.length()
                    var offset = 0L
                    while (remaining > 0L) {
                        val copied = channel.transferFrom(input, position + offset, remaining)
                        if (copied <= 0L) throw IOException("Failed to assemble segment ${segment.index}")
                        remaining -= copied
                        offset += copied
                    }
                }
                position += file.length()
            }
            output.fd.sync()
        }
    }

    private fun promote(
        partial: File,
        destination: File,
        expectedTotal: Long,
        progress: (LazTransferProgress) -> Unit,
    ): File {
        if (expectedTotal > 0L && partial.length() != expectedTotal) {
            throw IOException("Download ended at ${partial.length()} bytes, expected $expectedTotal")
        }
        validatePointCloudSignature(partial)
        if (destination.exists()) destination.delete()
        if (!partial.renameTo(destination)) {
            partial.copyTo(destination, overwrite = true)
            partial.delete()
        }
        progress(LazTransferProgress(destination.length(), destination.length()))
        return destination
    }

    private fun probe(sourceUrl: String): Probe {
        val head = Request.Builder().url(sourceUrl).head().build()
        runCatching {
            http.newCall(head).execute().use { response ->
                if (response.isSuccessful) {
                    val total = contentLengthOf(response)
                    val ranges = acceptsRanges(response) && total >= minParallelBytes
                    if (total > 0L) return Probe(total, ranges)
                }
            }
        }
        val range = Request.Builder().url(sourceUrl).header("Range", "bytes=0-0").build()
        return http.newCall(range).execute().use { response ->
            when (response.code) {
                206 -> Probe(contentRangeTotal(response) ?: contentLengthOf(response), true)
                200 -> Probe(contentLengthOf(response), false)
                else -> Probe(-1L, false)
            }
        }
    }

    private fun resolveTotal(response: Response, resumeFrom: Long, knownTotal: Long): Long {
        contentRangeTotal(response)?.let { return it }
        val length = contentLengthOf(response)
        return when {
            knownTotal > 0L -> knownTotal
            length > 0L && resumeFrom > 0L -> resumeFrom + length
            length > 0L -> length
            else -> -1L
        }
    }

    private fun contentLengthOf(response: Response): Long {
        val header = response.header("Content-Length")?.toLongOrNull() ?: -1L
        val body = response.body?.contentLength() ?: -1L
        return maxOf(header, body)
    }

    private fun contentRangeTotal(response: Response): Long? {
        val header = response.header("Content-Range") ?: return null
        val total = header.substringAfter('/', missingDelimiterValue = "").trim()
        return total.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun acceptsRanges(response: Response): Boolean {
        val accept = response.header("Accept-Ranges").orEmpty()
        return accept.contains("bytes", ignoreCase = true) || response.code == 206
    }

    private fun uniqueFileName(source: URL): String {
        val raw = source.path.substringAfterLast('/').ifBlank { "remote-lidar.laz" }
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val lower = cleaned.lowercase(Locale.US)
        return when {
            lower.endsWith(".laz") || lower.endsWith(".las") -> cleaned
            else -> "$cleaned.laz"
        }
    }

    private fun validateRemoteUrl(source: URL) {
        require(source.protocol.equals("https", ignoreCase = true)) { "Only HTTPS LiDAR URLs are allowed" }
        val host = source.host?.trim().orEmpty()
        require(host.isNotBlank()) { "LiDAR URL is missing a host" }
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty()) { "LiDAR host did not resolve" }
        require(addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress }) {
            "LiDAR URL must not target a private or local address"
        }
    }

    private fun looksLikeCopcFile(file: File): Boolean {
        if (file.name.contains(".copc.", ignoreCase = true)) return true
        if (file.length() < COPC_VLR_OFFSET + COPC_USER_ID.size) return false
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(COPC_VLR_OFFSET)
            val userId = ByteArray(COPC_USER_ID.size)
            raf.readFully(userId)
            userId.contentEquals(COPC_USER_ID)
        }
    }

    private fun unwrap(error: Exception): Exception {
        var current: Throwable = error
        while (current.cause is Exception) {
            current = current.cause as Exception
            if (current is DownloadCancelledException) return current
        }
        return (current as? Exception) ?: error
    }

    private fun segmentFile(partsDir: File, segment: ByteRange): File =
        File(partsDir, "seg-${segment.index.toString().padStart(2, '0')}")

    private fun segmentCount(totalBytes: Long, maxSegments: Int): Int {
        if (totalBytes < minParallelBytes || maxSegments <= 1) return 1
        val bySize = (totalBytes / targetSegmentBytes).toInt().coerceAtLeast(2)
        return bySize.coerceAtMost(maxSegments)
    }

    private class ProgressTicker(
        private val progress: (LazTransferProgress) -> Unit,
    ) {
        private val speed = LazSpeedWindow()
        private val lastEmitMs = AtomicLong(0L)
        private val lastEmitBytes = AtomicLong(0L)

        fun maybeEmit(downloaded: Long, total: Long) {
            val now = System.currentTimeMillis()
            val previousBytes = lastEmitBytes.get()
            val previousMs = lastEmitMs.get()
            if (downloaded - previousBytes < PROGRESS_BYTE_INTERVAL && now - previousMs < PROGRESS_TIME_INTERVAL_MS) {
                return
            }
            emit(downloaded, total, now)
        }

        fun emit(downloaded: Long, total: Long, now: Long = System.currentTimeMillis()) {
            lastEmitBytes.set(downloaded)
            lastEmitMs.set(now)
            progress(speed.update(downloaded, total, now))
        }
    }

    data class ByteRange(val index: Int, val start: Long, val endInclusive: Long) {
        val size: Long get() = endInclusive - start + 1L
    }

    private data class Probe(val totalBytes: Long, val supportsRanges: Boolean)

    private class DownloadCancelledException : IOException("Download cancelled")

    companion object {
        const val MAX_IMPORT_BYTES = 4L * 1024L * 1024L * 1024L
        const val DEFAULT_MAX_PARALLEL_SEGMENTS = 6
        internal const val MIN_PARALLEL_BYTES = 4L * 1024L * 1024L
        internal const val TARGET_SEGMENT_BYTES = 8L * 1024L * 1024L
        private const val STREAM_BUFFER_BYTES = 2 * 1024 * 1024
        private const val PROGRESS_BYTE_INTERVAL = 256L * 1024L
        private const val PROGRESS_TIME_INTERVAL_MS = 200L
        private const val MIRROR_PROBE_TIMEOUT_SECONDS = 4L
        private const val COPC_VLR_OFFSET = 375L
        private val LAS_SIGNATURE = byteArrayOf(0x4C, 0x41, 0x53, 0x46)
        private val COPC_USER_ID = byteArrayOf(
            0x63, 0x6F, 0x70, 0x63, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
    }
}
