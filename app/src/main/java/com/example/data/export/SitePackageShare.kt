package com.example.data.export

import java.security.MessageDigest
import java.util.Locale

/**
 * Lightweight inspect of a portable project / site-package archive.
 * Does not import or overwrite project data — callers confirm before any restore.
 */
object ProjectArchiveImport {
    data class Result(
        val ok: Boolean,
        val message: String,
        val manifestName: String?,
        val fileCount: Int = 0,
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
