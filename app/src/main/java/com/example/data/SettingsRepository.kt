package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class SettingsRepository(private val settingDao: SettingDao) {
    suspend fun saveSetting(key: String, value: String) {
        settingDao.insert(Setting(key, value))
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

    fun getAllSettings(): Flow<List<Setting>> = flow {
        emit(settingDao.getAll())
    }
}
