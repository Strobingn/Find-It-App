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
            copyDir(zip, databaseDir, "databases")
            copyDir(zip, sharedPrefsDir, "shared_prefs")
            if (historicIndex != null && historicIndex.isFile) {
                putFile(zip, historicIndex, "historic-map-archive/${historicIndex.name}")
            }
            lidarDir?.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in setOf("json", "txt", "idx") }
                ?.forEach { putFile(zip, it, "lidar/${it.name}") }
        }
        return out.toByteArray()
    }

    fun restoreZip(
        bytes: ByteArray,
        databaseDir: File,
        sharedPrefsDir: File,
        historicDir: File,
        lidarDir: File,
    ): Int {
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
                    entry.name.startsWith("databases/") -> File(databaseDir, entry.name.removePrefix("databases/"))
                    entry.name.startsWith("shared_prefs/") -> File(sharedPrefsDir, entry.name.removePrefix("shared_prefs/"))
                    entry.name.startsWith("historic-map-archive/") -> File(historicDir, entry.name.removePrefix("historic-map-archive/"))
                    entry.name.startsWith("lidar/") -> File(lidarDir, entry.name.removePrefix("lidar/"))
                    else -> null
                } ?: continue
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
                count += 1
            }
        }
        return count
    }

    private fun copyDir(zip: ZipOutputStream, dir: File, prefix: String) {
        if (!dir.isDirectory) return
        dir.listFiles()?.filter { it.isFile }?.forEach { putFile(zip, it, "$prefix/${it.name}") }
    }

    private fun putFile(zip: ZipOutputStream, file: File, path: String) {
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
