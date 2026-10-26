package com.example.thermals

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Terrain features whitetails use, in rough order of how good a stand spot they make. */
enum class StandFeature(val label: String, val baseScore: Double, val blurb: String) {
    SADDLE("Saddle", 1.0, "low gap in a ridge that deer cross instead of climbing over"),
    BENCH("Bench", 0.85, "flat shelf on a slope that deer walk along"),
    DRAW_HEAD("Draw head", 0.75, "top of a draw where trails funnel together"),
    RIDGE_POINT("Ridge point", 0.6, "end of a finger ridge deer travel and bed on"),
    BEDDING("Likely bedding", 0.7, "leeward upper slope where deer bed with the wind at their backs"),
}

enum class HuntWindow(val label: String, val startHour: Double, val endHour: Double) {
    MORNING("Morning sit", 6.0, 9.5),
    EVENING("Evening sit", 15.0, 18.5),
}

/** Regular elevation grid around a center point (row 0 = north edge, col 0 = west edge). */
class ElevationGrid(
    val centerLat: Double,
    val centerLon: Double,
    val size: Int,
    val spacingMeters: Double,
    /** Row-major meters, length size*size. */
    val meters: DoubleArray,
) {
    init {
        require(size >= 5 && meters.size == size * size) { "Grid must be size×size (≥5)" }
    }

    fun at(r: Int, c: Int): Double = meters[r.coerceIn(0, size - 1) * size + c.coerceIn(0, size - 1)]

    /** (lat, lon) of a grid node. */
    fun latLon(r: Int, c: Int): Pair<Double, Double> {
        val half = (size - 1) / 2.0
        val north = (half - r) * spacingMeters
        val east = (c - half) * spacingMeters
        val lat = centerLat + north / 111_320.0
        val lon = centerLon + east / (111_320.0 * cos(Math.toRadians(centerLat)).coerceAtLeast(0.01))
        return lat to lon
    }

    companion object {
        /** Sample points in row-major order for an elevation request. */
        fun samplePoints(centerLat: Double, centerLon: Double, size: Int, spacingMeters: Double): List<Pair<Double, Double>> {
            val grid = ElevationGrid(centerLat, centerLon, size, spacingMeters, DoubleArray(size * size))
            return (0 until size * size).map { grid.latLon(it / size, it % size) }
        }
    }
}

data class StandCandidate(
    val id: Int,
    val latitude: Double,
    val longitude: Double,
    val feature: StandFeature,
    val elevationFeet: Double,
    val slopeDegrees: Double,
    /** Direction you walk to go uphill from the spot, null on flat ground. */
    val uphillBearing: Double?,
    val morningScore: Double,
    val eveningScore: Double,
    /** Average scent drift (toward) during each sit. */
    val morningScentToward: Double,
    val eveningScentToward: Double,
    /** Where deer are expected to come from during each sit. */
    val morningDeerFrom: Double?,
    val eveningDeerFrom: Double?,
) {
    val bestWindow: HuntWindow get() = if (morningScore >= eveningScore) HuntWindow.MORNING else HuntWindow.EVENING
    val bestScore: Double get() = max(morningScore, eveningScore)
    val bestScentToward: Double get() = if (bestWindow == HuntWindow.MORNING) morningScentToward else eveningScentToward
}

/** A finished pick shown on the map (from the AI, or from the local ranking when AI is unavailable). */
data class StandPick(
    val rank: Int,
    val latitude: Double,
    val longitude: Double,
    val kind: Kind,
    val title: String,
    val bestTime: String,
    val wind: String,
    val why: String,
    val entry: String,
    val scentToward: Double?,
    val candidateId: Int?,
) {
    enum class Kind { STAND, AREA }
}

object StandFinder {

    const val GRID_SIZE = 17
    const val GRID_SPACING_M = 100.0

