package com.example.data.export

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Lightweight inspect / safe extract of a portable project / site-package archive.
 * Does not import or overwrite Room project data — callers confirm before any merge.
 */
object ProjectArchiveImport {
    data class Result(
        val ok: Boolean,
        val message: String,
        val manifestName: String?,
        val fileCount: Int = 0,
        /** Absolute path of the extract directory when [applyIfSafe] wrote files. */
        val extractDirPath: String? = null,
    )

    /**
     * Validate archive bytes via [ProjectArchiveWriter.readManifest].
     * Returns a non-ok [Result] for malformed input instead of throwing.
     */
    fun inspect(bytes: ByteArray): Result {
        if (bytes.isEmpty()) {
            return Result(ok = false, message = "Empty file", manifestName = null)
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
     * Zip-slip paths (absolute / `..`) are rejected.
     */
    fun applyIfSafe(bytes: ByteArray, destRoot: File): Result {
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
            if (rawPath.isBlank() || rawPath.contains("..") || rawPath.startsWith("/")) {
                skipped++
                continue
            }
            val normalized = rawPath.replace('\\', '/').trimStart('/')
            if (normalized.isBlank() || normalized.contains("..")) {
                skipped++
                continue
            }
            val out = File(destDir, normalized)
            val outCanonical = try {
                out.canonicalFile
            } catch (_: Exception) {
                skipped++
                continue
            }
            if (!outCanonical.path.startsWith(destCanonical.path + File.separator) &&
                outCanonical != destCanonical
            ) {
                skipped++
                continue
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
