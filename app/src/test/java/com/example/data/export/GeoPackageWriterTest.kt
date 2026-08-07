package com.example.data.export

import android.database.sqlite.SQLiteDatabase
import com.example.data.DetectionSource
import com.example.data.MetalType
import com.example.data.TargetSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeoPackageWriterTest {

    private val signal = TargetSignal(
        id = 42L,
        gridX = 50f,
        gridY = 40f,
        metalType = MetalType.MANUAL_MARKER,
        signalStrength = 10f,
        latitude = 42.5,
        longitude = -74.1,
        source = DetectionSource.MANUAL,
        notes = "cellar corner",
        status = "Logged",
    )

    @Test
    fun writeFindsProducesSqliteWithGpkgMarkersAndRows() {
        val bytes = GeoPackageWriter.writeFinds("North woods", listOf(signal))
        assertTrue(bytes.size > 100)
        // SQLite magic header
        assertEquals("SQLite format 3\u0000", bytes.copyOfRange(0, 16).toString(Charsets.US_ASCII))

        val temp = File.createTempFile("gpkg-test-", ".gpkg")
        try {
            temp.writeBytes(bytes)
            val db = SQLiteDatabase.openDatabase(
                temp.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            try {
                db.rawQuery("PRAGMA application_id", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(GeoPackageWriter.GPKG_APPLICATION_ID, cursor.getInt(0))
                }
                db.rawQuery("PRAGMA user_version", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(GeoPackageWriter.GPKG_USER_VERSION, cursor.getInt(0))
                }
                db.rawQuery(
                    "SELECT table_name, data_type, identifier FROM gpkg_contents",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(GeoPackageWriter.FINDS_TABLE, cursor.getString(0))
                    assertEquals("attributes", cursor.getString(1))
                    assertEquals("North woods", cursor.getString(2))
                }
                db.rawQuery(
                    "SELECT id, lat, lon, metal, status, notes FROM finds",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(42L, cursor.getLong(0))
                    assertEquals(42.5, cursor.getDouble(1), 1e-9)
                    assertEquals(-74.1, cursor.getDouble(2), 1e-9)
                    assertEquals("Manual marker", cursor.getString(3))
                    assertEquals("Logged", cursor.getString(4))
                    assertEquals("cellar corner", cursor.getString(5))
                    assertEquals(1, cursor.count)
                }
            } finally {
                db.close()
            }
        } finally {
            temp.delete()
        }
    }

    @Test
    fun writeFindsAllowsEmptySignals() {
        val bytes = GeoPackageWriter.writeFinds("Empty site", emptyList())
        assertTrue(bytes.size > 100)
        val temp = File.createTempFile("gpkg-empty-", ".gpkg")
        try {
            temp.writeBytes(bytes)
            val db = SQLiteDatabase.openDatabase(
                temp.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            try {
                db.rawQuery("SELECT COUNT(*) FROM finds", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            } finally {
                db.close()
            }
        } finally {
            temp.delete()
        }
    }
}
