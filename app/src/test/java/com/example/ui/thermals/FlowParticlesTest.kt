package com.example.ui.thermals

import com.example.thermals.ElevationGrid
import com.example.thermals.SiteTerrain
import com.example.thermals.TerrainSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowParticlesTest {

    private val flat = SiteTerrain(TerrainSetting.FLAT, null, 0.0)

    private fun grid(w: Float = 900f, h: Float = 1500f) = GroundGrid(
        FLOW_GRID_COLS, FLOW_GRID_ROWS, w, h,
        Array(FLOW_GRID_COLS * FLOW_GRID_ROWS) { i ->
            val c = i % FLOW_GRID_COLS
            val r = i / FLOW_GRID_COLS
            GroundCell((c + 0.5f) * w / FLOW_GRID_COLS, (r + 0.5f) * h / FLOW_GRID_ROWS, 0f, -1f, 1f, 0f, flat)
        },
    )

    /** Left half flows right, right half flows left: everything converges on the middle column. */
    private fun converging(): Array<CellMotion?> = Array(FLOW_GRID_COLS * FLOW_GRID_ROWS) { i ->
        val c = i % FLOW_GRID_COLS
        val dir = if (c < FLOW_GRID_COLS / 2) 1f else if (c > FLOW_GRID_COLS / 2) -1f else 0f
        CellMotion(dir, if (dir == 0f) -1f else 0f, 120f, 0, swirl = false)
    }

    @Test
    fun velocityBlendsSmoothlyAcrossCellEdges() {
        val g = grid()
        val m = converging()
        val out = FloatArray(2)
        val edge = 900f / FLOW_GRID_COLS * 4 // boundary between column 3 (→) and column 4 (centre)
        assertTrue(g.velocityAt(edge - 1f, 700f, m, out))
        val left = out[0]
        assertTrue(g.velocityAt(edge + 1f, 700f, m, out))
        val right = out[0]
        // Nearest-cell lookup jumped from +120 to 0 here; blended, the two sides are nearly equal.
        assertEquals(left, right, 6f)
    }

    @Test
    fun convergingFlowDoesNotPileParticlesIntoALine() {
        val g = grid()
        val m = converging()
        val particles = ParticleBuffer(360, seed = 7)
        repeat(20 * 60) { particles.step(1f / 60f, g, m) } // 20 s of frames
        val counts = IntArray(g.cells.size)
        for (i in 0 until particles.size) particles.cell[i].let { if (it >= 0) counts[it]++ }
        val share = 360f / g.cells.size
        val worst = counts.max()
        assertTrue("worst cell holds $worst particles (fair share ${"%.1f".format(share)})", worst <= share * 4f)
        val empty = counts.count { it == 0 }
        assertTrue("$empty of ${counts.size} cells are empty", empty < counts.size / 3)
    }

    @Test
    fun picksSetTheWindHourForTheirSit() {
        assertEquals(7f, sitHour("Morning sit")!!, 0f)
        assertEquals(17f, sitHour("Evening sit")!!, 0f)
        assertNull(sitHour("All day"))
    }

    @Test
    fun elevationGridGivesUphillAndSlope() {
        // Ground rises 5 m per 50 m to the east.
        val size = 15
        val meters = DoubleArray(size * size) { i -> 300.0 + (i % size) * 5.0 }
        val g = ElevationGrid(42.75, -75.5, size, 50.0, meters)
        assertEquals(335.0, g.elevationAt(42.75, -75.5)!!, 0.5) // centre column 7 × 5 m
        val terrain = g.terrainAt(42.75, -75.5)
        assertNotNull(terrain)
        assertEquals(TerrainSetting.SLOPE, terrain!!.setting)
        assertEquals(90.0, terrain.uphillBearingDegrees!!, 3.0)
        assertNull(g.elevationAt(43.0, -75.5)) // well outside the square
    }
}
