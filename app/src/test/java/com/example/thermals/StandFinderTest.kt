package com.example.thermals

import com.example.geospatial.GeoSpatialLibrary
import kotlin.math.abs
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StandFinderTest {

    private val size = StandFinder.GRID_SIZE
    private val spacing = StandFinder.GRID_SPACING_M

    /** East-west ridge with a dip (saddle) in the middle; ground falls away north and south. */
    private fun ridgeWithSaddle(): ElevationGrid {
        val half = (size - 1) / 2
        val m = DoubleArray(size * size) { i ->
            val r = i / size
            val c = i % size
            val crossRidge = abs(r - half) * 12.0 // falls 12 m per cell away from the ridge line
            val dip = 30.0 * cos((c - half) / 2.0).coerceAtLeast(0.0) // saddle at the center column
            400.0 - crossRidge - dip
        }
        return ElevationGrid(42.75, -75.5, size, spacing, m)
    }

    private fun forecastInputs(): ThermalInputs {
        val curve = DiurnalTemperatureCurve.build(
            listOf(DiurnalTemperatureCurve.Reading(6.0, 32.0), DiurnalTemperatureCurve.Reading(12.0, 66.0)),
            sunriseHour = 7.4,
        )
        return ThermalInputs(42.75, -75.5, 299, -240, curve.mapIndexed { h, t -> HourlyWeather(h, t, 4.0, 315.0, 10.0) }, SiteTerrain(TerrainSetting.SLOPE, 0.0, 12.0))
    }

    @Test
    fun findsTheSaddleOnARidge() {
        val grid = ridgeWithSaddle()
        val half = (size - 1) / 2
        assertEquals(StandFeature.SADDLE, StandFinder.classify(grid, half, half, null))
    }

    @Test
    fun candidatesAreScoredSpreadOutAndNumbered() {
        val grid = ridgeWithSaddle()
        val field = ThermalWindEngine.flowField(forecastInputs())
        val candidates = StandFinder.findCandidates(grid, field, prevailingFromDeg = 315.0)
        assertTrue("expected some candidates", candidates.isNotEmpty())
        assertEquals((1..candidates.size).toList(), candidates.map { it.id })
        // Without a forecast every spot is judged on terrain alone, and the saddle leads.
        assertEquals(StandFeature.SADDLE, StandFinder.findCandidates(grid, null, null).first().feature)
        for (i in candidates.indices) for (j in i + 1 until candidates.size) {
            val d = StandFinder.distanceMeters(candidates[i].latitude, candidates[i].longitude, candidates[j].latitude, candidates[j].longitude)
            assertTrue("candidates $i,$j only ${d}m apart", d >= StandFinder.MIN_PICK_SPACING_M)
        }
        candidates.forEach { assertTrue(it.bestScore in 0.0..1.0) }
        assertTrue(candidates.zipWithNext().all { (a, b) -> a.bestScore >= b.bestScore })
    }

    @Test
    fun saddleScoresOnBothLowSides() {
        val grid = ridgeWithSaddle()
        val half = (size - 1) / 2
        assertEquals(0.0, StandFinder.saddleLowAxis(grid, half, half)!!, 0.0) // ground falls N and S
    }

    @Test
    fun flatGroundHasNoStandFeatures() {
        val flat = ElevationGrid(42.0, -75.0, size, spacing, DoubleArray(size * size) { 300.0 })
        assertTrue(StandFinder.findCandidates(flat, null, null).isEmpty())
    }

    @Test
    fun localPicksExplainThemselves() {
        // West wind blows scent east: a crosswind on the north-south saddle, which is a full-credit sit.
        val base = forecastInputs()
        val inputs = base.copy(hourly = base.hourly.map { it.copy(windSpeedMph = 12.0, windFromDegrees = 270.0) })
        val picks = StandFinder.localPicks(StandFinder.findCandidates(ridgeWithSaddle(), ThermalWindEngine.flowField(inputs), 270.0))
        assertTrue(picks.isNotEmpty())
        val first = picks.first()
        assertEquals(1, first.rank)
        assertTrue(first.why.isNotBlank() && first.entry.isNotBlank() && first.wind.isNotBlank())
        assertNotNull(first.scentToward)
        assertTrue(first.wind, first.wind.contains("away from deer"))
    }

    @Test
    fun imagePercentRoundTrips() {
        val zoom = StandFinder.zoomForSpan(42.75, 1_800.0, 640)
        assertTrue(zoom in 14.0..17.0)
        val (cLat, cLon) = StandFinder.imagePercentToLatLon(50.0, 50.0, 42.75, -75.5, zoom, 640, 640)
        assertEquals(42.75, cLat, 1e-6)
        assertEquals(-75.5, cLon, 1e-6)
        val (lat, lon) = StandFinder.imagePercentToLatLon(80.0, 20.0, 42.75, -75.5, zoom, 640, 640)
        assertTrue("80% right is east", lon > -75.5)
        assertTrue("20% down is north", lat > 42.75)
        val (x, y) = StandFinder.latLonToImagePercent(lat, lon, 42.75, -75.5, zoom, 640, 640)
        assertEquals(80.0, x, 1e-6)
        assertEquals(20.0, y, 1e-6)
    }

    @Test
    fun parsesAiPicksFromCandidatesAndImageSpots() {
        val grid = ridgeWithSaddle()
        val candidates = StandFinder.findCandidates(grid, ThermalWindEngine.flowField(forecastInputs()), 315.0)
        val image = StandFinderService.SatelliteImage(ByteArray(0), "image/jpeg", 42.75, -75.5, StandFinder.zoomForSpan(42.75, 1_800.0, 640), 640, 640)
        val text = """
            Here you go:
            [{"rank":2,"kind":"stand","x":60,"y":40,"title":"Field corner","best_time":"Evening sit","wind":"W","why":"Inside corner","entry":"From the road"},
             {"rank":1,"kind":"stand","candidate":${candidates.first().id},"title":"Saddle","best_time":"Morning sit","wind":"NW","why":"Funnel","entry":"From the south"},
             {"rank":3,"kind":"area","x":30,"y":30,"title":"Bedding","why":"Thick cover"},
             {"rank":4,"kind":"stand","x":500,"y":50,"title":"Off image"}]
        """.trimIndent()
        val picks = StandFinderService.parsePicks(text, candidates, image, 42.75, -75.5)
        assertEquals(3, picks.size) // the off-image one is dropped
        assertEquals(listOf(1, 2, 3), picks.map { it.rank })
        assertEquals("Saddle", picks[0].title)
        assertEquals(candidates.first().latitude, picks[0].latitude, 1e-9)
        val morningScent = candidates.first().morningScentToward
        assertNotNull(morningScent)
        assertEquals(morningScent!!, picks[0].scentToward!!, 1e-9)
        assertEquals(StandPick.Kind.AREA, picks[2].kind)
        assertEquals(null, picks[2].scentToward)
    }

    @Test
    fun promptListsCandidatesWithImagePositions() {
        val grid = ridgeWithSaddle()
        val candidates = StandFinder.findCandidates(grid, null, null)
        val image = StandFinderService.SatelliteImage(ByteArray(0), "image/jpeg", 42.75, -75.5, StandFinder.zoomForSpan(42.75, 1_800.0, 640), 640, 640)
        val (system, user) = StandFinderService.buildPrompt(candidates, null, image, 42.75, -75.5, "Back 40", "Mon Oct 26")
        assertTrue(system.contains("JSON array"))
        assertTrue(user.contains("Back 40"))
        assertTrue(user.contains("Saddle"))
        assertTrue(user.lines().any { it.startsWith("1 |") })
    }

    @Test
    fun searchReaches750YardsFromTheCenter() {
        val yards = StandFinder.SEARCH_RADIUS_M / 0.9144
        assertEquals(750.0, yards, 0.5)
        // The elevation square covers the whole circle.
        val halfAcrossM = StandFinder.GRID_SPACING_M * (StandFinder.GRID_SIZE - 1) / 2.0
        assertTrue(halfAcrossM >= StandFinder.SEARCH_RADIUS_M)
        assertEquals(StandFinder.SEARCH_RADIUS_M, StandFinder.MAX_PICK_DISTANCE_M, 0.0)
    }

    @Test
    fun picksStayOffMappedRoadsAndHouses() {
        val grid = ridgeWithSaddle()
        val free = StandFinder.findCandidates(grid, null, null)
        val saddle = free.first { it.feature == StandFeature.SADDLE }
        // A road running right through the saddle removes it.
        val road = listOf(listOf((saddle.latitude to saddle.longitude - 0.002), (saddle.latitude to saddle.longitude + 0.002)))
        val blocked = StandFinder.findCandidates(grid, null, null, obstacles = MappedObstacles(road, emptyList()))
        assertTrue(blocked.none { StandFinder.distanceMeters(it.latitude, it.longitude, saddle.latitude, saddle.longitude) < 1.0 })
        assertTrue(blocked.all { !MappedObstacles(road, emptyList()).tooClose(it.latitude, it.longitude) })
    }

    @Test
    fun parsesOverpassRoadsButNotFootpaths() {
        val body = """
            {"elements":[
              {"type":"way","tags":{"highway":"residential"},"geometry":[{"lat":42.75,"lon":-75.50},{"lat":42.75,"lon":-75.499}]},
              {"type":"way","tags":{"highway":"footway"},"geometry":[{"lat":42.76,"lon":-75.50},{"lat":42.76,"lon":-75.499}]},
              {"type":"way","tags":{"building":"house"},"geometry":[{"lat":42.751,"lon":-75.501},{"lat":42.7511,"lon":-75.501}]}
            ]}
        """.trimIndent()
        val obstacles = StandFinderService.parseObstacles(body)
        assertEquals(1, obstacles.roads.size)
        assertEquals(1, obstacles.buildings.size)
        assertTrue(obstacles.tooClose(42.75, -75.4995))
        assertTrue(!obstacles.tooClose(42.76, -75.4995)) // footpath ignored
    }

    @Test
    fun distancesAreInFeetAndMiles() {
        assertEquals("330 ft", StandFinder.formatDistance(100.0))
        assertEquals("0.50 mi", StandFinder.formatDistance(804.67))
    }

    /** Square footprint [sideM] wide centred on ([lat], [lon]). */
    private fun square(lat: Double, lon: Double, sideM: Double): List<Pair<Double, Double>> {
        val dLat = sideM / 2 / 111_320.0
        val dLon = sideM / 2 / (111_320.0 * cos(Math.toRadians(lat)))
        return listOf(lat - dLat to lon - dLon, lat - dLat to lon + dLon, lat + dLat to lon + dLon, lat + dLat to lon - dLon, lat - dLat to lon - dLon)
    }

    @Test
    fun longWallsAndBigBuildingsCountAlongTheirEdgesAndInside() {
        val barn = square(42.75, -75.5, 120.0)
        val obstacles = MappedObstacles(emptyList(), listOf(barn))
        // 30 m outside the middle of a 120 m wall: > 67 m from either corner, but 30 m from the wall.
        val outside = 42.75 + (60.0 + 30.0) / 111_320.0
        assertEquals(30.0, obstacles.nearestBuildingMeters(outside, -75.5)!!, 0.5)
        assertTrue(obstacles.tooClose(outside, -75.5))
        // Dead centre of the barn is inside it.
        assertEquals(0.0, obstacles.nearestBuildingMeters(42.75, -75.5)!!, 0.0)
        assertTrue(obstacles.tooClose(42.75, -75.5))
        // Well clear of it is fine.
        assertTrue(!obstacles.tooClose(42.75 + 200.0 / 111_320.0, -75.5))
    }

    @Test
    fun roadDistanceIsToTheSegmentNotItsEnds() {
        // A 1 km straight road with no points in between; 20 m off its middle is still too close.
        val road = listOf(listOf(42.75 to -75.5 - 0.006, 42.75 to -75.5 + 0.006))
        val obstacles = MappedObstacles(road, emptyList())
        val beside = 42.75 + 20.0 / 111_320.0
        assertEquals(20.0, obstacles.nearestRoadMeters(beside, -75.5)!!, 0.5)
        assertTrue(obstacles.tooClose(beside, -75.5))
    }

    @Test
    fun aiPhotoPicksOnRoadsOrInYardsAreDropped() {
        val image = StandFinderService.SatelliteImage(ByteArray(0), "image/jpeg", 42.75, -75.5, StandFinder.zoomForSpan(42.75, 900.0, 640), 640, 640)
        val (roadLat, roadLon) = StandFinder.imagePercentToLatLon(60.0, 50.0, 42.75, -75.5, image.zoom, 640, 640)
        val (houseLat, houseLon) = StandFinder.imagePercentToLatLon(40.0, 40.0, 42.75, -75.5, image.zoom, 640, 640)
        val obstacles = MappedObstacles(
            roads = listOf(listOf(roadLat - 0.003 to roadLon, roadLat + 0.003 to roadLon)),
            buildings = listOf(square(houseLat, houseLon, 15.0)),
        )
        val text = """[
            {"rank":1,"kind":"stand","x":60,"y":50,"title":"On the road"},
            {"rank":2,"kind":"area","x":40,"y":40,"title":"In the yard"},
            {"rank":3,"kind":"stand","x":20,"y":75,"title":"Back corner"}
        ]"""
        val picks = StandFinderService.parsePicks(text, emptyList(), image, 42.75, -75.5, obstacles)
        assertEquals(listOf("Back corner"), picks.map { it.title })
        // With everything blocked nothing is returned, so the on-device picks stay up.
        val none = StandFinderService.parsePicks("""[{"rank":1,"x":60,"y":50}]""", emptyList(), image, 42.75, -75.5, obstacles)
        assertTrue(none.isEmpty())
    }

    @Test
    fun terrainHostFailuresAreNotBlamedOnTheAi() {
        val msg = StandFinderService.explainNetworkFailure("Unable to resolve host \"api.open-meteo.com\": No address associated with hostname")
        assertTrue(msg.contains("api.open-meteo.com"))
        assertTrue(!msg.contains("api.x.ai"))
        assertTrue(!msg.contains("AI"))
    }

    @Test
    fun blockedAiServersGetAPlainExplanation() {
        val msg = StandFinderService.explainNetworkFailure(
            "All cloud providers failed. Grok: Unable to resolve host \"api.x.ai\": No address associated with hostname; " +
                "Gemini: Unable to resolve host \"generativelanguage.googleapis.com\": No address associated with hostname",
        )
        assertTrue(msg.contains("blocking the AI servers"))
        assertTrue(msg.contains("api.x.ai") && msg.contains("generativelanguage.googleapis.com"))
        assertEquals("HTTP 500", StandFinderService.explainNetworkFailure("HTTP 500"))
    }

    @Test
    fun cornerCellsBeyond750YardsAreNotCandidates() {
        val n = size
        val meters = DoubleArray(n * n) { 200.0 }
        val r = 2
        val c = 2
        fun set(row: Int, col: Int, z: Double) {
            meters[row * n + col] = z
        }
        // Shoulder of a corner hill: steep enough to classify, but outside the 750-yard circle.
        set(r, c, 250.0)
        set(r - 1, c, 240.0)
        set(r + 1, c, 220.0)
        set(r, c + 1, 230.0)
        set(r, c - 1, 230.0)
        val grid = ElevationGrid(42.75, -75.5, n, spacing, meters)
        assertNotNull(StandFinder.classify(grid, r, c, null))
        val (lat, lon) = grid.latLon(r, c)
        val dist = StandFinder.distanceMeters(grid.centerLat, grid.centerLon, lat, lon)
        assertTrue("corner cell should sit outside 750 yards, was $dist m", dist > StandFinder.MAX_PICK_DISTANCE_M)
        val found = StandFinder.findCandidates(grid, null, null)
        assertTrue(found.none { StandFinder.distanceMeters(it.latitude, it.longitude, lat, lon) < 1.0 })
        found.forEach {
            val d = StandFinder.distanceMeters(grid.centerLat, grid.centerLon, it.latitude, it.longitude)
            assertTrue("candidate at $d m past 750 yards", d <= StandFinder.MAX_PICK_DISTANCE_M + 1e-6)
        }
    }

    @Test
    fun crosswindSaddleOutranksAPerfectBench() {
        // One approach: away (~135°+) scores 1, into the deer scores 0.
        assertEquals(1.0, StandFinder.approachAlignment(0.0, listOf(180.0)), 1e-9)
        assertEquals(0.0, StandFinder.approachAlignment(180.0, listOf(180.0)), 1e-9)
        // Saddle: scent down the trail is 0; a crosswind (~90° to the axis) is a full-credit sit.
        assertEquals(0.0, StandFinder.approachAlignment(0.0, listOf(0.0, 180.0)), 1e-9)
        assertEquals(1.0, StandFinder.approachAlignment(90.0, listOf(0.0, 180.0)), 1e-9)
        val saddle = StandFinder.sitScore(StandFeature.SADDLE, 1.0)
        val bench = StandFinder.sitScore(StandFeature.BENCH, 1.0)
        assertTrue("crosswind saddle $saddle should beat a perfect bench $bench", saddle > bench)
    }

    @Test
    fun localPicksOnlyCallScentAwayWhenTheSitIsStrong() {
        val away = stand(StandFeature.BENCH, align = 1.0, scent = 0.0, deerFrom = 180.0)
        val awayPicks = StandFinder.localPicks(listOf(away))
        assertEquals(1, awayPicks.size)
        assertTrue(awayPicks[0].wind.contains("away from deer"))
        assertTrue(awayPicks[0].entry.contains("Walk in from the N"))

        val marginal = stand(StandFeature.BENCH, align = 0.7, scent = 90.0, deerFrom = 180.0)
        val marginalPicks = StandFinder.localPicks(listOf(marginal))
        assertEquals(1, marginalPicks.size)
        assertTrue(marginalPicks[0].wind.contains("Marginal"))
        assertTrue(!marginalPicks[0].wind.contains("away from deer"))
        assertTrue(marginalPicks[0].entry.contains("not cleanly away"))

        val poor = stand(StandFeature.BENCH, align = 0.2, scent = 180.0, deerFrom = 180.0)
        assertTrue(StandFinder.localPicks(listOf(poor)).none { it.kind == StandPick.Kind.STAND })
        assertTrue(StandFinder.windCopy(poor).contains("Poor sit"))
        assertTrue(!StandFinder.windCopy(poor).contains("away from deer"))
    }

    @Test
    fun promptMarksPoorSitsInsteadOfCallingThemAway() {
        val poor = stand(StandFeature.BENCH, align = 0.1, scent = 180.0, deerFrom = 180.0)
        val (_, user) = StandFinderService.buildPrompt(listOf(poor), null, null, 42.0, -75.0, "Site", "Day")
        assertTrue(user.contains("POOR"))
        assertTrue(!user.contains("away from deer"))
        assertEquals("away from deer", StandFinder.sitHonesty(1.0))
        assertEquals("marginal, not cleanly away", StandFinder.sitHonesty(0.7))
    }

    @Test
    fun uncoveredDemCellsAreNotInterpolatedAsSeaLevel() {
        val n = size
        val meters = DoubleArray(n * n) { 100.0 }
        val covered = BooleanArray(n * n) { true }
        for (r in 0 until n) covered[r * n] = false
        val grid = ElevationGrid(42.75, -75.5, n, spacing, meters, covered)
        val (edgeLat, edgeLon) = grid.latLon(1, 0)
        assertNull(grid.elevationAt(edgeLat, edgeLon))
        val (lat, lon) = grid.latLon(14, 14)
        assertEquals(100.0, grid.elevationAt(lat, lon)!!, 0.05)
    }

    @Test
    fun levelShelfWithSteepSidesIsStillABench() {
        val n = size
        val r0 = 14
        val meters = DoubleArray(n * n)
        for (r in 0 until n) {
            val z = when {
                r <= 11 -> 240.0 - (11 - r) * 15.0
                r >= 17 -> 160.0 - (r - 17) * 15.0
                r == 12 -> 205.0
                r == 16 -> 195.0
                else -> 200.0
            }
            for (c in 0 until n) meters[r * n + c] = z
        }
        val grid = ElevationGrid(42.75, -75.5, n, spacing, meters)
        assertEquals(StandFeature.BENCH, StandFinder.classify(grid, r0, r0, null))
    }

    @Test
    fun benchRequiresSteepGroundOnBothSidesOfTheShelf() {
        val both = slopeShelf(dropDownhill = true)
        assertEquals(StandFeature.BENCH, StandFinder.classify(both, 14, 14, null))
        val oneCliff = slopeShelf(dropDownhill = false)
        assertTrue(StandFinder.classify(oneCliff, 14, 14, null) != StandFeature.BENCH)
    }

    @Test
    fun drawHeadKeepsRisingUphillAndFallingDownhill() {
        val n = size
        val meters = DoubleArray(n * n)
        val r0 = 14
        for (r in 0 until n) {
            for (c in 0 until n) {
                var z = 200.0 + (r0 - r) * 7.0
                if (r == r0 && c == r0) z -= 8.0
                meters[r * n + c] = z
            }
        }
        val grid = ElevationGrid(42.75, -75.5, n, spacing, meters)
        assertEquals(StandFeature.DRAW_HEAD, StandFinder.classify(grid, r0, r0, null))
    }

    @Test
    fun siteDemIsUsedWhenTheCenterIsCoveredAndSkippedWhenItIsASpeck() {
        val lat = 42.75
        val lon = -75.5
        val wide = SiteDem { la, lo ->
            if (StandFinder.distanceMeters(lat, lon, la, lo) <= 400.0) 250.0 else null
        }
        val grid = SiteDemGrids.fromSamples(lat, lon, wide)
        assertNotNull(grid)
        val half = StandFinder.GRID_SIZE / 2
        assertTrue(grid!!.isCovered(half, half))
        assertTrue(!grid.isCovered(2, 2))
        assertTrue(grid.covered.count { it } >= SiteDemGrids.MIN_HITS)
        val speck = SiteDem { la, lo ->
            if (StandFinder.distanceMeters(lat, lon, la, lo) <= 30.0) 250.0 else null
        }
        assertNull(SiteDemGrids.fromSamples(lat, lon, speck))
    }

    @Test
    fun lidarSampleIsNorthUpAndBilinear() {
        val meta = GeoSpatialLibrary.GeoSpatialMetadata(
            siteName = "test",
            bounds = GeoSpatialLibrary.GeographicBounds(42.0, 43.0, -76.0, -75.0),
            columns = 5,
            rows = 5,
        )
        val center = LidarStandDem.sample(42.5, -75.5, meta, 5, 5) { col, _ -> col * 10.0 }
        assertEquals(20.0, center!!, 1e-6)
        val northEdge = LidarStandDem.sample(43.0, -75.5, meta, 5, 5) { col, row -> (row * 100 + col).toDouble() }
        assertEquals(2.0, northEdge!!, 1e-6)
        assertNull(LidarStandDem.sample(50.0, -75.5, meta, 5, 5) { _, _ -> 1.0 })
    }

    /** Gentle north-facing plane with a steep step above the shelf, and optionally below it. */
    private fun slopeShelf(dropDownhill: Boolean): ElevationGrid {
        val n = size
        val r0 = 14
        val meters = DoubleArray(n * n)
        for (r in 0 until n) {
            for (c in 0 until n) {
                var z = 200.0 + (r0 - r) * 2.62
                if (r <= r0 - 3) z += 25.0
                if (dropDownhill && r >= r0 + 3) z -= 25.0
                meters[r * n + c] = z
            }
        }
        return ElevationGrid(42.75, -75.5, n, spacing, meters)
    }

    private fun stand(feature: StandFeature, align: Double, scent: Double, deerFrom: Double) = StandCandidate(
        id = 1,
        latitude = 42.0,
        longitude = -75.0,
        feature = feature,
        elevationFeet = 1000.0,
        slopeDegrees = 8.0,
        uphillBearing = 0.0,
        morningScore = StandFinder.sitScore(feature, align),
        eveningScore = StandFinder.sitScore(feature, align * 0.5),
        morningScentToward = scent,
        eveningScentToward = scent,
        morningDeerFrom = deerFrom,
        eveningDeerFrom = deerFrom,
        morningAlign = align,
        eveningAlign = align * 0.5,
    )
}
