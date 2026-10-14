package com.example.data

import com.example.gps.crs.LasCrsReader
import com.example.gps.crs.WktCrsParser
import com.github.mreutegg.laszip4j.laslib.LASheader
import java.io.File

/** Separate XY/Z factors. Missing vertical metadata retains the existing metre assumption. */
internal data class LasTerrainUnits(val horizontal: Double = 1.0, val vertical: Double = 1.0) {
    companion object {
        /** Same VLR metadata on range-streamed COPC, where there is no complete local file. */
        fun fromHeader(header: LASheader): LasTerrainUnits {
            val projection = header.vlrs?.filter {
                String(it.user_id, Charsets.US_ASCII).trimEnd('\u0000') == "LASF_Projection"
            }.orEmpty()
            val wkt = projection.firstOrNull { it.record_id.code == 2112 }?.data?.toString(Charsets.UTF_8)
            val keys = projection.firstOrNull { it.record_id.code == 34735 }?.data
            val horizontal = wkt?.let(WktCrsParser::horizontalUnitToMeters)
                ?: keys?.let { LasCrsReader.linearUnitFromGeoKeys(it, 3076) }
                ?: keys?.let(LasCrsReader::fromGeoKeys)?.unitToMeters ?: 1.0
            val vertical = wkt?.let(WktCrsParser::verticalUnitToMeters)
                ?: keys?.let { LasCrsReader.linearUnitFromGeoKeys(it, 4099) } ?: 1.0
            return LasTerrainUnits(horizontal, vertical)
        }

        fun read(file: File?): LasTerrainUnits {
            val info = file?.let { LasCrsReader.read(it) } ?: return LasTerrainUnits()
            return LasTerrainUnits(info.horizontalUnitToMeters ?: 1.0, info.verticalUnitToMeters ?: 1.0)
        }
    }
}