    /**
     * Finds stand-worthy terrain in the grid and scores each spot for the morning and evening sits.
     *
     * Deer rules of thumb used: in the morning deer move from low feeding ground up toward bedding,
     * in the evening from bedding back down. A spot scores well for a sit when the scent drift
     * (thermal + wind at that spot's own slope) points away from where deer come from.
     */
    fun findCandidates(grid: ElevationGrid, field: ThermalWindEngine.FlowField?, prevailingFromDeg: Double?, maxCandidates: Int = 12): List<StandCandidate> {
        val raw = mutableListOf<Triple<Int, Int, StandFeature>>()
        val n = grid.size
        val s = grid.spacingMeters
        for (r in 2 until n - 2) {
            for (c in 2 until n - 2) {
                classify(grid, r, c, prevailingFromDeg)?.let { raw += Triple(r, c, it) }
            }
        }
        val scored = raw.mapNotNull { (r, c, feature) ->
            val (uphill, slope) = ThermalMapGeometry.slopeFromSamples(
                north = grid.at(r - 1, c),
                south = grid.at(r + 1, c),
                east = grid.at(r, c + 1),
                west = grid.at(r, c - 1),
                spacingMeters = s,
            )
            val terrain = ThermalMapGeometry.cellTerrain(uphill, slope)
            val (lat, lon) = grid.latLon(r, c)
            // Saddles: deer cross from both low sides at any time. Slopes: up from below in the
            // morning, down from above in the evening.
            val saddleAxis = if (feature == StandFeature.SADDLE) saddleLowAxis(grid, r, c) else null
            val morningFrom = saddleAxis ?: uphill?.let { ThermalWindEngine.normalize(it + 180.0) }
            val eveningFrom = saddleAxis ?: uphill
            val approaches = { from: Double? ->
                when {
                    from == null -> emptyList()
                    saddleAxis != null -> listOf(from, ThermalWindEngine.normalize(from + 180.0))
                    else -> listOf(from)
                }
            }
            val am = windowScore(field, terrain, HuntWindow.MORNING, approaches(morningFrom))
            val pm = windowScore(field, terrain, HuntWindow.EVENING, approaches(eveningFrom))
            StandCandidate(
                id = 0,
                latitude = lat,
                longitude = lon,
                feature = feature,
                elevationFeet = grid.at(r, c) * 3.28084,
                slopeDegrees = slope,
                uphillBearing = uphill,
                morningScore = feature.baseScore * (0.35 + 0.65 * am.first),
                eveningScore = feature.baseScore * (0.35 + 0.65 * pm.first),
                morningScentToward = am.second,
                eveningScentToward = pm.second,
                morningDeerFrom = morningFrom,
                eveningDeerFrom = eveningFrom,
            )
        }.sortedByDescending { it.bestScore }

        // Keep spots at least ~250 m apart so the picks spread over the property.
        val kept = mutableListOf<StandCandidate>()
        for (cand in scored) {
            if (kept.size >= maxCandidates) break
            if (kept.none { distanceMeters(it.latitude, it.longitude, cand.latitude, cand.longitude) < 250.0 }) kept += cand
        }
        return kept.mapIndexed { i, cand -> cand.copy(id = i + 1) }
    }

    /**
     * Fraction (0..1) of the sit where scent drifts away from the deer's approach, plus the
     * average scent bearing. Without a forecast, neutral 0.5.
     */
    private fun windowScore(
        field: ThermalWindEngine.FlowField?,
        terrain: SiteTerrain,
        window: HuntWindow,
        deerFrom: List<Double>,
    ): Pair<Double, Double> {
        if (field == null) return 0.5 to 0.0
        var good = 0.0
        var total = 0.0
        var east = 0.0
        var north = 0.0
        var t = window.startHour
        while (t <= window.endHour) {
            val h = field.at(t, terrain)
            val rad = Math.toRadians(h.scentTowardDegrees)
            east += sin(rad) * h.scentSpeedMph
            north += cos(rad) * h.scentSpeedMph
            val safe = if (deerFrom.isEmpty()) {
                0.5
            } else {
                // Angle between scent drift and each direction deer come from: 180° = perfect,
                // 0° = busted. The worst approach decides.
                val base = deerFrom.minOf { from -> ((angleDiff(h.scentTowardDegrees, from) - 45.0) / 90.0).coerceIn(0.0, 1.0) }
                if (h.phase == ThermalPhase.SWITCHING) base * 0.5 else base
            }
            good += safe
            total += 1.0
            t += 0.5
        }
        val bearing = ThermalWindEngine.normalize(Math.toDegrees(atan2(east, north)))
        return (if (total == 0.0) 0.5 else good / total) to bearing
    }

