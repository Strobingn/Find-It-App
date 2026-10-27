package com.example.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import java.io.File

/** The Gemma 4 builds the app can run offline. Files come from Hugging Face (Apache-2.0, not gated). */
enum class OnDeviceModel(
    val label: String,
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
    val blurb: String,
) {
    GEMMA4_E2B(
        label = "Gemma 4 E2B",
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        blurb = "Faster, lighter on memory",
    ),
    GEMMA4_E4B(
        label = "Gemma 4 E4B",
        repo = "litert-community/gemma-4-E4B-it-litert-lm",
        fileName = "gemma-4-E4B-it.litertlm",
        sizeBytes = 3_659_530_240L,
        blurb = "Smarter answers, needs a newer phone",
    ),
    ;

    val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"
    val sizeLabel: String get() = OnDeviceModelStore.formatBytes(sizeBytes)
}

/** Where a model download stands. */
sealed interface ModelDownloadState {
    data object NotDownloaded : ModelDownloadState
    data class Downloading(val bytes: Long, val total: Long, val waitingForWifi: Boolean) : ModelDownloadState {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data object Ready : ModelDownloadState
    data class Failed(val reason: String) : ModelDownloadState
}

/**
 * Downloads, tracks and deletes the offline Gemma models. Downloads go through Android's
 * DownloadManager: Wi-Fi only, resumes after drops and app restarts, shows a system notification.
 * Files live in the app's own external files folder (no storage permission, removed on uninstall).
 */
class OnDeviceModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val downloads get() = appContext.getSystemService(DownloadManager::class.java)

    private val dir: File
        get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, MODELS_DIR).apply { mkdirs() }

    fun fileFor(model: OnDeviceModel): File = File(dir, model.fileName)
    private fun partFileName(model: OnDeviceModel) = model.fileName + ".part"

    fun isDownloaded(model: OnDeviceModel): Boolean = fileFor(model).let { it.isFile && it.length() == model.sizeBytes }

    /** The model the user chose, if it's fully downloaded. */
    fun activeModel(): OnDeviceModel? {
        // A download that finished while no screen was watching still needs its final rename.
        OnDeviceModel.entries.forEach { if (!isDownloaded(it) && prefs.contains(keyDownload(it))) state(it) }
        val chosen = prefs.getString(KEY_ACTIVE, null)?.let { name -> OnDeviceModel.entries.firstOrNull { it.name == name } }
        val active = chosen?.takeIf { isDownloaded(it) } ?: OnDeviceModel.entries.firstOrNull { isDownloaded(it) }
        activeModelLabel = active?.label
        return active
    }

    fun chosenModel(): OnDeviceModel =
        prefs.getString(KEY_ACTIVE, null)?.let { name -> OnDeviceModel.entries.firstOrNull { it.name == name } } ?: OnDeviceModel.GEMMA4_E2B

    fun choose(model: OnDeviceModel) = prefs.edit().putString(KEY_ACTIVE, model.name).apply()

    /** Free space where the models are stored. */
    fun freeBytes(): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    /** Starts (or keeps) a Wi-Fi-only download. Returns an error message instead when it can't start. */
    fun startDownload(model: OnDeviceModel): String? {
        if (isDownloaded(model)) return null
        if (state(model) is ModelDownloadState.Downloading) return null
        val needed = model.sizeBytes + SPACE_MARGIN_BYTES
        if (freeBytes() < needed) {
            return "Not enough space: ${model.label} needs ${formatBytes(needed)} free (${formatBytes(freeBytes())} available)."
        }
        File(dir, partFileName(model)).delete()
        val request = DownloadManager.Request(Uri.parse(model.downloadUrl))
            .setTitle("Find It offline AI")
            .setDescription("${model.label} · ${model.sizeLabel}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(appContext, null, "$MODELS_DIR/${partFileName(model)}")
        val id = runCatching { downloads.enqueue(request) }.getOrElse { return "Couldn't start the download: ${it.localizedMessage}" }
        prefs.edit().putLong(keyDownload(model), id).apply()
        return null
    }

    fun cancelDownload(model: OnDeviceModel) {
        val id = prefs.getLong(keyDownload(model), -1L)
        if (id >= 0) runCatching { downloads.remove(id) }
        prefs.edit().remove(keyDownload(model)).apply()
        File(dir, partFileName(model)).delete()
    }

    fun delete(model: OnDeviceModel) {
        cancelDownload(model)
        OnDeviceGemma.release(fileFor(model).path)
        fileFor(model).delete()
    }

    /** Current state; also finalizes a finished download (checks the size, then renames it into place). */
    fun state(model: OnDeviceModel): ModelDownloadState {
        if (isDownloaded(model)) return ModelDownloadState.Ready
        val id = prefs.getLong(keyDownload(model), -1L)
        if (id < 0) return ModelDownloadState.NotDownloaded
        val cursor = runCatching { downloads.query(DownloadManager.Query().setFilterById(id)) }.getOrNull()
            ?: return ModelDownloadState.NotDownloaded
        cursor.use { c ->
            if (!c.moveToFirst()) {
                prefs.edit().remove(keyDownload(model)).apply()
                return ModelDownloadState.NotDownloaded
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: model.sizeBytes
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> finalize(model)
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove(keyDownload(model)).apply()
                    runCatching { downloads.remove(id) }
                    ModelDownloadState.Failed(failureReason(reason))
                }
                else -> ModelDownloadState.Downloading(
                    bytes = bytes,
                    total = total,
                    waitingForWifi = status == DownloadManager.STATUS_PAUSED &&
                        (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI || reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK),
                )
            }
        }
    }

    private fun finalize(model: OnDeviceModel): ModelDownloadState {
        val part = File(dir, partFileName(model))
        prefs.edit().remove(keyDownload(model)).apply()
        if (!part.isFile || part.length() != model.sizeBytes) {
            part.delete()
            return ModelDownloadState.Failed("The download was incomplete — tap Download to try again.")
        }
        val target = fileFor(model)
        target.delete()
        if (!part.renameTo(target)) return ModelDownloadState.Failed("Couldn't save the model file.")
        if (prefs.getString(KEY_ACTIVE, null) == null || !isDownloaded(chosenModel())) choose(model)
        return ModelDownloadState.Ready
    }

    private fun keyDownload(model: OnDeviceModel) = "download_${model.name}"

    companion object {
        /** Label of the last-seen active model, for status lines that have no Context. */
        @Volatile
        var activeModelLabel: String? = null
            private set

        private const val PREFS = "offline_ai"
        private const val KEY_ACTIVE = "active_model"
        private const val MODELS_DIR = "models"
        private const val SPACE_MARGIN_BYTES = 300L * 1024 * 1024

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
            else -> "%.0f KB".format(bytes / 1_000.0)
        }

        internal fun failureReason(reason: Int): String = when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage space."
            DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> "The connection dropped and the download couldn't resume — tap Download to try again."
            DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Hugging Face didn't serve the file — try again later."
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage isn't available."
            else -> "Download failed (code $reason) — tap Download to try again."
        }
    }
}
