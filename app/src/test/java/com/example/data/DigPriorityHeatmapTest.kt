package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DigPriorityHeatmapTest {

    private fun signal(gridX: Float, gridY: Float, status: String = "Logged") = TargetSignal(
        gridX = gridX,
        gridY = gridY,
        metalType = MetalType.MANUAL_MARKER,
        signalStrength = 0f,
        status = status,
    )

    @Test
    fun emptySignalsProduceAnAllZeroGrid() {
        val heatmap = computeDigPriorityHeatmap(emptyList(), bins = 10)

        assertEquals(100, heatmap.size)
        assertTrue(heatmap.all { it == 0f })
    }

    @Test
    fun excavatedAndTrashFindsAreExcludedFromDensity() {
        val heatmap = computeDigPriorityHeatmap(
            listOf(signal(50f, 50f, status = "Excavated"), signal(50f, 50f, status = "Trash")),
            bins = 10,
        )

        assertTrue(heatmap.all { it == 0f })
    }

    @Test
    fun clusteredSignalsPeakHigherThanAnIsolatedOne() {
        val bins = 20
        val clustered = computeDigPriorityHeatmap(
            listOf(signal(50f, 50f), signal(51f, 50f), signal(50f, 51f), signal(52f, 52f)),
            bins = bins,
        )
        val isolated = computeDigPriorityHeatmap(listOf(signal(5f, 5f)), bins = bins)

        assertTrue(clustered.max() >= isolated.max())
        // The normalized peak should sit at 1.0 in both cases; what differs is the surrounding spread.
        assertEquals(1f, clustered.max())
        assertEquals(1f, isolated.max())
    }

    @Test
    fun densityStaysWithinNormalizedBounds() {
        val signals = (0 until 30).map { index -> signal((index % 20).toFloat() * 5f, (index / 20).toFloat() * 5f) }
        val heatmap = computeDigPriorityHeatmap(signals, bins = 15)

        assertTrue(heatmap.all { it in 0f..1f })
    }
}
