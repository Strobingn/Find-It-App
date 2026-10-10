package com.example.data.historicmap

import java.io.File

enum class HistoricMapSource(val label: String, val sortRank: Int) {
    DAVID_RUMSEY("David Rumsey Map Collection", 0),
    USGS_HISTORICAL("USGS historical topographic", 1),
    LIBRARY_OF_CONGRESS("Library of Congress", 2),
    SANBORN("Sanborn fire insurance", 3),
    USER_IMPORT("Imported scan", 4),
}

data class HistoricMapRecord(
    val id: String,
    val source: HistoricMapSource,
    val title: String,
    val editionYear: Int?,
    val attribution: String,
    val remoteUrl: String,
    val localFile: File?,
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
    val georeferenced: Boolean,
    val residualRmseMeters: Double? = null,
    val matchRadiusMeters: Double = 50.0,
    val downloadedAtMillis: Long = 0L,
) {
    val footprintKey: String
        get() = listOf(
            source.name,
            editionYear ?: 0,
            "%.4f".format(minLatitude),
            "%.4f".format(maxLatitude),
            "%.4f".format(minLongitude),
            "%.4f".format(maxLongitude),
            remoteUrl,
        ).joinToString("|")
}

data class HistoricMapIndex(
    val records: List<HistoricMapRecord>,
) {
    fun forBounds(
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double,
    ): List<HistoricMapRecord> = records.filter { record ->
        record.maxLatitude >= minLatitude &&
            record.minLatitude <= maxLatitude &&
            record.maxLongitude >= minLongitude &&
            record.minLongitude <= maxLongitude
    }.sortedWith(
        compareBy<HistoricMapRecord> { it.source.sortRank }
            .thenByDescending { it.editionYear ?: Int.MIN_VALUE }
            .thenBy { it.title },
    )
}

data class MapFindMatch(
    val mapId: String,
    val featureType: MapFeatureType,
    val distanceMeters: Double,
    val confidenceBoost: Float,
    val residualWidened: Boolean,
)