    /** Feature at a grid node, or null if it's plain hillside / flat. */
    internal fun classify(grid: ElevationGrid, r: Int, c: Int, prevailingFromDeg: Double?): StandFeature? {
        val z = grid.at(r, c)
        val n = grid.at(r - 1, c)
        val s = grid.at(r + 1, c)
        val e = grid.at(r, c + 1)
        val w = grid.at(r, c - 1)
        val ne = grid.at(r - 1, c + 1)
        val sw = grid.at(r + 1, c - 1)
        val nw = grid.at(r - 1, c - 1)
        val se = grid.at(r + 1, c + 1)
        val spacing = grid.spacingMeters
        val tol = 1.5 // meters; below DEM noise

        // Saddle: higher on both ends of one axis, lower on both ends of the perpendicular axis.
        val pairs = listOf((n to s) to (e to w), (ne to sw) to (nw to se))
        for ((up, down) in pairs) {
            if (up.first > z + tol && up.second > z + tol && down.first < z - tol && down.second < z - tol) return StandFeature.SADDLE
            if (down.first > z + tol && down.second > z + tol && up.first < z - tol && up.second < z - tol) return StandFeature.SADDLE
        }

        val (uphill, slope) = ThermalMapGeometry.slopeFromSamples(n, s, e, w, spacing)
        // Topographic position: center vs the ring two cells out.
        val ring = mutableListOf<Double>()
        for (dr in -2..2) for (dc in -2..2) if (abs(dr) == 2 || abs(dc) == 2) ring += grid.at(r + dr, c + dc)
        val tpi = z - ring.average()

        // Bench: nearly flat here but steep just above and below along the fall line.
        if (slope < 7.0) {
            val (u2, upSlope) = steepnessAround(grid, r, c, spacing)
            if (u2 != null && upSlope > 11.0 && tpi > -6.0 && tpi < 6.0) return StandFeature.BENCH
        }
        // Ridge point: sticks up above its surroundings.
        if (tpi > 8.0 && slope in 3.0..25.0) {
            // Leeward upper slope of a ridge doubles as bedding when the wind is known.
            if (prevailingFromDeg != null && uphill != null) {
                val aspect = ThermalWindEngine.normalize(uphill + 180.0)
                if (angleDiff(aspect, prevailingFromDeg) > 120.0) return StandFeature.BEDDING
            }
            return StandFeature.RIDGE_POINT
        }
        // Draw head: a hollow (below surroundings) where the ground keeps rising beyond it.
        if (tpi < -6.0 && slope in 4.0..30.0) return StandFeature.DRAW_HEAD
        return null
    }

    /** Bearing of a saddle's low side (deer cross along this axis), from which neighbor pair is lower. */
    internal fun saddleLowAxis(grid: ElevationGrid, r: Int, c: Int): Double? {
        val z = grid.at(r, c)
        val axes = listOf(
            0.0 to ((grid.at(r - 1, c) + grid.at(r + 1, c)) / 2.0),
            90.0 to ((grid.at(r, c + 1) + grid.at(r, c - 1)) / 2.0),
            45.0 to ((grid.at(r - 1, c + 1) + grid.at(r + 1, c - 1)) / 2.0),
            135.0 to ((grid.at(r + 1, c + 1) + grid.at(r - 1, c - 1)) / 2.0),
        )
        val lowest = axes.minByOrNull { it.second } ?: return null
        return if (lowest.second < z) lowest.first else null
    }

    /** Max slope in the 5×5 neighborhood, and its uphill bearing. */
    private fun steepnessAround(grid: ElevationGrid, r: Int, c: Int, spacing: Double): Pair<Double?, Double> {
        var best = 0.0
        var bestUp: Double? = null
        for (dr in -2..2) {
            for (dc in -2..2) {
                if (dr == 0 && dc == 0) continue
                val rr = r + dr
                val cc = c + dc
                val (u, sl) = ThermalMapGeometry.slopeFromSamples(
                    grid.at(rr - 1, cc), grid.at(rr + 1, cc), grid.at(rr, cc + 1), grid.at(rr, cc - 1), spacing,
                )
                if (sl > best) {
                    best = sl
                    bestUp = u
                }
            }
        }
        return bestUp to best
    }

