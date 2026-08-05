package com.example.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.data.historicmap.GeoReferenceConfidence
import com.example.data.historicmap.HistoricMapControlPoint
import com.example.data.historicmap.controlPointsFromStorage
import com.example.data.historicmap.controlPointsToStorage
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

private val SUPPORTED_HISTORIC_MAP_EXTENSIONS = setOf("jpg", "jpeg", "png", "tif", "tiff", "webp", "bmp")
private const val MAX_HISTORIC_MAP_IMPORT_BYTES = 200L * 1024L * 1024L

/**
 * A user-imported historic map image (a scanned survey, plat, or old topographic sheet) aligned
 * over the live terrain map. Placement is user-driven: either manual center/scale/rotation, or a
 * [GeoReferencer] fit from [controlPoints]. [latitude]/[longitude] mark the image center, and
 * [widthScale]/[heightScale] scale the image's natural footprint ([baseWidthMeters] at
 * [aspectRatio]).
 */
data class HistoricMapOverlay(
    val id: String,
    val file: File,
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val baseWidthMeters: Float,
    val aspectRatio: Float,
    val widthScale: Float = 1f,
    val heightScale: Float = 1f,
    val bearingDegrees: Float = 0f,
    val opacity: Float = 0.65f,
    val visible: Boolean = true,
    val controlPoints: List<HistoricMapControlPoint> = emptyList(),
    val confidence: GeoReferenceConfidence = GeoReferenceConfidence.INSUFFICIENT_POINTS,
    val rmseMeters: Double? = null,
    val maxResidualMeters: Double? = null,
    val transformStorage: String? = null,
    val sourceAttribution: String = "",
) {
    val widthMeters: Float get() = (baseWidthMeters * widthScale).coerceAtLeast(1f)
    val heightMeters: Float get() = (baseWidthMeters / aspectRatio.coerceAtLeast(0.01f) * heightScale).coerceAtLeast(1f)

    val hasReliableGeoreference: Boolean
        get() = confidence == GeoReferenceConfidence.GOOD ||
            confidence == GeoReferenceConfidence.FAIR
}

/**
 * Persistent app-private storage for imported historic map images plus the manual alignment
 * (position, scale, rotation, opacity, visibility) the user assigns to each one. File layout
 * mirrors [LazDatasetStore]; alignment fields are stored alongside each file's record since,
 * unlike the single rendered terrain layer, a project may carry several historic maps at once.
 */
