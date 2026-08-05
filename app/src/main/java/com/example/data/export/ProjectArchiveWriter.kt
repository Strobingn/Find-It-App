package com.example.data.export

/** One named file inside a portable project archive. */
data class ProjectArchiveFile(
    val path: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ProjectArchiveFile && path == other.path && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = path.hashCode() * 31 + bytes.contentHashCode()
}

data class ProjectArchiveManifest(
    val projectName: String,
    val createdAtMillis: Long,
    val filePaths: List<String>,
)

/**
 * Portable project archive: a deflated zip with a plain-text manifest so a project moves
 * between devices as one self-describing file. The manifest round-trips without unzipping
 * the payload first, and malformed archives are rejected instead of half-imported.
 */
object ProjectArchiveWriter {
    const val MANIFEST_PATH = "manifest.txt"
    private const val MANIFEST_HEADER = "FINDIT_PROJECT_ARCHIVE_V1"

    fun write(
        projectName: String,
        files: List<ProjectArchiveFile>,
        createdAtMillis: Long,
    ): ByteArray {
        require(files.none { it.path == MANIFEST_PATH }) { "manifest.txt is reserved" }
        val manifest = buildString {
            appendLine(MANIFEST_HEADER)
            appendLine("name\t${encode(projectName)}")
            appendLine("created\t$createdAtMillis")
            for (file in files) {
                appendLine("file\t${encode(file.path)}\t${file.bytes.size}")
            }
        }
        val entries = linkedMapOf(MANIFEST_PATH to manifest.toByteArray(Charsets.UTF_8))
        for (file in files) entries[file.path] = file.bytes
        return createZipArchive(entries)
    }

    fun readManifest(archiveBytes: ByteArray): ProjectArchiveManifest? {
        val entries = try {
            readZipArchive(archiveBytes)
        } catch (exception: Exception) {
            return null
        }
        val manifestText = entries[MANIFEST_PATH]?.toString(Charsets.UTF_8) ?: return null
        val lines = manifestText.lines().filter { it.isNotBlank() }
        if (lines.firstOrNull() != MANIFEST_HEADER) return null
        var name: String? = null
        var created: Long? = null
        val paths = ArrayList<String>()
        for (line in lines.drop(1)) {
            val parts = line.split('\t')
            when (parts.getOrNull(0)) {
                "name" -> name = parts.getOrNull(1)?.let { decode(it) }
                "created" -> created = parts.getOrNull(1)?.toLongOrNull()
                "file" -> parts.getOrNull(1)?.let { paths.add(decode(it)) }
            }
        }
        if (name == null || created == null) return null
        return ProjectArchiveManifest(projectName = name, createdAtMillis = created, filePaths = paths)
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decode(value: String): String =
        java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
}
