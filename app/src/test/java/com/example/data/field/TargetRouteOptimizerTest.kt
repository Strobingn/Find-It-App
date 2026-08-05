package com.example.data.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetRouteOptimizerTest {
    private fun waypoint(id: String, latitude: Double, longitude: Double) =
        FieldWaypoint(id = id, latitude = latitude, longitude = longitude, displayName = id)

    @Test
    fun emptyAndSingleWaypointRoutesAreTrivial() {
        val empty = TargetRouteOptimizer.optimize(emptyList())
        assertTrue(empty.waypoints.isEmpty())
        assertEquals(0.0, empty.totalDistanceMeters, 0.0001)

        val single = TargetRouteOptimizer.optimize(listOf(waypoint("A", 1.0, 1.0)))
        assertEquals(listOf("A"), single.waypoints.map { it.id })
    }

    @Test
    fun nearestNeighborStartsFromClosestWaypoint() {
        // Start (0,0); C is ~56 km away, A ~111 km, B ~234 km.
        val a = waypoint("A", 1.0, 0.0)
        val b = waypoint("B", 1.5, 1.5)
        val c = waypoint("C", 0.0, 0.5)

        val route = TargetRouteOptimizer.optimize(
            waypoints = listOf(a, b, c),
            startLatitude = 0.0,
            startLongitude = 0.0,
        )
        val naiveTotal = TargetRouteOptimizer.totalDistanceMeters(
            listOf(a, b, c),
            startLatitude = 0.0,
            startLongitude = 0.0,
        )

        assertEquals(listOf("C", "A", "B"), route.waypoints.map { it.id })
        assertTrue(
            "optimized ${route.totalDistanceMeters} should beat naive $naiveTotal",
            route.totalDistanceMeters < naiveTotal,
        )
    }

    @Test
    fun crossingInputOrderIsUntangled() {
        // Unit square corners given in crossing order; the short tour visits each side once.
        val a = waypoint("A", 0.0, 0.0)
        val b = waypoint("B", 1.0, 1.0)
        val c = waypoint("C", 0.0, 1.0)
        val d = waypoint("D", 1.0, 0.0)

        val route = TargetRouteOptimizer.optimize(listOf(a, b, c, d))
        val naiveTotal = TargetRouteOptimizer.totalDistanceMeters(listOf(a, b, c, d))

        assertEquals(listOf("A", "C", "B", "D"), route.waypoints.map { it.id })
        assertEquals(333_600.0, route.totalDistanceMeters, 4_000.0)
        assertTrue(route.totalDistanceMeters < naiveTotal)
    }

    @Test
    fun optimizationNeverDropsOrDuplicatesWaypoints() {
        val input = listOf(
            waypoint("A", 41.3, -74.0),
            waypoint("B", 41.5, -73.8),
            waypoint("C", 41.4, -74.2),
            waypoint("D", 41.6, -74.1),
            waypoint("E", 41.35, -73.9),
        )

        val route = TargetRouteOptimizer.optimize(
            waypoints = input,
            startLatitude = 41.3,
            startLongitude = -74.1,
        )

        assertEquals(input.size, route.waypoints.size)
        assertEquals(input.map { it.id }.sorted(), route.waypoints.map { it.id }.sorted())
    }
}
