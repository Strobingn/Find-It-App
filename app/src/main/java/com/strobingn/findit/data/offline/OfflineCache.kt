package com.strobingn.findit.data.offline

import android.content.Context
import java.io.File

/**
 * Offline-first local cache (roadmap #10).
 * Stores exports, DEM snippets, and JSON packs under app files.
 */
class OfflineCache(context: Context) {
  private val root: File = File(context.filesDir, "offline").also { it.mkdirs() }
  val findsFile: File = File(root, "finds.json")
  val gridsFile: File = File(root, "grids.json")
  val teamFile: File = File(root, "team.json")
  val settingsFile: File = File(root, "settings.json")
  val exportsDir: File = File(root, "exports").also { it.mkdirs() }
  val demDir: File = File(root, "dem").also { it.mkdirs() }
  val tilesDir: File = File(root, "tiles").also { it.mkdirs() }

  fun writeBytes(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
  }

  fun writeText(file: File, text: String) {
    file.parentFile?.mkdirs()
    file.writeText(text)
  }

  fun readTextOrNull(file: File): String? =
    if (file.exists()) file.readText() else null

  fun clearTiles() {
    tilesDir.listFiles()?.forEach { it.deleteRecursively() }
  }

  fun usageBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