    /** Offline picks when no AI is configured (or it fails): the top spots with plain-language notes. */
    fun localPicks(candidates: List<StandCandidate>, maxStands: Int = 4): List<StandPick> {
        val stands = candidates.filter { it.feature != StandFeature.BEDDING }.take(maxStands)
        val areas = candidates.filter { it.feature == StandFeature.BEDDING }.take(2)
        var rank = 0
        return stands.map { c ->
            rank++
            val window = c.bestWindow
            val deerFrom = if (window == HuntWindow.MORNING) c.morningDeerFrom else c.eveningDeerFrom
            StandPick(
                rank = rank,
                latitude = c.latitude,
                longitude = c.longitude,
                kind = StandPick.Kind.STAND,
                title = "${c.feature.label} stand",
                bestTime = window.label,
                wind = "Best with scent going ${ThermalWindEngine.compass(c.bestScentToward)}" +
                    (deerFrom?.let { " — away from deer coming from the ${ThermalWindEngine.compass(it)}" } ?: ""),
                why = "A ${c.feature.blurb}. %.0f ft, %.0f° slope.".format(c.elevationFeet, c.slopeDegrees),
                entry = deerFrom?.let { "Walk in from the ${ThermalWindEngine.compass(it + 180.0)} side so you never cross where deer come from." }
                    ?: "Approach on the downwind side.",
                scentToward = c.bestScentToward,
                candidateId = c.id,
            )
        } + areas.map { c ->
            rank++
            StandPick(
                rank = rank,
                latitude = c.latitude,
                longitude = c.longitude,
                kind = StandPick.Kind.AREA,
                title = c.feature.label,
                bestTime = "Hunt the edges, don't walk in",
                wind = "Keep your scent off it",
                why = "A ${c.feature.blurb}.",
                entry = "Set up on travel routes leading to it rather than inside it.",
                scentToward = null,
                candidateId = c.id,
            )
        }
    }

    fun angleDiff(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        return hypot(dLat, dLon) * 6_371_008.8
    }

    // ── Satellite image geometry (Web Mercator) for the AI's picture of the property ──

    /** Zoom level that makes [widthPx] pixels span [spanMeters] at [lat] (512-px Mapbox tiles). */
    fun zoomForSpan(lat: Double, spanMeters: Double, widthPx: Int): Double {
        val metersPerPx = spanMeters / widthPx
        return ln(40_075_016.686 * cos(Math.toRadians(lat)) / (512.0 * metersPerPx)) / ln(2.0)
    }

    /** Converts an image position (0..100 % from left / top) back to (lat, lon). */
    fun imagePercentToLatLon(
        xPercent: Double,
        yPercent: Double,
        centerLat: Double,
        centerLon: Double,
        zoom: Double,
        widthPx: Int,
        heightPx: Int,
    ): Pair<Double, Double> {
        val worldPx = 512.0 * 2.0.pow(zoom)
        val cx = (centerLon + 180.0) / 360.0 * worldPx
        val sinLat = sin(Math.toRadians(centerLat))
        val cy = (0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * PI)) * worldPx
        val px = cx + (xPercent / 100.0 - 0.5) * widthPx
        val py = cy + (yPercent / 100.0 - 0.5) * heightPx
        val lon = px / worldPx * 360.0 - 180.0
        val n = PI - 2.0 * PI * py / worldPx
        val lat = Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
        return lat to lon
    }

    /** (x%, y%) of a lat/lon on the same image — inverse of [imagePercentToLatLon]. */
    fun latLonToImagePercent(
        lat: Double,
        lon: Double,
        centerLat: Double,
        centerLon: Double,
        zoom: Double,
        widthPx: Int,
        heightPx: Int,
    ): Pair<Double, Double> {
        val worldPx = 512.0 * 2.0.pow(zoom)
        fun x(l: Double) = (l + 180.0) / 360.0 * worldPx
        fun y(la: Double): Double {
            val sl = sin(Math.toRadians(la))
            return (0.5 - ln((1 + sl) / (1 - sl)) / (4 * PI)) * worldPx
        }
        val xp = ((x(lon) - x(centerLon)) / widthPx + 0.5) * 100.0
        val yp = ((y(lat) - y(centerLat)) / heightPx + 0.5) * 100.0
        return xp to yp
    }
}
