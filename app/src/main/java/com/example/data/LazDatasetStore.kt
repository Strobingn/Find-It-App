package com.example.data

import java.io.File
import java.util.Locale

data class LazDataset(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)

/** Persistent app-private storage for downloaded and copied LAZ/LAS datasets. */
class LazDatasetStore(
    val directory: File,
) {
    init {
        directory.mkdirs()
    }

    fun list(): List<LazDataset> = directory.listFiles()
        ?.asSequence()
        ?.filter { it.isFile && it.extension.lowercase(Locale.US) in setOf("laz", "las") }
        ?.map {
            LazDataset(
                file = it,
                displayName = it.name,
                sizeBytes = it.length(),
                modifiedAtMillis = it.lastModified(),
            )
        }
        ?.sortedByDescending { it.modifiedAtMillis }
        ?.toList()
        ?: emptyList()

    fun destinationFor(requestedName: String): File {
        directory.mkdirs()
        val raw = requestedName.substringAfterLast('/').substringBefore('?')
        val safe = raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "lidar-dataset.laz" }
        val extension = safe.substringAfterLast('.', "").lowercase(Locale.US)
        require(extension in setOf("laz", "las")) { "Dataset must be a LAZ or LAS file" }
        val first = File(directory, safe)
        if (!first.exists()) return first

        val base = safe.removeSuffix(".$extension")
        var index = 2
        while (true) {
            val candidate = File(directory, "$base-$index.$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    fun delete(dataset: LazDataset): Boolean {
        return contains(dataset.file) && dataset.file.delete()
    }

    fun contains(file: File): Boolean {
        return runCatching {
            file.canonicalFile.parentFile == directory.canonicalFile && file.exists()
        }.getOrDefault(false)
    }
}
