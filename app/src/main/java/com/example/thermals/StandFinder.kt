package com.example.thermals

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.Locale

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
    /**
     * False where this cell has no elevation (outside a LiDAR footprint). Those cells are
     * not scored. Defaults to fully covered, which is the Open-Meteo grid.
     */
    val covered: BooleanArray = BooleanArray(size * size) { true },
) {
    init {
        require(size >= 5 && meters.size == size * size) { "Grid must be size×size (≥5)" }
        require(covered.size == size * size) { "Coverage mask must be size×size" }
    }

    fun isCovered(r: Int, c: Int): Boolean {
        if (r !in 0 until size || c !in 0 until size) return false
        return covered[r * size + c]
    }

    /** True when [r],[c] and the [radius] ring around it all have elevations. */
    fun coversNeighborhood(r: Int, c: Int, radius: Int = 2): Boolean {
        for (dr in -radius..radius) {
            for (dc in -radius..radius) {
                if (!isCovered(r + dr, c + dc)) return false
            }
        }
        return true
    }

    fun at(r: Int, c: Int): Double = meters[r.coerceIn(0, size - 1) * size + c.coerceIn(0, size - 1)]

    /** Bilinear elevation at ([lat], [lon]), or null outside the grid. */
    fun elevationAt(lat: Double, lon: Double): Double? {
        val half = (size - 1) / 2.0
        val north = (lat - centerLat) * 111_320.0
        val east = (lon - centerLon) * 111_320.0 * cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)
        val r = half - north / spacingMeters
        val c = half + east / spacingMeters
        if (r < 0 || c < 0 || r > size - 1 || c > size - 1) return null
        val r0 = r.toInt().coerceAtMost(size - 2)
        val c0 = c.toInt().coerceAtMost(size - 2)
        // Uncovered cells are stored as NaN, never 0 m. Don't interpolate a hole into a fake cliff.
        if (!isCovered(r0, c0) || !isCovered(r0, c0 + 1) || !isCovered(r0 + 1, c0) || !isCovered(r0 + 1, c0 + 1)) {
            return null
        }
        val fr = r - r0
        val fc = c - c0
        val top = at(r0, c0) * (1 - fc) + at(r0, c0 + 1) * fc
        val bottom = at(r0 + 1, c0) * (1 - fc) + at(r0 + 1, c0 + 1) * fc
        return top * (1 - fr) + bottom * fr
    }

    /** Uphill bearing and slope at ([lat], [lon]) for the thermal model, or null outside the grid. */
    fun terrainAt(lat: Double, lon: Double): SiteTerrain? {
        val step = spacingMeters
        val (nLat, nLon) = ThermalMapGeometry.destination(lat, lon, 0.0, step)
        val (sLat, sLon) = ThermalMapGeometry.destination(lat, lon, 180.0, step)
        val (eLat, eLon) = ThermalMapGeometry.destination(lat, lon, 90.0, step)
        val (wLat, wLon) = ThermalMapGeometry.destination(lat, lon, 270.0, step)
        val zC = elevationAt(lat, lon) ?: return null
        val zN = elevationAt(nLat, nLon) ?: return null
        val zS = elevationAt(sLat, sLon) ?: return null
        val zE = elevationAt(eLat, eLon) ?: return null
        val zW = elevationAt(wLat, wLon) ?: return null
        return ThermalMapGeometry.terrainFromNeighborhood(zC, zN, zS, zE, zW, step)
    }

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
    /** Null when that sit is calm — no confident bearing. */
    val morningScentToward: Double?,
    val eveningScentToward: Double?,
    /** Where deer are expected to come from during each sit. */
    val morningDeerFrom: Double?,
    val eveningDeerFrom: Double?,
    /** 0..1 scent-vs-approach alignment for each sit. 1 = strong (away, or a saddle crosswind). */
    val morningAlign: Double,
    val eveningAlign: Double,
) {
    val bestWindow: HuntWindow get() = if (morningScore >= eveningScore) HuntWindow.MORNING else HuntWindow.EVENING
    val bestScore: Double get() = max(morningScore, eveningScore)
    val bestScentToward: Double? get() = if (bestWindow == HuntWindow.MORNING) morningScentToward else eveningScentToward
    val bestAlign: Double get() = if (bestWindow == HuntWindow.MORNING) morningAlign else eveningAlign
    val bestDeerFrom: Double? get() = if (bestWindow == HuntWindow.MORNING) morningDeerFrom else eveningDeerFrom
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

    /** Search reach from the set location: 750 yards. */
    const val SEARCH_RADIUS_M = 750 * 0.9144

    /** 28 × 50 m = 1,400 m across — a square that just covers the 750-yard circle. */
    const val GRID_SIZE = 29

    const val GRID_SPACING_M = 50.0

    /** Picks are kept at least this far apart (~400 ft). */
    const val MIN_PICK_SPACING_M = 120.0

    /** Below this alignment the sit is a poor recommendation and is dropped on device. */
    const val MIN_RECOMMEND_ALIGN = 0.5

    /** One-way "away" (~135° from the approach) or a saddle crosswind. */
    const val STRONG_AWAY_ALIGN = 0.999

    /** Anything farther than 750 yards from the search center is ignored. */
    const val MAX_PICK_DISTANCE_M = SEARCH_RADIUS_M

    /** Stay this far from mapped roads (~130 ft) and buildings (~200 ft). */
    const val ROAD_BUFFER_M = 40.0
    const val BUILDING_BUFFER_M = 60.0

    /**
     * Finds stand-worthy terrain in the grid and scores each spot for the morning and evening sits.
     *
     * Deer rules of thumb used: in the morning deer move from low feeding ground up toward bedding,
     * in the evening from bedding back down. A spot scores well for a sit when the scent drift
     * (thermal + wind at that spot's own slope) points away from where deer come from.
     */
    fun findCandidates(
        grid: ElevationGrid,
        field: ThermalWindEngine.FlowField?,
        prevailingFromDeg: Double?,
        maxCandidates: Int = 18,
        /** Mapped roads and buildings to stay away from (empty when unavailable). */
        obstacles: MappedObstacles = MappedObstacles.NONE,
    ): List<StandCandidate> {
        val raw = mutableListOf<Triple<Int, Int, StandFeature>>()
        val n = grid.size
        val s = grid.spacingMeters
        for (r in 2 until n - 2) {
            for (c in 2 until n - 2) {
                // Radius first, then features, so corners of the DEM square never reach the AI.
                if (!grid.coversNeighborhood(r, c)) continue
                val (lat, lon) = grid.latLon(r, c)
                if (distanceMeters(grid.centerLat, grid.centerLon, lat, lon) > MAX_PICK_DISTANCE_M) continue
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
            val terrain = ThermalMapGeometry.terrainFromNeighborhood(
                center = grid.at(r, c),
                north = grid.at(r - 1, c),
                south = grid.at(r + 1, c),
                east = grid.at(r, c + 1),
                west = grid.at(r, c - 1),
                spacingMeters = s,
            )
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
                morningScore = sitScore(feature, am.align),
                eveningScore = sitScore(feature, pm.align),
                morningScentToward = am.bearing,
                eveningScentToward = pm.bearing,
                morningDeerFrom = morningFrom,
                eveningDeerFrom = eveningFrom,
                morningAlign = am.align,
                eveningAlign = pm.align,
            )
        }.sortedByDescending { it.bestScore }

        // Keep spots at least ~250 m apart so the picks spread over the property.
        val kept = mutableListOf<StandCandidate>()
        for (cand in scored) {
            if (kept.size >= maxCandidates) break
            if (obstacles.tooClose(cand.latitude, cand.longitude)) continue
            if (kept.none { distanceMeters(it.latitude, it.longitude, cand.latitude, cand.longitude) < MIN_PICK_SPACING_M }) kept += cand
        }
        return kept.mapIndexed { i, cand -> cand.copy(id = i + 1) }
    }

    private data class WindowRead(val align: Double, val bearing: Double?)

    /**
     * Mean scent-vs-approach alignment over the sit, plus the speed-weighted scent bearing.
     * Bearing is null when the averaged drift is calm so we don't report atan2(0, 0) as north.
     * Without a forecast, alignment is a neutral 0.5 and there is no bearing.
     */
    private fun windowScore(
        field: ThermalWindEngine.FlowField?,
        terrain: SiteTerrain,
        window: HuntWindow,
        deerFrom: List<Double>,
    ): WindowRead {
        if (field == null) return WindowRead(0.5, null)
        var good = 0.0
        var total = 0.0
        var east = 0.0
        var north = 0.0
        var t = window.startHour
        while (t <= window.endHour) {
            val h = field.at(t, terrain)
            if (h.scentSpeedMph >= ThermalWindEngine.CALM_SCENT_MPH) {
                val rad = Math.toRadians(h.scentTowardDegrees)
                east += sin(rad) * h.scentSpeedMph
                north += cos(rad) * h.scentSpeedMph
            }
            val safe = if (h.scentSpeedMph < ThermalWindEngine.CALM_SCENT_MPH) {
                0.5
            } else {
                val base = approachAlignment(h.scentTowardDegrees, deerFrom)
                if (h.phase == ThermalPhase.SWITCHING) base * 0.5 else base
            }
            good += safe
            total += 1.0
            t += 0.5
        }
        val samples = total.coerceAtLeast(1.0)
        val bearing = ThermalWindEngine.bearingOrNull(east / samples, north / samples)
        return WindowRead(if (total == 0.0) 0.5 else good / total, bearing)
    }

    /**
     * How well scent stays off the deer, 0..1.
     *
     * One approach (slope, draw, bench): deer come from one direction. Alignment reaches 1
     * once scent is ~135° or more from that approach ("away") and is 0 at 45° or less.
     *
     * Two opposite approaches (a saddle): deer use both ends of the low axis, so scent cannot
     * be away from both. Straight down the trail scores 0. A crosswind (~90° to the axis) is
     * the best possible sit and scores 1, so a well-set saddle is not capped under a bench.
     */
    internal fun approachAlignment(scentToward: Double, deerFrom: List<Double>): Double {
        if (deerFrom.isEmpty()) return 0.5
        if (deerFrom.size >= 2) {
            val cross = deerFrom.minOf { angleDiff(scentToward, it) }
            return (cross / 90.0).coerceIn(0.0, 1.0)
        }
        val diff = angleDiff(scentToward, deerFrom.first())
        return ((diff - 45.0) / 90.0).coerceIn(0.0, 1.0)
    }

    /** Feature weight times alignment. A full-credit saddle (1.0) beats a full-credit bench (0.85). */
    internal fun sitScore(feature: StandFeature, align: Double): Double =
        feature.baseScore * (0.35 + 0.65 * align.coerceIn(0.0, 1.0))

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
        val k = spacing / 100.0 // relief thresholds below were tuned on a 100 m grid
        val tol = 1.0 // meters; at DEM noise

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

        // Bench: flat here, with the fall line steep both uphill and downhill — not one cliff nearby.
        // A level shelf has no local uphill, so the fall line comes from the ground two cells out.
        if (slope < 7.0 && tpi > -6.0 * k && tpi < 6.0 * k) {
            val fall = uphill ?: fallLineBearing(grid, r, c)
            if (fall != null) {
                val (dr, dc) = gridStep(fall)
                val upSlope = slopeAt(grid, r + 2 * dr, c + 2 * dc)
                val downSlope = slopeAt(grid, r - 2 * dr, c - 2 * dc)
                if (upSlope > 11.0 && downSlope > 11.0) return StandFeature.BENCH
            }
        }
        // Ridge point: sticks up above its surroundings.
        if (tpi > 8.0 * k && slope in 3.0..25.0) {
            // Leeward upper slope of a ridge doubles as bedding when the wind is known.
            if (prevailingFromDeg != null && uphill != null) {
                val aspect = ThermalWindEngine.normalize(uphill + 180.0)
                if (angleDiff(aspect, prevailingFromDeg) > 120.0) return StandFeature.BEDDING
            }
            return StandFeature.RIDGE_POINT
        }
        // Draw head: a hollow where the slope keeps rising uphill and falling downhill.
        // A bowl bottom (no continued rise) is not a draw head.
        if (tpi < -6.0 * k && slope in 4.0..30.0 && uphill != null) {
            val (dr, dc) = gridStep(uphill)
            val upR = r + 2 * dr
            val upC = c + 2 * dc
            val downR = r - 2 * dr
            val downC = c - 2 * dc
            if (upR in 0 until grid.size && upC in 0 until grid.size &&
                downR in 0 until grid.size && downC in 0 until grid.size &&
                grid.at(upR, upC) > z + 1.5 && grid.at(downR, downC) < z - 1.0
            ) {
                return StandFeature.DRAW_HEAD
            }
        }
        return null
    }

    /**
     * Uphill bearing from the ground two cells out, when the shelf itself is level.
     * Null when that ring is flat too — there is no fall line to walk.
     */
    private fun fallLineBearing(grid: ElevationGrid, r: Int, c: Int): Double? {
        if (r < 2 || c < 2 || r > grid.size - 3 || c > grid.size - 3) return null
        val northSouth = grid.at(r - 2, c) - grid.at(r + 2, c)
        val eastWest = grid.at(r, c + 2) - grid.at(r, c - 2)
        if (maxOf(abs(northSouth), abs(eastWest)) < 2.0) return null
        return if (abs(northSouth) >= abs(eastWest)) {
            if (northSouth >= 0.0) 0.0 else 180.0
        } else {
            if (eastWest >= 0.0) 90.0 else 270.0
        }
    }

    /** One grid step along [bearing] (row increases south, column increases east). */
    private fun gridStep(bearing: Double): Pair<Int, Int> {
        val rad = Math.toRadians(bearing)
        val dc = kotlin.math.round(sin(rad)).toInt().coerceIn(-1, 1)
        val dr = kotlin.math.round(-cos(rad)).toInt().coerceIn(-1, 1)
        return if (dr == 0 && dc == 0) -1 to 0 else dr to dc
    }

    private fun slopeAt(grid: ElevationGrid, r: Int, c: Int): Double {
        if (r !in 1 until grid.size - 1 || c !in 1 until grid.size - 1) return 0.0
        return ThermalMapGeometry.slopeFromSamples(
            grid.at(r - 1, c), grid.at(r + 1, c), grid.at(r, c + 1), grid.at(r, c - 1), grid.spacingMeters,
        ).second
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

    /** Offline picks when no AI is configured (or it fails): the top spots with plain-language notes. */
    fun localPicks(candidates: List<StandCandidate>, maxStands: Int = 4): List<StandPick> {
        // Poor sits (scent toward the deer, or no crosswind on a saddle) are not recommendations.
        val stands = candidates
            .filter { it.feature != StandFeature.BEDDING && it.bestAlign >= MIN_RECOMMEND_ALIGN }
            .take(maxStands)
        val areas = candidates.filter { it.feature == StandFeature.BEDDING }.take(2)
        var rank = 0
        return stands.map { c ->
            rank++
            val window = c.bestWindow
            val deerFrom = c.bestDeerFrom
            val scent = c.bestScentToward
            val strong = c.bestAlign >= STRONG_AWAY_ALIGN && scent != null && deerFrom != null
            StandPick(
                rank = rank,
                latitude = c.latitude,
                longitude = c.longitude,
                kind = StandPick.Kind.STAND,
                title = "${c.feature.label} stand",
                bestTime = window.label,
                wind = windCopy(c),
                why = "A ${c.feature.blurb}. %,.0f ft elevation, %.0f° slope.".format(c.elevationFeet, c.slopeDegrees),
                entry = if (strong && deerFrom != null) {
                    "Walk in from the ${ThermalWindEngine.compass(deerFrom + 180.0)} side so you never cross where deer come from."
                } else {
                    "Scent is not cleanly away from the deer. Don't treat this as a setup until the wind changes."
                },
                scentToward = scent,
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

    /** Honest wind line. "Away from deer" only when [bestAlign] is actually strong. */
    internal fun windCopy(c: StandCandidate): String {
        val scent = c.bestScentToward
        val deerFrom = c.bestDeerFrom
        if (scent == null) return "Air is nearly still, so this sit has no reliable scent direction."
        val toward = ThermalWindEngine.compass(scent)
        if (c.bestAlign >= STRONG_AWAY_ALIGN && deerFrom != null) {
            return "Scent goes $toward — away from deer coming from the ${ThermalWindEngine.compass(deerFrom)}."
        }
        if (c.bestAlign >= MIN_RECOMMEND_ALIGN) {
            return "Marginal: scent goes $toward. Not cleanly away from the deer — not a confident setup."
        }
        return "Poor sit: scent goes $toward, toward the deer. Not a recommendation."
    }

    internal fun sitHonesty(align: Double): String = when {
        align >= STRONG_AWAY_ALIGN -> "away from deer"
        align >= MIN_RECOMMEND_ALIGN -> "marginal, not cleanly away"
        else -> "POOR scent toward deer — do not recommend"
    }

    /** US-style distance: feet under a tenth of a mile, otherwise miles. */
    fun formatDistance(meters: Double): String {
        val feet = meters * 3.28084
        return if (feet < 528.0) "%,d ft".format(Locale.US, (feet / 10.0).roundToInt() * 10) else "%.2f mi".format(Locale.US, feet / 5280.0)
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

/**
 * Roads (polylines) and building footprints (polygons) from OpenStreetMap. Distances are
 * measured to the actual line segments / footprint edges, and a point inside a footprint
 * counts as 0 m away, so long walls and big barns can't hide between their corners.
 */
class MappedObstacles(
    val roads: List<List<Pair<Double, Double>>>,
    val buildings: List<List<Pair<Double, Double>>>,
) {
    fun tooClose(lat: Double, lon: Double): Boolean =
        (nearestRoadMeters(lat, lon) ?: Double.MAX_VALUE) < StandFinder.ROAD_BUFFER_M ||
            (nearestBuildingMeters(lat, lon) ?: Double.MAX_VALUE) < StandFinder.BUILDING_BUFFER_M

    fun nearestRoadMeters(lat: Double, lon: Double): Double? =
        roads.minOfOrNull { polylineDistance(lat, lon, it, closed = false) }

    fun nearestBuildingMeters(lat: Double, lon: Double): Double? =
        buildings.minOfOrNull { if (contains(lat, lon, it)) 0.0 else polylineDistance(lat, lon, it, closed = true) }

    /** Feet to the nearest road / building, for the AI's context (null when none mapped). */
    fun nearestRoadFeet(lat: Double, lon: Double): Double? = nearestRoadMeters(lat, lon)?.let { it * FEET_PER_METER }

    fun nearestBuildingFeet(lat: Double, lon: Double): Double? = nearestBuildingMeters(lat, lon)?.let { it * FEET_PER_METER }

    val isEmpty: Boolean get() = roads.isEmpty() && buildings.isEmpty()

    companion object {
        val NONE = MappedObstacles(emptyList(), emptyList())
        private const val FEET_PER_METER = 3.28084
        private const val M_PER_DEG_LAT = 111_320.0

        /** Local east/north metres of [p] relative to ([lat], [lon]) — exact enough over a mile. */
        private fun project(lat: Double, lon: Double, p: Pair<Double, Double>): Pair<Double, Double> {
            val x = (p.second - lon) * M_PER_DEG_LAT * kotlin.math.cos(Math.toRadians(lat))
            val y = (p.first - lat) * M_PER_DEG_LAT
            return x to y
        }

        /** Distance from the origin to segment a-b, in metres. */
        private fun segmentDistance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val dx = b.first - a.first
            val dy = b.second - a.second
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-a.first * dx - a.second * dy) / len2).coerceIn(0.0, 1.0)
            return kotlin.math.hypot(a.first + t * dx, a.second + t * dy)
        }

        fun polylineDistance(lat: Double, lon: Double, line: List<Pair<Double, Double>>, closed: Boolean): Double {
            if (line.isEmpty()) return Double.MAX_VALUE
            val pts = line.map { project(lat, lon, it) }
            if (pts.size == 1) return kotlin.math.hypot(pts[0].first, pts[0].second)
            var best = Double.MAX_VALUE
            for (i in 1 until pts.size) best = minOf(best, segmentDistance(pts[i - 1], pts[i]))
            if (closed && pts.first() != pts.last()) best = minOf(best, segmentDistance(pts.last(), pts.first()))
            return best
        }

        /** Ray-casting point-in-polygon test. */
        fun contains(lat: Double, lon: Double, ring: List<Pair<Double, Double>>): Boolean {
            if (ring.size < 3) return false
            val pts = ring.map { project(lat, lon, it) }
            var inside = false
            var j = pts.size - 1
            for (i in pts.indices) {
                val (xi, yi) = pts[i]
                val (xj, yj) = pts[j]
                if ((yi > 0) != (yj > 0) && 0 < (xj - xi) * (0 - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }
    }
}
