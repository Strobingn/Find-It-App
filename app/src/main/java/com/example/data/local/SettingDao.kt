package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(setting: Setting)

    @Query("SELECT value FROM settings WHERE key = :key LIMIT 1")
    suspend fun getValue(key: String): String?

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM settings")
    suspend fun clearAll()

    @Query("SELECT * FROM settings")
    suspend fun getAll(): List<Setting>
}
