package com.example.ui.thermals

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.thermals.SiteTerrain
import com.example.thermals.ThermalHour
import com.example.thermals.ThermalPhase
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

// Shared by the Wind map (Mapbox) and the Stands map (Google): the ground under a grid of screen
// points, the scent/thermal motion there, and the streak particles that ride it.

internal const val FLOW_GRID_COLS = 9
internal const val FLOW_GRID_ROWS = 15

internal enum class ParticleMode(val label: String) { SCENT("Scent drift"), THERMAL("Thermal only") }

/** One sample of the map's ground under the screen, in screen pixels, with how many pixels one meter north/east spans there. */
internal class GroundCell(
    val px: Float,
    val py: Float,
    val northX: Float,
    val northY: Float,
    val eastX: Float,
    val eastY: Float,
    val terrain: SiteTerrain,
    val latitude: Double = Double.NaN,
    val longitude: Double = Double.NaN,
)

internal class GroundGrid(val cols: Int, val rows: Int, val width: Float, val height: Float, val cells: Array<GroundCell?>) {
    private val cellW = width / cols
    private val cellH = height / rows

    fun cellAt(x: Float, y: Float): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return -1
        val c = (x / cellW).toInt().coerceIn(0, cols - 1)
        val r = (y / cellH).toInt().coerceIn(0, rows - 1)
        return r * cols + c
    }

    /**
     * Velocity in px/s at ([x], [y]), blended bilinearly between the four nearest cell centres.
     * Nearest-cell lookup made the field jump at cell edges, and streaks piled up along those
     * edges in straight lines. Writes (vx, vy) into [out]; false when no cell nearby has motion.
     */
    fun velocityAt(x: Float, y: Float, motions: Array<CellMotion?>, out: FloatArray): Boolean {
        val gx = x / cellW - 0.5f
        val gy = y / cellH - 0.5f
        val c0 = floor(gx).toInt()
        val r0 = floor(gy).toInt()
        val fx = gx - c0
        val fy = gy - r0
        var sx = 0f
        var sy = 0f
        var wSum = 0f
        for (dr in 0..1) for (dc in 0..1) {
            val r = (r0 + dr).coerceIn(0, rows - 1)
            val c = (c0 + dc).coerceIn(0, cols - 1)
            val idx = r * cols + c
            if (cells[idx] == null) continue
            val m = motions.getOrNull(idx) ?: continue
            val w = (if (dc == 0) 1f - fx else fx) * (if (dr == 0) 1f - fy else fy)
            if (w <= 0f) continue
            sx += m.dx * m.speedPx * w
            sy += m.dy * m.speedPx * w
            wSum += w
        }
        if (wSum < 1e-4f) return false
        out[0] = sx / wSum
        out[1] = sy / wSum
        return true
    }

    /**
     * Terrain of the sample nearest [lat]/[lon]. The wind-map cone and HUD use this so they
     * match the particle cell under the pin (same slope source, same exaggeration correction).
     */
    fun terrainNearest(lat: Double, lon: Double): SiteTerrain? {
        var best: GroundCell? = null
        var bestD = Double.POSITIVE_INFINITY
        val cosLat = kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        for (cell in cells) {
            if (cell == null || cell.latitude.isNaN() || cell.longitude.isNaN()) continue
            val d = hypot(cell.latitude - lat, (cell.longitude - lon) * cosLat)
            if (d < bestD) {
                bestD = d
                best = cell
            }
        }
        return best?.terrain
    }
}

/** Per-cell motion for the current hour: unit screen direction, pixel speed and phase color. */
internal class CellMotion(val dx: Float, val dy: Float, val speedPx: Float, val color: Int, val swirl: Boolean)

internal fun cellMotion(cell: GroundCell, flow: ThermalHour, mode: ParticleMode, pxPerDp: Float): CellMotion {
    val (bearing, mph) = when (mode) {
        ParticleMode.SCENT -> flow.scentTowardDegrees to flow.scentSpeedMph
        ParticleMode.THERMAL -> (flow.thermalTowardDegrees ?: flow.scentTowardDegrees) to
            (if (flow.thermalTowardDegrees == null) 0.15 else flow.thermalSpeedMph)
    }
    val rad = Math.toRadians(bearing)
    // Screen direction of a ground bearing = east·sin + north·cos (perspective-correct).
    var sx = (cell.eastX * sin(rad) + cell.northX * cos(rad)).toFloat()
    var sy = (cell.eastY * sin(rad) + cell.northY * cos(rad)).toFloat()
    val len = hypot(sx, sy)
    val perspective = len
    if (len > 1e-6f) {
        sx /= len
        sy /= len
    } else {
        sx = 0f
        sy = -1f
    }
    // Pixels per second: visibly faster for stronger flow; nearer ground (bigger px/m) moves faster.
    val depth = (perspective / 0.35f).coerceIn(0.45f, 1.6f)
    val speed = (14f + mph.toFloat() * 22f) * pxPerDp * depth
    val swirl = flow.phase == ThermalPhase.SWITCHING && abs(flow.tendency) < 0.03 && mph < 1.0
    return CellMotion(sx, sy, speed, phaseColor(flow.phase).toArgb(), swirl)
}

/**
 * Streaks that flow with the local scent/thermal motion of the ground under them. Particles
 * dim while the map is being dragged (the field is re-sampled when the camera settles).
 */
