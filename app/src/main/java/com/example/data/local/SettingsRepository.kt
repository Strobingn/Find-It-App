package com.example.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class SettingsRepository(private val settingDao: SettingDao) {
    
    // Predefined setting keys
    object Keys {
        const val SUN_AZIMUTH = "sun_azimuth"
        const val SUN_ALTITUDE = "sun_altitude"
        const val VEGETATION_FILTER = "vegetation_filter"
        const val PALETTE_TYPE = "palette_type"
        const val CONTRAST = "contrast"
        const val VISUALIZATION_MODE = "visualization_mode"
        const val OVERLAY_TYPE = "overlay_type"
        const val OVERLAY_OPACITY = "overlay_opacity"
        const val GRID_SPACING = "grid_spacing"
        const val Z_SCALE = "z_scale"
        const val FEATURE_SCALE_METERS = "feature_scale_meters"
        const val ANALYSIS_SENSITIVITY = "analysis_sensitivity"
        const val CONTOUR_INTERVAL_METERS = "contour_interval_meters"
        const val CURRENT_SITE_INDEX = "current_site_index"
        const val SWEEP_X = "sweep_x"
        const val SWEEP_Y = "sweep_y"
        /** When true, zoom/pan re-rasterizes the original LAZ/LAS/GeoTIFF for the viewport. */
        const val AUTO_REFINE_TERRAIN = "auto_refine_terrain"
        /** Minimum map zoom before auto detail fires. */
        const val AUTO_REFINE_MIN_ZOOM = "auto_refine_min_zoom"
    }

    suspend fun saveString(key: String, value: String) {
        settingDao.insert(Setting(key, value))
    }

    suspend fun saveInt(key: String, value: Int) {
        settingDao.insert(Setting(key, value.toString()))
    }

    suspend fun saveFloat(key: String, value: Float) {
        settingDao.insert(Setting(key, value.toString()))
    }

    suspend fun saveBoolean(key: String, value: Boolean) {
        settingDao.insert(Setting(key, value.toString()))
    }

    suspend fun getString(key: String, defaultValue: String = ""): String {
        return settingDao.getValue(key) ?: defaultValue
    }

    suspend fun getInt(key: String, defaultValue: Int = 0): Int {
        return settingDao.getValue(key)?.toIntOrNull() ?: defaultValue
    }

    suspend fun getFloat(key: String, defaultValue: Float = 0f): Float {
        return settingDao.getValue(key)?.toFloatOrNull() ?: defaultValue
    }

    suspend fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return settingDao.getValue(key)?.toBooleanStrictOrNull() ?: defaultValue
    }

    suspend fun deleteSetting(key: String) {
        settingDao.delete(key)
    }

    suspend fun clearAll() {
        settingDao.getAll().forEach { settingDao.delete(it.key) }
    }

    fun getAllSettings(): Flow<List<Setting>> = flow {
        emit(settingDao.getAll())
    }
}
