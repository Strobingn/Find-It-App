package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.OfflineMapCache
import com.example.data.OfflineMapCache.CacheRegion
import com.example.data.OfflineMapCache.CacheStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing offline map caching and survey regions.
 * 
 * This ViewModel provides functionality for:
 * - Pre-downloading map tiles for offline use
 * - Managing cached regions
 * - Monitoring cache statistics
 * - Clearing old or unused cache
 */
class OfflineMapsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val mapCache = OfflineMapCache(application)
    
    // State for cache statistics
    private val _cacheStats = MutableStateFlow<CacheStats?>(null)
    val cacheStats: StateFlow<CacheStats?> = _cacheStats.asStateFlow()
    
    // State for current download progress
    private val _downloadProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val downloadProgress: StateFlow<Pair<Int, Int>?> = _downloadProgress.asStateFlow()
    
    // State for cached regions
    private val _cachedRegions = MutableStateFlow<List<String>>(emptyList())
    val cachedRegions: StateFlow<List<String>> = _cachedRegions.asStateFlow()
    
    // State for download status message
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()
    
    // State for current region being downloaded
    private val _currentRegion = MutableStateFlow<CacheRegion?>(null)
    val currentRegion: StateFlow<CacheRegion?> = _currentRegion.asStateFlow()
    
    init {
        refreshCacheStats()
        refreshCachedRegions()
    }
    
    /**
     * Refresh cache statistics
     */
    fun refreshCacheStats() {
        viewModelScope.launch {
            _cacheStats.value = mapCache.getStats()
        }
    }
    
    /**
     * Refresh list of cached regions
     */
    fun refreshCachedRegions() {
        viewModelScope.launch {
            _cachedRegions.value = mapCache.listRegionDefinitions()
        }
    }
    
    /**
     * Pre-download map tiles for a region
     */
    fun preloadRegion(
        region: CacheRegion,
        providerUrl: String = OfflineMapCache.OSM_STANDARD
    ) {
        viewModelScope.launch {
            _currentRegion.value = region
            _statusMessage.value = "Starting download..."
            _downloadProgress.value = 0 to 1
            
            try {
                val key = mapCache.generateRegionKey(region, providerUrl)
                
                // Save region definition
                mapCache.saveRegionDefinition(key, region, providerUrl)
                
                // Preload tiles
                val downloaded = mapCache.preloadRegion(
                    region = region,
                    providerUrl = providerUrl,
                    onProgress = { processed, total ->
                        _downloadProgress.value = processed to total
                        _statusMessage.value = "Downloaded $processed of $total tiles"
                    },

                )
                
                _statusMessage.value = "Download complete: $downloaded tiles cached"
                refreshCacheStats()
                refreshCachedRegions()
                
            } catch (e: Exception) {
                _statusMessage.value = "Download failed: ${e.localizedMessage}"
            } finally {
                _currentRegion.value = null
                _downloadProgress.value = null
            }
        }
    }
    
    /**
     * Clear all cached tiles
     */
    fun clearAllCache() {
        viewModelScope.launch {
            _statusMessage.value = "Clearing cache..."
            val success = mapCache.clearAll()
            _statusMessage.value = if (success) "Cache cleared" else "Failed to clear cache"
            refreshCacheStats()
        }
    }
    
    /**
     * Clear old tiles (older than daysToKeep days)
     */
    fun clearOldCache(daysToKeep: Int = 30) {
        viewModelScope.launch {
            _statusMessage.value = "Clearing old tiles..."
            val count = mapCache.clearOldTiles(daysToKeep)
            _statusMessage.value = "Cleared $count old tiles"
            refreshCacheStats()
        }
    }
    
    /**
     * Remove a specific cached region
     */
    fun removeCachedRegion(key: String) {
        viewModelScope.launch {
            val removed = mapCache.deleteRegionDefinition(key)
            refreshCachedRegions()
            _statusMessage.value = if (removed) {
                "Region removed; shared cached tiles were kept."
            } else {
                "Could not remove region."
            }
        }
    }

    fun reportValidationError(message: String) {
        _statusMessage.value = message
    }
    
    /**
     * Load a saved region definition
     */
    fun loadRegionDefinition(key: String): CacheRegion? {
        return mapCache.loadRegionDefinition(key)?.first
    }
    

    /**
     * Check if a specific region is already cached
     */
    fun isRegionCached(region: CacheRegion, providerUrl: String): Boolean {
        val key = mapCache.generateRegionKey(region, providerUrl)
        return mapCache.listRegionDefinitions().contains(key)
    }
    
    /**
     * Get the current cache size in MB
     */
    fun getCacheSizeMB(): Double {
        return cacheStats.value?.sizeInMB() ?: 0.0
    }
    
    /**
     * Get the cache usage percentage
     */
    fun getCacheUsagePercent(): Double {
        return cacheStats.value?.percentUsed() ?: 0.0
    }
    
    /**
     * Check if cache is full
     */
    fun isCacheFull(): Boolean {
        return cacheStats.value?.isFull() ?: false
    }
}
