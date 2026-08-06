package com.example.data.export

import android.database.sqlite.SQLiteDatabase
import com.example.data.TargetSignal
import java.io.File
import java.util.Locale

/**
 * Minimal GeoPackage (SQLite) export of logged finds.
 *
 * Writes a SQLite database with:
 * - `PRAGMA application_id` / `user_version` matching GeoPackage conventions
 * - required `gpkg_spatial_ref_sys` + `gpkg_contents` rows
 * - attribute table `finds` (id, lat, lon, metal, status, notes)
 *
 * This is a handoff-friendly attributes package, not a full geometry/feature layer and
 * never claims metal identity or dig depth from LiDAR.
 */
object GeoPackageWriter {
    /** OGC GeoPackage application_id: ASCII "GPKG". */
    const val GPKG_APPLICATION_ID = 0x47504B47

    /** GeoPackage 1.2.x user_version convention. */
    const val GPKG_USER_VERSION = 10200

    const val FINDS_TABLE = "finds"

    fun writeFinds(projectName: String, signals: List<TargetSignal>): ByteArray {
        val temp = File.createTempFile("findit-finds-", ".gpkg")
        try {
            // openOrCreateDatabase requires the path to not exist as a non-db file after createTempFile.
            if (temp.exists()) temp.delete()
            val db = SQLiteDatabase.openOrCreateDatabase(temp.absolutePath, null)
            try {
                db.execSQL("PRAGMA application_id = $GPKG_APPLICATION_ID")
                db.execSQL("PRAGMA user_version = $GPKG_USER_VERSION")
                createCoreTables(db)
                insertSpatialRefSys(db)
                createFindsTable(db)
                insertFinds(db, signals)
                insertContents(db, projectName, signals)
            } finally {
                db.close()
            }
            return temp.readBytes()
        } finally {
            temp.delete()
        }
    }

    private fun createCoreTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE gpkg_spatial_ref_sys (
              srs_name TEXT NOT NULL,
              srs_id INTEGER NOT NULL PRIMARY KEY,
              organization TEXT NOT NULL,
              organization_coordsys_id INTEGER NOT NULL,
              definition TEXT NOT NULL,
              description TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE gpkg_contents (
              table_name TEXT NOT NULL PRIMARY KEY,
              data_type TEXT NOT NULL,
              identifier TEXT UNIQUE,
              description TEXT DEFAULT '',
              last_change DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
              min_x DOUBLE,
              min_y DOUBLE,
              max_x DOUBLE,
              max_y DOUBLE,
              srs_id INTEGER,
              CONSTRAINT fk_gc_r_srs_id FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id)
            )
            """.trimIndent(),
        )
    }

    private fun insertSpatialRefSys(db: SQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO gpkg_spatial_ref_sys
              (srs_name, srs_id, organization, organization_coordsys_id, definition, description)
            VALUES
              ('Undefined cartesian SRS', 0, 'NONE', 0, 'undefined', 'undefined'),
              ('Undefined geographic SRS', -1, 'NONE', -1, 'undefined', 'undefined'),
              ('WGS 84 geodetic', 4326, 'EPSG', 4326,
               'GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]]',
               'longitude/latitude')
            """.trimIndent(),
        )
    }

    private fun createFindsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $FINDS_TABLE (
              id INTEGER PRIMARY KEY NOT NULL,
              lat REAL,
              lon REAL,
              metal TEXT,
              status TEXT,
              notes TEXT
            )
            """.trimIndent(),
        )
    }

    private fun insertFinds(db: SQLiteDatabase, signals: List<TargetSignal>) {
        val statement = db.compileStatement(
            "INSERT INTO $FINDS_TABLE (id, lat, lon, metal, status, notes) VALUES (?,?,?,?,?,?)",
        )
        try {
            for (signal in signals) {
                statement.clearBindings()
                statement.bindLong(1, signal.id)
                if (signal.latitude != null) {
                    statement.bindDouble(2, signal.latitude)
                } else {
                    statement.bindNull(2)
                }
                if (signal.longitude != null) {
                    statement.bindDouble(3, signal.longitude)
                } else {
                    statement.bindNull(3)
                }
                statement.bindString(4, signal.metalType.label)
                statement.bindString(5, signal.status)
                statement.bindString(6, signal.notes)
                statement.executeInsert()
            }
        } finally {
            statement.close()
        }
    }

    private fun insertContents(
        db: SQLiteDatabase,
        projectName: String,
        signals: List<TargetSignal>,
    ) {
        val positioned = signals.filter { it.latitude != null && it.longitude != null }
        val minLon = positioned.minOfOrNull { it.longitude!! }
        val maxLon = positioned.maxOfOrNull { it.longitude!! }
        val minLat = positioned.minOfOrNull { it.latitude!! }
        val maxLat = positioned.maxOfOrNull { it.latitude!! }
        val identifier = projectName.take(255).ifBlank { "Find It finds" }
        val description =
            "Find It logged targets (attributes). LiDAR is terrain context only — not metal proof."
        db.execSQL(
            """
            INSERT INTO gpkg_contents
              (table_name, data_type, identifier, description, min_x, min_y, max_x, max_y, srs_id)
            VALUES (?, 'attributes', ?, ?, ?, ?, ?, ?, 4326)
            """.trimIndent(),
            arrayOf(
                FINDS_TABLE,
                identifier,
                description,
                minLon,
                minLat,
                maxLon,
                maxLat,
            ),
        )
    }

    /** Human-readable size label for Tools status. */
    fun formatByteSize(bytes: Int): String =
        when {
            bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
}
