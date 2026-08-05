package com.example.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.Locale
import org.json.JSONObject

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

    /**
     * Remembers which URL a stored file came from.
     *
     * Reuse used to be decided by filename alone. Tile names are only unique within a survey, so
     * once the picker covered more than one project a tile could be "already downloaded" because an
     * unrelated survey happened to ship a file of the same name — silently substituting the wrong
     * ground. The index records the actual provenance.
     */
    @Synchronized
    fun recordSource(sourceUrl: String, file: File) {
        if (sourceUrl.isBlank() || !contains(file)) return
        writeIndex(readIndex().toMutableMap().apply { put(sourceUrl, file.name) })
    }

    /** The stored file previously downloaded from [sourceUrl], if it is still on disk. */
    @Synchronized
    fun fileForSource(sourceUrl: String): File? {
        val name = readIndex()[sourceUrl] ?: return null
        return File(directory, name).takeIf(File::isFile)
    }

    private fun readIndex(): Map<String, String> {
        val raw = indexFile.takeIf(File::isFile)?.let { runCatching(it::readText).getOrNull() }
            ?: return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            json.keys().forEach { key ->
                val name = json.optString(key)
                if (name.isNotBlank()) put(key, name)
            }
        }
    }

    private fun writeIndex(index: Map<String, String>) {
        val json = JSONObject()
        index.forEach { (url, name) -> json.put(url, name) }
        runCatching { indexFile.writeText(json.toString()) }
    }

    private val indexFile: File get() = File(directory, SOURCE_INDEX_NAME)

    fun delete(dataset: LazDataset): Boolean {
        if (!contains(dataset.file) || !dataset.file.delete()) return false
        val deletedName = dataset.file.name
        writeIndex(readIndex().filterValues { it != deletedName })
        return true
    }

    /** Renames a saved dataset in-place while preserving every URL that points at it. */
    @Synchronized
    fun rename(dataset: LazDataset, requestedName: String): LazDataset {
        require(contains(dataset.file)) { "Dataset is not in the saved LiDAR folder" }
        val targetName = normalizedDatasetName(requestedName, dataset.file.extension)
        val target = File(directory, targetName)
        if (target.canonicalFile == dataset.file.canonicalFile) return list().first { it.file.name == targetName }
        require(!target.exists()) { "A saved dataset named $targetName already exists" }
        if (!dataset.file.renameTo(target)) {
            throw IOException("Could not rename the saved dataset")
        }
        val oldName = dataset.file.name
        writeIndex(readIndex().mapValues { (_, name) -> if (name == oldName) target.name else name })
        return list().first { it.file.name == target.name }
    }

    fun contains(file: File): Boolean {
        return runCatching {
            file.canonicalFile.parentFile == directory.canonicalFile && file.exists()
        }.getOrDefault(false)
    }

    private fun normalizedDatasetName(requestedName: String, fallbackExtension: String): String {
        val raw = requestedName.substringAfterLast('/').substringBefore('?').trim()
        require(raw.isNotBlank()) { "Enter a name for the saved dataset" }
        val withExtension = if (raw.substringAfterLast('.', "").isBlank()) {
            "$raw.$fallbackExtension"
        } else {
            raw
        }
        val safe = withExtension
            .replace(Regex("[^a-zA-Z0-9._ -]"), "_")
            .trim('.', ' ')
        val extension = safe.substringAfterLast('.', "").lowercase(Locale.US)
        require(extension in setOf("laz", "las")) { "Saved datasets must keep a .laz or .las extension" }
        require(safe.isNotBlank()) { "Enter a valid saved dataset name" }
        return safe
    }

    companion object {
        private const val FOLDER_NAME = "lidar"

        /** Dot-prefixed and not a .laz/.las file, so [list] never surfaces it as a dataset. */
        private const val SOURCE_INDEX_NAME = ".sources.json"

        /**
         * Canonical durable store under [Context.getFilesDir] (not cache, not media that can
         * unmount). Migrates any older files from the external-files `lidar` folder once.
         */
        fun open(context: Context): LazDatasetStore {
            val app = context.applicationContext
            val primary = File(app.filesDir, FOLDER_NAME)
            primary.mkdirs()
            val legacyExternal = app.getExternalFilesDir(null)?.let { File(it, FOLDER_NAME) }
            if (legacyExternal != null &&
                legacyExternal.isDirectory &&
                runCatching { legacyExternal.canonicalPath != primary.canonicalPath }.getOrDefault(true)
            ) {
                migrateLegacyFolder(legacyExternal, primary)
            }
            return LazDatasetStore(primary)
        }

        /** One-way copy of LAZ/LAS and the source-URL index into the durable folder. */
        private fun migrateLegacyFolder(from: File, to: File) {
            to.mkdirs()
            from.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val dest = File(to, file.name)
                if (dest.exists()) return@forEach
                runCatching {
                    file.copyTo(dest, overwrite = false)
                }
            }
        }
    }
}
