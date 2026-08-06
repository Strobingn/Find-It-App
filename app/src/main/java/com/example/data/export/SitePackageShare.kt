package com.example.data.export

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Lightweight inspect / safe extract of a portable project / site-package archive.
 * Does not import or overwrite Room project data — callers confirm before any merge.
 */
object ProjectArchiveImport {
    /** Soft cap on compressed archive size (and uncompressed estimate) for import/inspect. */
    const val MAX_ARCHIVE_BYTES: Long = 80L * 1024L * 1024L // 80 MiB

    data class Result(
        val ok: Boolean,
        val message: String,
        val manifestName: String?,
        val fileCount: Int = 0,
        /** Absolute path of the extract directory when [applyIfSafe] wrote files. */
        val extractDirPath: String? = null,
    )

    /**
     * True when [declaredLength] is a known positive size that exceeds [MAX_ARCHIVE_BYTES].
     * Content resolvers often report -1 when length is unknown — those pass this check.
     */
    fun exceedsSizeCap(declaredLength: Long): Boolean =
        declaredLength > 0L && declaredLength > MAX_ARCHIVE_BYTES

    /**
     * Read stream into a [ByteArray] only if length (when known) and bytes actually read stay
     * within [MAX_ARCHIVE_BYTES]. Soft-fails with a non-ok [Result] message via exception-free return.
     */
    fun readBytesCapped(stream: InputStream, knownLength: Long = -1L): ResultBytes {
        if (exceedsSizeCap(knownLength)) {
            return ResultBytes(
                bytes = null,
                error = "Archive too large (${formatMb(knownLength)}; max ${formatMb(MAX_ARCHIVE_BYTES)})",
            )
        }
        val available = runCatching { stream.available().toLong() }.getOrDefault(-1L)
        if (exceedsSizeCap(available)) {
            return ResultBytes(
                bytes = null,
                error = "Archive too large (${formatMb(available)}; max ${formatMb(MAX_ARCHIVE_BYTES)})",
            )
        }
        val limit = MAX_ARCHIVE_BYTES.toInt()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val out = java.io.ByteArrayOutputStream(minOf(if (knownLength > 0) knownLength.toInt() else 64 * 1024, limit))
        var total = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_ARCHIVE_BYTES) {
                return ResultBytes(
                    bytes = null,
                    error = "Archive too large (exceeds ${formatMb(MAX_ARCHIVE_BYTES)})",
                )
            }
            out.write(buffer, 0, n)
        }
        return ResultBytes(bytes = out.toByteArray(), error = null)
    }

    data class ResultBytes(val bytes: ByteArray?, val error: String?)

    /**
     * Validate archive bytes via [ProjectArchiveWriter.readManifest].
     * Returns a non-ok [Result] for malformed input instead of throwing.
     */
    fun inspect(bytes: ByteArray): Result {
        if (bytes.isEmpty()) {
            return Result(ok = false, message = "Empty file", manifestName = null)
        }
        if (bytes.size.toLong() > MAX_ARCHIVE_BYTES) {
            return Result(
                ok = false,
                message = "Archive too large (${formatMb(bytes.size.toLong())}; max ${formatMb(MAX_ARCHIVE_BYTES)})",
                manifestName = null,
            )
        }
        val manifest = ProjectArchiveWriter.readManifest(bytes)
            ?: return Result(
                ok = false,
                message = "Not a Find It project archive (missing or invalid manifest)",
                manifestName = null,
            )
        return Result(
            ok = true,
            message = "Archive OK: ${manifest.projectName} · ${manifest.filePaths.size} file(s)",
            manifestName = manifest.projectName,
            fileCount = manifest.filePaths.size,
        )
    }

    /**
     * Extract archive payload files into [destRoot]/findit-imports/{safeName}/ without
     * overwriting existing files and without writing into Room. Skips [ProjectArchiveWriter.MANIFEST_PATH].
     * Zip-slip paths (absolute / `..`) are rejected (archive fails closed).
     */
    fun applyIfSafe(bytes: ByteArray, destRoot: File): Result {
        if (bytes.size.toLong() > MAX_ARCHIVE_BYTES) {
            return Result(
                ok = false,
                message = "Archive too large (${formatMb(bytes.size.toLong())}; max ${formatMb(MAX_ARCHIVE_BYTES)})",
                manifestName = null,
            )
        }
        val uncompressedEstimate = estimateUncompressedBytes(bytes)
        if (uncompressedEstimate > MAX_ARCHIVE_BYTES) {
            return Result(
                ok = false,
                message = "Archive uncompressed size too large (${formatMb(uncompressedEstimate)}; max ${formatMb(MAX_ARCHIVE_BYTES)})",
                manifestName = null,
            )
        }
        val inspected = inspect(bytes)
        if (!inspected.ok) return inspected
        val manifest = ProjectArchiveWriter.readManifest(bytes)
            ?: return Result(ok = false, message = "Manifest unreadable", manifestName = null)
        val entries = try {
            readZipArchive(bytes)
        } catch (error: Exception) {
            return Result(
                ok = false,
                message = "Could not read zip: ${error.localizedMessage ?: "error"}",
                manifestName = manifest.projectName,
            )
        }
        val payloadBytes = entries.entries
            .filter { it.key != ProjectArchiveWriter.MANIFEST_PATH }
            .sumOf { it.value.size.toLong() }
        if (payloadBytes > MAX_ARCHIVE_BYTES) {
            return Result(
                ok = false,
                message = "Archive payload too large (${formatMb(payloadBytes)}; max ${formatMb(MAX_ARCHIVE_BYTES)})",
                manifestName = manifest.projectName,
            )
        }
        val safeName = manifest.projectName
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(48)
            .ifBlank { "import" }
        val destDir = File(destRoot, "findit-imports${File.separator}$safeName")
        if (!destDir.exists() && !destDir.mkdirs()) {
            return Result(
                ok = false,
                message = "Could not create import folder",
                manifestName = manifest.projectName,
            )
        }
        val destCanonical = destDir.canonicalFile
        var written = 0
        var skipped = 0
        for ((rawPath, fileBytes) in entries) {
            if (rawPath == ProjectArchiveWriter.MANIFEST_PATH) continue
            if (isUnsafeZipPath(rawPath)) {
                return Result(
                    ok = false,
                    message = "Archive rejected: unsafe path ($rawPath)",
                    manifestName = manifest.projectName,
                )
            }
            val normalized = rawPath.replace('\\', '/').trimStart('/')
            if (normalized.isBlank() || isUnsafeZipPath(normalized)) {
                return Result(
                    ok = false,
                    message = "Archive rejected: unsafe path ($rawPath)",
                    manifestName = manifest.projectName,
                )
            }
            val out = File(destDir, normalized)
            val outCanonical = try {
                out.canonicalFile
            } catch (_: Exception) {
                return Result(
                    ok = false,
                    message = "Archive rejected: unsafe path ($rawPath)",
                    manifestName = manifest.projectName,
                )
            }
            if (!outCanonical.path.startsWith(destCanonical.path + File.separator) &&
                outCanonical != destCanonical
            ) {
                return Result(
                    ok = false,
                    message = "Archive rejected: zip-slip path ($rawPath)",
                    manifestName = manifest.projectName,
                )
            }
            if (out.exists()) {
                skipped++
                continue
            }
            out.parentFile?.mkdirs()
            out.writeBytes(fileBytes)
            written++
        }
        return Result(
            ok = true,
            message = "Extracted $written file(s) to ${destDir.absolutePath}" +
                if (skipped > 0) " · skipped $skipped" else "",
            manifestName = manifest.projectName,
            fileCount = written,
            extractDirPath = destDir.absolutePath,
        )
    }

    /** True for blank, absolute, or parent-traversal (`..`) zip entry paths. */
    fun isUnsafeZipPath(rawPath: String): Boolean {
        if (rawPath.isBlank()) return true
        if (rawPath.startsWith("/") || rawPath.startsWith("\\")) return true
        // Drive-letter / UNC style
        if (rawPath.length >= 2 && rawPath[1] == ':') return true
        val normalized = rawPath.replace('\\', '/')
        if (normalized.startsWith("../") || normalized.contains("/../") || normalized.endsWith("/..") ||
            normalized == ".." || normalized.startsWith("..")
        ) {
            return true
        }
        return normalized.split('/').any { it == ".." }
    }

    private fun estimateUncompressedBytes(bytes: ByteArray): Long {
        return try {
            var total = 0L
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val size = entry.size
                        total += if (size >= 0L) size else 0L
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            total
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatMb(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }
}

/**
 * Short QR / text payloads for project handoff. Full zip bytes are never embedded —
 * large archives always emit [SHARE_FILE] so the user shares the file via the system sheet.
 */
object QrSharePayload {
    const val HEADER = "FINDIT_SHARE_V1"
    const val SHARE_FILE = "SHARE_FILE"
    const val INLINE_META = "INLINE_META"

    /**
     * Practical QR payload ceiling (characters). Above this, payloads always use SHARE_FILE
     * and never try to carry archive content.
     */
    const val MAX_INLINE_ARCHIVE_BYTES = 2_500

    /**
     * Build a short, scannable text payload describing a project archive.
     * When [archiveByteSize] exceeds [MAX_INLINE_ARCHIVE_BYTES], the body is [SHARE_FILE]
     * (hash + size only — not the zip).
     */
    fun forProject(
        projectName: String,
        archiveByteSize: Int,
        contentHash: String,
    ): String {
        val mode = if (archiveByteSize > MAX_INLINE_ARCHIVE_BYTES) SHARE_FILE else INLINE_META
        return buildString {
            appendLine(HEADER)
            appendLine(mode)
            appendLine("name=${sanitize(projectName)}")
            appendLine("bytes=$archiveByteSize")
            appendLine("sha256=${contentHash.lowercase(Locale.US)}")
        }.trimEnd()
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    private fun sanitize(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().ifBlank { "(unnamed)" }
}