class HistoricMapOverlayRepository(
    context: Context,
    directory: File? = null,
) {
    private val storeDirectory = directory ?: File(
        context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir,
        "historic-maps",
    )
    private val preferences = context.applicationContext
        .getSharedPreferences("historic_map_overlays", Context.MODE_PRIVATE)

    init {
        storeDirectory.mkdirs()
    }

    fun list(): List<HistoricMapOverlay> {
        val ids = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty()
        return ids.mapNotNull(::load).sortedBy { it.displayName.lowercase(Locale.US) }
    }

    /** Copies [uri] into app-private storage and creates a default, unaligned overlay record. */
    fun importImage(
        context: Context,
        uri: Uri,
        requestedName: String,
        defaultLatitude: Double,
        defaultLongitude: Double,
        defaultBaseWidthMeters: Float,
    ): HistoricMapOverlay {
        val destination = destinationFor(requestedName)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destination).use { output -> copyAtMost(input, output, MAX_HISTORIC_MAP_IMPORT_BYTES) }
            } ?: error("Could not open selected historic map file")
            require(destination.length() > 0L) { "$requestedName is empty" }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(destination.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            destination.delete()
            error("Could not read $requestedName as an image")
        }
        val aspectRatio = bounds.outWidth.toFloat() / bounds.outHeight.toFloat()

        val overlay = HistoricMapOverlay(
            id = UUID.randomUUID().toString(),
            file = destination,
            displayName = requestedName,
            latitude = defaultLatitude,
            longitude = defaultLongitude,
            baseWidthMeters = defaultBaseWidthMeters.coerceAtLeast(1f),
            aspectRatio = aspectRatio,
        )
        save(overlay)
        return overlay
    }

    fun update(overlay: HistoricMapOverlay) = save(overlay)

    fun delete(overlay: HistoricMapOverlay) {
        overlay.file.delete()
        val ids = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet()
        ids.remove(overlay.id)
        val editor = preferences.edit().putStringSet(KEY_IDS, ids)
        FIELDS.forEach { field -> editor.remove(key(overlay.id, field)) }
        editor.apply()
    }

    private fun destinationFor(requestedName: String): File {
        storeDirectory.mkdirs()
        val raw = requestedName.substringAfterLast('/').substringBefore('?')
        val safe = raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "historic-map.jpg" }
        val extension = safe.substringAfterLast('.', "").lowercase(Locale.US)
        val normalized = if (extension in SUPPORTED_HISTORIC_MAP_EXTENSIONS) safe else "$safe.jpg"
        val first = File(storeDirectory, normalized)
        if (!first.exists()) return first

        val base = normalized.substringBeforeLast('.')
        val ext = normalized.substringAfterLast('.')
        var index = 2
        while (true) {
            val candidate = File(storeDirectory, "$base-$index.$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun save(overlay: HistoricMapOverlay) {
        val ids = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet()
        ids.add(overlay.id)
        preferences.edit()
            .putStringSet(KEY_IDS, ids)
            .putString(key(overlay.id, "file"), overlay.file.absolutePath)
            .putString(key(overlay.id, "name"), overlay.displayName)
            .putString(key(overlay.id, "lat"), overlay.latitude.toString())
            .putString(key(overlay.id, "lon"), overlay.longitude.toString())
            .putFloat(key(overlay.id, "baseWidth"), overlay.baseWidthMeters)
            .putFloat(key(overlay.id, "aspect"), overlay.aspectRatio)
            .putFloat(key(overlay.id, "widthScale"), overlay.widthScale)
            .putFloat(key(overlay.id, "heightScale"), overlay.heightScale)
            .putFloat(key(overlay.id, "bearing"), overlay.bearingDegrees)
            .putFloat(key(overlay.id, "opacity"), overlay.opacity)
            .putBoolean(key(overlay.id, "visible"), overlay.visible)
            .putString(key(overlay.id, "controlPoints"), controlPointsToStorage(overlay.controlPoints))
            .putString(key(overlay.id, "confidence"), overlay.confidence.name)
            .putString(key(overlay.id, "rmse"), overlay.rmseMeters?.toString())
            .putString(key(overlay.id, "maxResidual"), overlay.maxResidualMeters?.toString())
            .putString(key(overlay.id, "transform"), overlay.transformStorage)
            .putString(key(overlay.id, "attribution"), overlay.sourceAttribution)
            .apply()
    }

    private fun load(id: String): HistoricMapOverlay? {
        val path = preferences.getString(key(id, "file"), null) ?: return null
        val file = File(path)
        if (!file.exists()) return null
        val latitude = preferences.getString(key(id, "lat"), null)?.toDoubleOrNull() ?: return null
        val longitude = preferences.getString(key(id, "lon"), null)?.toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val confidenceName = preferences.getString(key(id, "confidence"), null)
        return HistoricMapOverlay(
            id = id,
            file = file,
            displayName = preferences.getString(key(id, "name"), file.name) ?: file.name,
            latitude = latitude,
            longitude = longitude,
            baseWidthMeters = preferences.getFloat(key(id, "baseWidth"), 200f).coerceAtLeast(1f),
            aspectRatio = preferences.getFloat(key(id, "aspect"), 1f).coerceAtLeast(0.01f),
            // Wider range than the manual sliders so control-point fits survive reload.
            widthScale = preferences.getFloat(key(id, "widthScale"), 1f).coerceIn(0.05f, 20f),
            heightScale = preferences.getFloat(key(id, "heightScale"), 1f).coerceIn(0.05f, 20f),
            bearingDegrees = preferences.getFloat(key(id, "bearing"), 0f).coerceIn(-180f, 180f),
            opacity = preferences.getFloat(key(id, "opacity"), 0.65f).coerceIn(0.1f, 1f),
            visible = preferences.getBoolean(key(id, "visible"), true),
            controlPoints = controlPointsFromStorage(
                preferences.getString(key(id, "controlPoints"), "").orEmpty(),
            ),
            confidence = GeoReferenceConfidence.entries.firstOrNull { it.name == confidenceName }
                ?: GeoReferenceConfidence.INSUFFICIENT_POINTS,
            rmseMeters = preferences.getString(key(id, "rmse"), null)?.toDoubleOrNull(),
            maxResidualMeters = preferences.getString(key(id, "maxResidual"), null)?.toDoubleOrNull(),
            transformStorage = preferences.getString(key(id, "transform"), null),
            sourceAttribution = preferences.getString(key(id, "attribution"), "").orEmpty(),
        )
    }

    private fun key(id: String, field: String) = "$id.$field"

    /** Streams with a hard ceiling so an oversized pick fails fast instead of filling the device first. */
    private fun copyAtMost(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long) {
        val buffer = ByteArray(256 * 1024)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            copied += read
            require(copied <= maxBytes) {
                "Historic map image must be smaller than ${maxBytes / (1024 * 1024)} MB"
            }
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    private companion object {
        const val KEY_IDS = "ids"
        val FIELDS = listOf(
            "file", "name", "lat", "lon", "baseWidth", "aspect",
            "widthScale", "heightScale", "bearing", "opacity", "visible",
            "controlPoints", "confidence", "rmse", "maxResidual", "transform", "attribution",
        )
    }
}
