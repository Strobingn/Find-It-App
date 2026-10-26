package com.example.thermals

import kotlin.math.abs
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        val grid = ridgeWithSaddle()
        val picks = StandFinder.localPicks(StandFinder.findCandidates(grid, ThermalWindEngine.flowField(forecastInputs()), 315.0))
        assertTrue(picks.isNotEmpty())
        val first = picks.first()
        assertEquals(1, first.rank)
        assertTrue(first.why.isNotBlank() && first.entry.isNotBlank() && first.wind.isNotBlank())
        assertNotNull(first.scentToward)
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
        assertEquals(candidates.first().morningScentToward, picks[0].scentToward!!, 1e-9)
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
    fun searchAreaIsHalfAMile() {
        val acrossFeet = StandFinder.GRID_SPACING_M * (StandFinder.GRID_SIZE - 1) * 3.28084
        assertEquals(2_640.0, acrossFeet, 60.0)
    }

    @Test
    fun picksStayOffMappedRoadsAndHouses() {
        val grid = ridgeWithSaddle()
        val free = StandFinder.findCandidates(grid, null, null)
        val saddle = free.first { it.feature == StandFeature.SADDLE }
        // A road running right through the saddle removes it.
        val road = MappedObstacles.densify(listOf((saddle.latitude to saddle.longitude - 0.002), (saddle.latitude to saddle.longitude + 0.002)))
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
        assertTrue(obstacles.roadPoints.size > 2) // densified
        assertTrue(obstacles.roadPoints.all { it.first == 42.75 })
        assertEquals(2, obstacles.buildingPoints.size)
        assertTrue(obstacles.tooClose(42.75, -75.4995))
        assertTrue(!obstacles.tooClose(42.76, -75.4995)) // footpath ignored
    }

    @Test
    fun distancesAreInFeetAndMiles() {
        assertEquals("330 ft", StandFinder.formatDistance(100.0))
        assertEquals("0.50 mi", StandFinder.formatDistance(804.67))
    }

    @Test
    fun blockedAiServersGetAPlainExplanation() {
        val msg = StandFinderService.explainAiFailure("All cloud providers failed. Grok: Unable to resolve host \"api.x.ai\": No address associated with hostname")
        assertTrue(msg.contains("blocking the AI servers"))
        assertEquals("HTTP 500", StandFinderService.explainAiFailure("HTTP 500"))
    }
}
