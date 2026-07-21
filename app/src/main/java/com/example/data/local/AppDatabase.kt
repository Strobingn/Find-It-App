package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [TargetSignalEntity::class, Setting::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun targetSignalDao(): TargetSignalDao
    abstract fun settingDao(): SettingDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE target_signals ADD COLUMN photoUris TEXT NOT NULL DEFAULT ''")
            }
        }
        private val migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS settings (
                        key TEXT PRIMARY KEY NOT NULL,
                        value TEXT NOT NULL
                    )
                """)
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "find-it.db",
            )
                .addMigrations(migration1To2, migration2To3)
                .build()
                .also { instance = it }
        }
    }
}