@Composable
internal fun ParticleOverlay(
    grid: GroundGrid?,
    motions: Array<CellMotion?>,
    count: Int,
    lastMoveMs: () -> Long,
    modifier: Modifier = Modifier,
    alpha: Float = 0.9f,
) {
    val currentGrid by rememberUpdatedState(grid)
    val currentMotions by rememberUpdatedState(motions)
    val particles = remember(count) { ParticleBuffer(count) }
    var frame by remember { mutableLongStateOf(0L) }
    val strokePx = with(LocalDensity.current) { 2.2.dp.toPx() }

    LaunchedEffect(particles) {
        var last = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                val g = currentGrid
                if (g != null) particles.step(dt, g, currentMotions)
                frame = now
            }
        }
    }

    Canvas(modifier.fillMaxSize()) {
        if (frame < 0L) return@Canvas // reading the frame clock redraws every frame
        if (currentGrid == null) return@Canvas
        val moving = System.currentTimeMillis() - lastMoveMs() < 350L
        val dimming = if (moving) 0.25f else 1f
        for (i in 0 until particles.size) {
            if (particles.cell[i] < 0) continue
            val life = particles.age[i] / particles.life[i]
            val fade = (if (life < 0.15f) life / 0.15f else if (life > 0.75f) (1f - life) / 0.25f else 1f).coerceIn(0f, 1f)
            val speed = particles.speed[i]
            if (speed < 0.5f) continue
            val tail = (6f + speed * 0.22f).coerceAtMost(40f)
            val ux = particles.vx[i] / speed
            val uy = particles.vy[i] / speed
            val x = particles.x[i]
            val y = particles.y[i]
            drawLine(
                color = Color(particles.color[i]).copy(alpha = alpha * fade * dimming),
                start = Offset(x - ux * tail, y - uy * tail),
                end = Offset(x, y),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Struct-of-arrays particle store (no per-frame allocation). */
internal class ParticleBuffer(val size: Int, seed: Int = 42) {
    val x = FloatArray(size)
    val y = FloatArray(size)
    val vx = FloatArray(size)
    val vy = FloatArray(size)
    val speed = FloatArray(size)
    val color = IntArray(size)
    val age = FloatArray(size)
    val life = FloatArray(size) { 1f }
    val cell = IntArray(size) { -1 }
    private val rnd = Random(seed)
    private var counts = IntArray(0)
    private val v = FloatArray(2)

    fun step(dt: Float, grid: GroundGrid, motions: Array<CellMotion?>) {
        val n = grid.cells.size
        if (counts.size != n) counts = IntArray(n)
        counts.fill(0)
        var active = 0
        for (c in 0 until n) if (grid.cells[c] != null && motions.getOrNull(c) != null) active++
        if (active == 0) {
            cell.fill(-1)
            return
        }
        for (i in 0 until size) cell[i].let { if (it in 0 until n) counts[it]++ }
        // Converging flow (valley bottoms, along roads in a drainage) would otherwise collect
        // streaks into dense lines; thin out cells holding far more than their share.
        val crowdCap = max(3f, size.toFloat() / active * CROWD_FACTOR)

        for (i in 0 until size) {
            age[i] += dt
            val c = cell[i]
            if (c < 0 || c >= n || age[i] >= life[i]) {
                respawn(i, grid, motions)
                continue
            }
            if (counts[c] > crowdCap && rnd.nextFloat() < dt * THIN_RATE) {
                counts[c]--
                respawn(i, grid, motions)
                continue
            }
            val m = motions.getOrNull(c)
            if (m == null || !grid.velocityAt(x[i], y[i], motions, v)) {
                respawn(i, grid, motions)
                continue
            }
            var dx = v[0]
            var dy = v[1]
            if (m.swirl) {
                // Switching thermals: scent eddies around instead of travelling.
                val a = age[i] * 2.4f + i
                dx = cos(a) * m.speedPx
                dy = sin(a) * m.speedPx
            }
            vx[i] = dx
            vy[i] = dy
            speed[i] = hypot(dx, dy)
            color[i] = m.color
            x[i] += dx * dt
            y[i] += dy * dt
            val next = grid.cellAt(x[i], y[i])
            if (next != c) {
                counts[c]--
                if (next >= 0) counts[next]++
            }
            cell[i] = next
            if (next < 0 || grid.cells[next] == null) respawn(i, grid, motions)
        }
    }

    /** Respawns at the least crowded of a few random spots, so coverage stays even. */
    private fun respawn(i: Int, grid: GroundGrid, motions: Array<CellMotion?>) {
        var bestX = 0f
        var bestY = 0f
        var bestCell = -1
        var bestCount = Int.MAX_VALUE
        repeat(SPAWN_TRIES) {
            val px = rnd.nextFloat() * grid.width
            val py = rnd.nextFloat() * grid.height
            val c = grid.cellAt(px, py)
            if (c >= 0 && grid.cells[c] != null && motions.getOrNull(c) != null) {
                val load = counts.getOrElse(c) { 0 }
                if (load < bestCount) {
                    bestCount = load
                    bestX = px
                    bestY = py
                    bestCell = c
                }
            }
        }
        age[i] = 0f
        cell[i] = bestCell
        if (bestCell < 0) return
        x[i] = bestX
        y[i] = bestY
        speed[i] = 0f
        motions[bestCell]?.let { color[i] = it.color }
        if (bestCell < counts.size) counts[bestCell]++
        life[i] = 2f + rnd.nextFloat() * 2.5f
    }

    private companion object {
        const val CROWD_FACTOR = 2.2f
        const val THIN_RATE = 3f
        const val SPAWN_TRIES = 6
    }
}

// ───────────────────────── Shared map chrome ─────────────────────────

@Composable
internal fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.86f),
        modifier = modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
internal fun RoundGlassButton(onClick: () -> Unit, testTag: String, content: @Composable () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.86f),
        modifier = Modifier.size(44.dp).testTag(testTag),
    ) {
        IconButton(onClick = onClick) { content() }
    }
}
