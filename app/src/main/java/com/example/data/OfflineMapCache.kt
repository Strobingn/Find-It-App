package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Manages offline caching of basemap tiles for field use without connectivity.
 * 
 * Supports caching of OpenStreetMap tiles (or other tile providers) for offline use.
 * Tiles are stored in the app's cache directory and can be pre-downloaded for specific regions.
 */
class OfflineMapCache(private val context: Context) {
    
    companion object {
        private const val CACHE_DIR_NAME = "offline_maps"
        private const val MAX_CACHE_SIZE_MB = 500L // 500 MB limit
        private const val TILE_SIZE = 256 // Standard tile size in pixels
        
        // Common tile providers
        const val OSM_STANDARD = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        const val OSM_HUMANITARIAN = "https://tile.openstreetmap.fr/hot/{z}/{x}/{y}.png"
        const val OSM_OUTDOORS = "https://tiles.wmflabs.org/hikebike/{z}/{x}/{y}.png"
    }
    
    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
    }
    
    /**
     * Data class representing a tile coordinate
     */
    data class TileCoordinate(
        val x: Int,
        val y: Int,
        val zoom: Int,
        val provider: String = OSM_STANDARD
    ) {
        fun toFileName(): String {
            return "${provider.hashCode()}_${zoom}_${x}_${y}.png"
        }
        
        fun toFile(): File {
            return File(cacheDir, toFileName())
        }
    }
    
    /**
     * Data class representing a geographic bounding box for caching
     */
    data class CacheRegion(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
        val minZoom: Int = 10,
        val maxZoom: Int = 18
    )
    
    /**
     * Data class for cache statistics
     */
    data class CacheStats(
        val tileCount: Int,
        val totalSizeBytes: Long,
        val maxCacheSizeBytes: Long,
        val providers: Set<String>
    ) {
        fun sizeInMB(): Double = totalSizeBytes / (1024.0 * 1024.0)
        fun isFull(): Boolean = totalSizeBytes >= maxCacheSizeBytes
        fun percentUsed(): Double = (totalSizeBytes.toDouble() / maxCacheSizeBytes) * 100
    }
    
    /**
     * Get a tile from cache if available
     */
    fun getTile(coordinate: TileCoordinate): Bitmap? {
        val file = coordinate.toFile()
        return if (file.exists()) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Store a tile in the cache
     */
    fun storeTile(coordinate: TileCoordinate, bitmap: Bitmap): Boolean {
        return try {
            val file = coordinate.toFile()
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if a tile is cached
     */
    fun hasTile(coordinate: TileCoordinate): Boolean {
        return coordinate.toFile().exists()
    }
    
    /**
     * Pre-download tiles for a specific geographic region
     */
    suspend fun preloadRegion(
        region: CacheRegion,
        providerUrl: String = OSM_STANDARD,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onTileDownloaded: (TileCoordinate) -> Unit = {}
    ): Int {
        var downloadedCount = 0
        val tiles = calculateTilesForRegion(region, providerUrl)
        var processed = 0
        
        tiles.forEach { coordinate ->
            if (!hasTile(coordinate)) {
                // TODO: Download tile from network
                // For now, just mark as processed
                // In a real implementation, this would use a network client
                // to download the tile from the provider URL
            }
            processed++
            downloadedCount++
            onProgress(processed, tiles.size)
            onTileDownloaded(coordinate)
        }
        
        return downloadedCount
    }
    
    /**
     * Calculate which tiles are needed for a geographic region
     */
    private fun calculateTilesForRegion(region: CacheRegion, providerUrl: String): List<TileCoordinate> {
        val tiles = mutableListOf<TileCoordinate>()
        
        for (zoom in region.minZoom..region.maxZoom) {
            val minTileX = lonToTileX(region.minLon, zoom)
            val maxTileX = lonToTileX(region.maxLon, zoom)
            val minTileY = latToTileY(region.maxLat, zoom) // Y is inverted
            val maxTileY = latToTileY(region.minLat, zoom)
            
            for (x in minTileX..maxTileX) {
                for (y in minTileY..maxTileY) {
                    tiles.add(TileCoordinate(x, y, zoom, providerUrl))
                }
            }
        }
        
        return tiles
    }
    
    /**
     * Convert longitude to tile X coordinate
     */
    private fun lonToTileX(lon: Double, zoom: Int): Int {
        return ((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
    }
    
    /**
     * Convert latitude to tile Y coordinate
     */
    private fun latToTileY(lat: Double, zoom: Int): Int {
        return ((1.0 - Math.log(Math.tan(lat * Math.PI / 180.0) + 
                1.0 / Math.cos(lat * Math.PI / 180.0)) / Math.PI) / 2.0 * (1 shl zoom)).toInt()
    }
    
    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats {
        val files = cacheDir.listFiles() ?: return CacheStats(0, 0, MAX_CACHE_SIZE_MB * 1024 * 1024, emptySet())
        
        var totalSize = 0L
        val providers = mutableSetOf<String>()
        
        files.forEach { file ->
            totalSize += file.length()
            // Extract provider from filename
            val name = file.name
            val providerHash = name.substringBefore('_')
            providers.add(providerHash)
        }
        
        return CacheStats(
            tileCount = files.size,
            totalSizeBytes = totalSize,
            maxCacheSizeBytes = MAX_CACHE_SIZE_MB * 1024 * 1024,
            providers = providers
        )
    }
    
    /**
     * Clear all cached tiles
     */
    fun clearAll(): Boolean {
        return try {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Clear tiles for a specific provider
     */
    fun clearProvider(providerUrl: String): Boolean {
        val prefix = "${providerUrl.hashCode()}_"
        return try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(prefix)) {
                    file.delete()
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Clear old tiles (older than daysToKeep days)
     */
    fun clearOldTiles(daysToKeep: Int = 30): Int {
        var deletedCount = 0
        val cutoff = System.currentTimeMillis() - (daysToKeep * 24L * 60 * 60 * 1000)
        
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    file.delete()
                    deletedCount++
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        
        return deletedCount
    }
    
    /**
     * Generate a unique cache key for a region
     */
    fun generateRegionKey(region: CacheRegion, providerUrl: String): String {
        val regionString = "${region.minLat},${region.maxLat},${region.minLon},${region.maxLon},${region.minZoom},${region.maxZoom},$providerUrl"
        return MessageDigest.getInstance("SHA-256")
            .digest(regionString.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Save a region definition for later reuse
     */
    fun saveRegionDefinition(key: String, region: CacheRegion, providerUrl: String): Boolean {
        return try {
            val definitionsDir = File(cacheDir, "definitions").apply { mkdirs() }
            val file = File(definitionsDir, "$key.json")
            file.writeText("""
                {
                    "minLat": ${region.minLat},
                    "maxLat": ${region.maxLat},
                    "minLon": ${region.minLon},
                    "maxLon": ${region.maxLon},
                    "minZoom": ${region.minZoom},
                    "maxZoom": ${region.maxZoom},
                    "provider": "$providerUrl"
                }
            """.trimIndent())
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Load a saved region definition
     */
    fun loadRegionDefinition(key: String): Pair<CacheRegion, String>? {
        return try {
            val definitionsDir = File(cacheDir, "definitions")
            val file = File(definitionsDir, "$key.json")
            if (file.exists()) {
                val content = file.readText()
                // Parse JSON (simplified - in production use a proper JSON parser)
                val minLat = content.substringAfter("\"minLat\":").substringBefore(",").toDouble()
                val maxLat = content.substringAfter("\"maxLat\":").substringBefore(",").toDouble()
                val minLon = content.substringAfter("\"minLon\":").substringBefore(",").toDouble()
                val maxLon = content.substringAfter("\"maxLon\":").substringBefore(",").toDouble()
                val minZoom = content.substringAfter("\"minZoom\":").substringBefore(",").toInt()
                val maxZoom = content.substringAfter("\"maxZoom\":").substringBefore(",").toInt()
                val provider = content.substringAfter("\"provider\":\"").substringBefore("\"")
                
                CacheRegion(minLat, maxLat, minLon, maxLon, minZoom, maxZoom) to provider
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * List all saved region definitions
     */
    fun listRegionDefinitions(): List<String> {
        return try {
            val definitionsDir = File(cacheDir, "definitions")
            if (definitionsDir.exists()) {
                definitionsDir.listFiles()?.map { it.nameWithoutExtension } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
