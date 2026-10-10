package com.example.update

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Uninstall-survival backup: Room DB, SharedPreferences, historic-map index, and LAZ library
 * references (paths + tiny index files — not multi-gigabyte LAS copies).
 */
object AppDataBackup {
    const val MANIFEST = "FINDIT_APP_BACKUP_V1"
    const val REFS_NAME = "imported-refs.txt"

    private val CREDENTIAL_PREF_NAMES = setOf(
        "openai_credentials.xml",
        "gemini_credentials.xml",
    )

    fun exportZip(
        databaseDir: File,
        sharedPrefsDir: File,
        historicIndex: File?,
        lidarDir: File?,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.txt"))
            zip.write(MANIFEST.toByteArray())
            zip.closeEntry()
            snapshotDatabases(databaseDir).forEach { putFile(zip, it, "databases/${it.name}") }
            copyDir(zip, sharedPrefsDir, "shared_prefs") { file ->
                file.name !in CREDENTIAL_PREF_NAMES && !looksLikeCredentialPref(file.name)
            }
            if (historicIndex != null && historicIndex.isFile) {
                putFile(zip, historicIndex, "historic-map-archive/${historicIndex.name}")
            }
            val copiedLidar = HashSet<String>()
            lidarDir?.listFiles()
                ?.filter { it.isFile && (it.extension.lowercase() in setOf("json", "txt", "idx") || it.name == ".sources.json") }
                ?.forEach {
                    putFile(zip, it, "lidar/${it.name}")
                    copiedLidar += it.name
                }
            val sourceIndex = lidarDir?.let { File(it, ".sources.json") }
            if (sourceIndex != null && sourceIndex.isFile && sourceIndex.name !in copiedLidar) {
                putFile(zip, sourceIndex, "lidar/.sources.json")
            }
            val refs = lidarDir?.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in setOf("laz", "las") }
                ?.sortedBy { it.name }
                ?.joinToString("\n") { "${it.name}\t${it.length()}" }
                .orEmpty()
            if (refs.isNotBlank()) {
                zip.putNextEntry(ZipEntry("lidar/$REFS_NAME"))
                zip.write(refs.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun restoreZip(
        bytes: ByteArray,
        databaseDir: File,
        sharedPrefsDir: File,
        historicDir: File,
        lidarDir: File,
        beforeReplace: () -> Unit = {},
    ): Int {
        beforeReplace()
        var count = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (entry.name == "manifest.txt") {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    require(text.startsWith(MANIFEST)) { "Not a Find It app backup" }
                    continue
                }
                val target = when {
                    entry.name.startsWith("databases/") ->
                        safeChild(databaseDir, entry.name.removePrefix("databases/"))
                    entry.name.startsWith("shared_prefs/") ->
                        safeChild(sharedPrefsDir, entry.name.removePrefix("shared_prefs/"))
                    entry.name.startsWith("historic-map-archive/") ->
                        safeChild(historicDir, entry.name.removePrefix("historic-map-archive/"))
                    entry.name.startsWith("lidar/") ->
                        safeChild(lidarDir, entry.name.removePrefix("lidar/"))
                    else -> null
                } ?: continue
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
                count += 1
            }
        }
        return count
    }

    fun safeChild(root: File, relative: String): File {
        require(relative.isNotBlank()) { "Empty backup path" }
        require(!relative.startsWith("/")) { "Absolute backup path rejected" }
        val normalized = relative.replace('\\', '/').trimStart('/')
        require(!normalized.split('/').any { it == ".." || it.isEmpty() }) {
            "Backup entry escapes destination: $relative"
        }
        val target = File(root, normalized).canonicalFile
        val rootCanon = root.canonicalFile
        val prefix = rootCanon.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
        require(target.path == rootCanon.path || target.path.startsWith(prefix)) {
            "Backup entry escapes destination: $relative"
        }
        return target
    }

    /**
     * Checkpoint WAL then copy only the main `.db` files so the snapshot is one generation.
     * Non-SQLite files (unit-test fixtures) are copied as-is.
     */
    internal fun snapshotDatabases(databaseDir: File): List<File> {
        if (!databaseDir.isDirectory) return emptyList()
        return databaseDir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith("-wal") && !it.name.endsWith("-shm") }
            .mapNotNull { source ->
                if (!source.isFile) return@mapNotNull null
                if (looksLikeSqlite(source)) checkpointSqlite(source)
                if (source.isFile) source else null
            }
    }

    private fun looksLikeSqlite(file: File): Boolean {
        if (!file.isFile || file.length() < 16L) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(16)
                if (input.read(header) < 16) return false
                header.decodeToString().startsWith("SQLite format 3")
            }
        }.getOrDefault(false)
    }

    private fun checkpointSqlite(dbFile: File) {
        runCatching {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            )
            db.use {
                it.rawQuery("PRAGMA wal_checkpoint(FULL)", null)?.close()
            }
        }
    }

    private fun looksLikeCredentialPref(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("credential") || lower.contains("api_key") || lower.contains("apitoken")
    }

    private fun copyDir(zip: ZipOutputStream, dir: File, prefix: String, include: (File) -> Boolean = { true }) {
        if (!dir.isDirectory) return
        dir.listFiles()?.filter { it.isFile && include(it) }?.forEach { putFile(zip, it, "$prefix/${it.name}") }
    }

    private fun putFile(zip: ZipOutputStream, file: File, path: String) {
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
