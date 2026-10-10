package com.example.data.historicmap

import com.example.analysis.FeatureDisplayBucket
import com.example.analysis.HistoricFeatureClassifier
import com.example.analysis.TerrainFeatureCandidate
import com.example.analysis.TerrainFeatureType
import com.example.analysis.TerrainFeedbackRating
import com.example.data.field.FieldNavigation

/**
 * Raises detector confidence when a find sits near a historic-map symbol.
 * Automatic checks always use every archived map; the picker only chooses the overlay.
 */
object HistoricMapFindMatcher {
    fun match(
        candidate: TerrainFeatureCandidate,
        latitude: Double,
        longitude: Double,
        maps: List<HistoricMapRecord>,
        features: List<HistoricMapFeature>,
    ): Pair<TerrainFeatureCandidate, List<MapFindMatch>> {
        val hits = ArrayList<MapFindMatch>()
        var boost = 0f
        for (map in maps) {
            val radius = map.matchRadiusMeters
            val relevant = features.filter { it.mapId == map.id && typesAgree(candidate.type, it.type) }
            for (feature in relevant) {
                val nearest = feature.points.minOfOrNull { vertex ->
                    FieldNavigation.distanceMeters(latitude, longitude, vertex.latitude, vertex.longitude)
                } ?: continue
                val increment = AutoGeoreferencer.boostForMatch(
                    residualRmseMeters = map.residualRmseMeters,
                    georeferenced = map.georeferenced,
                    distanceMeters = nearest,
                    matchRadiusMeters = radius,
                )
                if (increment <= 0f) continue
                hits += MapFindMatch(
                    mapId = map.id,
                    featureType = feature.type,
                    distanceMeters = nearest,
                    confidenceBoost = increment,
                    residualWidened = radius > AutoGeoreferencer.BASE_MATCH_RADIUS_METERS,
                )
                boost += increment
            }
        }
        // One poorly aligned map cannot confirm a find.
        val alignedMaps = maps.count { it.georeferenced && (it.residualRmseMeters ?: 0.0) <= 12.0 }
        if (alignedMaps == 0) boost = boost.coerceAtMost(0.08f)
        val updated = candidate.copy(
            score = (candidate.score + boost).coerceIn(0f, 1f),
            mapBoost = boost,
            evidence = candidate.evidence + hits.take(3).map {
                "map ${it.featureType.label} @ ${"%.0f".format(it.distanceMeters)} m"
            },
        )
        return updated to hits
    }

    fun applyAll(
        candidates: List<TerrainFeatureCandidate>,
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double,
        maps: List<HistoricMapRecord>,
        features: List<HistoricMapFeature>,
    ): List<TerrainFeatureCandidate> {
        if (maps.isEmpty() || features.isEmpty()) return candidates
        val spanLat = (maxLatitude - minLatitude).takeIf { it > 0.0 } ?: return candidates
        val spanLon = (maxLongitude - minLongitude).takeIf { it > 0.0 } ?: return candidates
        return candidates.map { candidate ->
            val longitude = minLongitude + (candidate.xPercent / 100.0) * spanLon
            val latitude = maxLatitude - (candidate.yPercent / 100.0) * spanLat
            val (updated, _) = match(candidate, latitude, longitude, maps, features)
            val bucket = when {
                updated.feedback == TerrainFeedbackRating.CONFIRMED -> FeatureDisplayBucket.AUTO
                updated.feedback == TerrainFeedbackRating.REJECTED -> FeatureDisplayBucket.HIDDEN
                updated.visualizationHits < HistoricFeatureClassifier.MIN_VISUALIZATION_HITS ->
                    updated.displayBucket
                updated.score >= HistoricFeatureClassifier.DEFAULT_AUTO_SHOW_THRESHOLD ->
                    FeatureDisplayBucket.AUTO
                else -> FeatureDisplayBucket.MAYBE
            }
            updated.copy(displayBucket = bucket)
        }.filter { it.displayBucket != FeatureDisplayBucket.HIDDEN }
    }

    private fun typesAgree(find: TerrainFeatureType, symbol: MapFeatureType): Boolean = when (find) {
        TerrainFeatureType.FOUNDATION, TerrainFeatureType.CELLAR_HOLE, TerrainFeatureType.OLD_HOMESITE ->
            symbol == MapFeatureType.STRUCTURE
        TerrainFeatureType.ROAD_TRAIL -> symbol == MapFeatureType.ROAD
        TerrainFeatureType.STONE_WALL -> symbol == MapFeatureType.WALL
        else -> false
    }
}
