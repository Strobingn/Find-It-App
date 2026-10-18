package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis
import android.util.Log

@RunWith(AndroidJUnit4::class)
@Config(manifest=Config.NONE)
class SettingsRepositoryBenchmarkTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: SettingDao
    private lateinit var repository: SettingsRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(
            context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.settingDao()
        repository = SettingsRepository(dao)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun benchmarkClearAll() = runBlocking {
        // Insert 1000 items
        for (i in 1..1000) {
            repository.saveString("key_$i", "value_$i")
        }

        val time = measureTimeMillis {
            repository.clearAll()
        }

        System.err.println("BENCHMARK_RESULT: Time to clear all (1000 items): $time ms")
    }
}
