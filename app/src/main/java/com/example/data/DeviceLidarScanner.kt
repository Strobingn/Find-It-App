package com.example.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** A LAZ/LAS/COPC file found in shared phone storage (outside the app's private library). */
data class DeviceLidarFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val folder: String,
    /** Content Uri when found via MediaStore fallback; null means read [path] directly. */
    val contentUri: String? = null,
)

/**
 * Finds LAZ/LAS/COPC files anywhere on the phone. With All-files access it walks
 * /storage/emulated/0 and SD-card volumes; otherwise it falls back to MediaStore.
 */
object DeviceLidarScanner {
    private const val MAX_DEPTH = 12
    private const val MAX_RESULTS = 2_000

    private val _files = MutableStateFlow<List<DeviceLidarFile>>(emptyList())
    val files: StateFlow<List<DeviceLidarFile>> = _files.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val mutex = Mutex()

    /** True for .laz, .las and .copc.laz (case-insensitive); rejects partial/hidden files. */
    fun isLidarFileName(name: String): Boolean {
        if (name.isBlank() || name.startsWith(".")) return false
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".laz") || lower.endsWith(".las")
    }

    /** Skip hidden dirs and app-private Android/data + Android/obb trees. */
    fun shouldSkipDirectory(dir: File, volumeRoot: File): Boolean {
        if (dir.name.startsWith(".")) return true
        val parent = dir.parentFile ?: return false
        val isAndroidChild = parent.name == "Android" && parent.parentFile?.absolutePath == volumeRoot.absolutePath
        return isAndroidChild && (dir.name == "data" || dir.name == "obb")
    }

    /**
     * Drop files already in the app library (same name + size, case-insensitive) and
     * duplicate hits of the same path/name+size; result sorted newest first.
     */
    fun dedupe(found: List<DeviceLidarFile>, library: List<LazDataset>): List<DeviceLidarFile> {
        val libraryKeys = library.map { it.displayName.lowercase(Locale.US) to it.sizeBytes }.toSet()
        val libraryPaths = library.map { it.file.absolutePath }.toSet()
        val seenPaths = HashSet<String>()
        val seenKeys = HashSet<Pair<String, Long>>()
        return found
            .filter { it.path !in libraryPaths }
            .filter { (it.name.lowercase(Locale.US) to it.sizeBytes) !in libraryKeys }
            .filter { seenPaths.add(it.path) }
            .filter { seenKeys.add(it.name.lowercase(Locale.US) to it.sizeBytes) }
            .sortedByDescending { it.modifiedAtMillis }
    }

    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }

    suspend fun scan(context: Context, library: List<LazDataset>): List<DeviceLidarFile> {
        if (!mutex.tryLock()) return _files.value
        try {
            _scanning.value = true
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    if (hasAllFilesAccess(context)) walkVolumes(context) else queryMediaStore(context)
                }.getOrElse { runCatching { queryMediaStore(context) }.getOrDefault(emptyList()) }
            }
            val result = dedupe(found, library)
            _files.value = result
            return result
        } finally {
            _scanning.value = false
            mutex.unlock()
        }
    }

    /** Re-apply dedupe after the library changes (e.g. a scanned file was just imported). */
    fun refilter(library: List<LazDataset>) {
        _files.value = dedupe(_files.value, library)
    }

    private fun volumeRoots(context: Context): List<File> {
        val roots = LinkedHashSet<File>()
        @Suppress("DEPRECATION")
        roots += Environment.getExternalStorageDirectory()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            sm?.storageVolumes?.forEach { vol -> vol.directory?.let { roots += it } }
        }
        return roots.filter { it.canRead() }
    }

    private suspend fun walkVolumes(context: Context): List<DeviceLidarFile> {
        val out = ArrayList<DeviceLidarFile>()
        for (root in volumeRoots(context)) {
            walk(root, root, 0, out)
            if (out.size >= MAX_RESULTS) break
        }
        return out
    }

    private suspend fun walk(dir: File, root: File, depth: Int, out: MutableList<DeviceLidarFile>) {
        currentCoroutineContext().ensureActive()
        if (depth > MAX_DEPTH || out.size >= MAX_RESULTS) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (out.size >= MAX_RESULTS) return
            if (child.isDirectory) {
                if (!shouldSkipDirectory(child, root)) walk(child, root, depth + 1, out)
            } else if (isLidarFileName(child.name) && child.length() > 0L) {
                out += DeviceLidarFile(
                    path = child.absolutePath,
                    name = child.name,
                    sizeBytes = child.length(),
                    modifiedAtMillis = child.lastModified(),
                    folder = child.parentFile?.absolutePath.orEmpty(),
                )
            }
        }
    }

    private fun queryMediaStore(context: Context): List<DeviceLidarFile> {
        val out = ArrayList<DeviceLidarFile>()
        val collection = MediaStore.Files.getContentUri("external")
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATA,
        )
        val selection = "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ? OR " +
            "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ?"
        context.contentResolver.query(collection, projection, selection, arrayOf("%.laz", "%.las"), null)
            ?.use { c ->
                while (c.moveToNext() && out.size < MAX_RESULTS) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    if (!isLidarFileName(name)) continue
                    val data = c.getString(4).orEmpty()
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    out += DeviceLidarFile(
                        path = data.ifBlank { uri.toString() },
                        name = name,
                        sizeBytes = c.getLong(2),
                        modifiedAtMillis = c.getLong(3) * 1000L,
                        folder = File(data).parent.orEmpty(),
                        contentUri = uri.toString(),
                    )
                }
            }
        return out
    }
}
