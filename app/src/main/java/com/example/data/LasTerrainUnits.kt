package com.example.data

import com.example.gps.crs.LasCrsReader
import java.io.File

/** Separate XY/Z factors. Missing vertical metadata retains the existing metre assumption. */
internal data class LasTerrainUnits(val horizontal: Double = 1.0, val vertical: Double = 1.0) {
    companion object {
        fun read(file: File?): LasTerrainUnits {
            val info = file?.let { LasCrsReader.read(it) } ?: return LasTerrainUnits()
            return LasTerrainUnits(info.crs?.unitToMeters ?: 1.0, info.verticalUnitToMeters ?: 1.0)
        }
    }
}
