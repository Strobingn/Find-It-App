package com.example.data.relief

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Ways of drawing bare-earth elevation so man-made earthworks stand out. */
enum class ReliefMode(val label: String, val description: String) {
    HILLSHADE("Hillshade", "Ground lit by the sun. Several suns keep features visible whichever way they run."),
    SLOPE("Slope", "Steepness only, no light direction. Banks and ditch sides read dark."),
    LOCAL_RELIEF("Local relief", "Ground minus its smoothed self: small bumps and hollows, no hills."),
    SKY_VIEW("Sky view", "How much sky each spot sees. Pits and channels go dark, ridges stay light."),
    OPENNESS("Openness", "Like sky view but balanced for ridges and hollows alike."),
    MULTI_SCALE("Multi-scale relief", "Local relief at several sizes at once, for features both small and broad."),
    RED_RELIEF("Red relief", "Slope as red, ridges light and hollows dark, with no light direction."),
    VAT("VAT", "Hillshade, slope, sky view and openness blended into one balanced view."),
    MULTI_LIGHT_PCA("Multi-light PCA", "Eight hillshades combined so nothing hides in shadow."),
    SVF_LRM("Sky view + local relief", "Sky view in grey with local relief tinted warm (up) and cool (down)."),
    SVF_SLOPE("Sky view + slope", "Sky view sharpened by steepness, so rims read as hard as floors."),
}

data class ReliefSettings(
    val mode: ReliefMode = ReliefMode.HILLSHADE,
    val azimuthDeg: Float = 315f,
    val altitudeDeg: Float = 35f,
    val zFactor: Float = 2f,
    val multiDirection: Boolean = true,
    val multiColour: Boolean = false,
    /** Neighbourhood for local relief / sky view / openness, in ground metres. */
    val radiusMeters: Float = 10f,
    /** -0.5..0.5 */
    val brightness: Float = 0f,
    /** 0.5..2 */
    val contrast: Float = 1f,
)

/**
 * Renders an [ElevationChunk] into ARGB pixels (SIZE×SIZE, the padded margin is only read).
 * Pure Kotlin so it runs off the main thread and in JVM tests.
 */
object ReliefRenderer {
    private const val F = ElevationChunk.FULL
    private const val P = ElevationChunk.PAD
    private const val S = ElevationChunk.SIZE
    private const val DIRECTIONS = 16

    fun render(chunk: ElevationChunk, settings: ReliefSettings): IntArray =
        render(chunk.values, chunk.cellMeters, settings)

    fun render(values: FloatArray, cellMeters: Double, settings: ReliefSettings): IntArray {
        require(values.size == F * F)
        val valid = BooleanArray(F * F) { !values[it].isNaN() }
        val out = IntArray(S * S)
        if (valid.none { it }) return out
        val e = filled(values, valid)
        val cell = cellMeters.coerceAtLeast(0.05)
        val z = settings.zFactor.toDouble().coerceIn(0.1, 20.0)
        val radiusPx = (settings.radiusMeters / cell).roundToInt().coerceIn(2, P - 1)

        val rgb: (Int, Int) -> Int = when (settings.mode) {
            ReliefMode.HILLSHADE -> hillshadeShader(e, cell, z, settings)
            ReliefMode.SLOPE -> {
                val slope = slopeDeg(e, cell, z)
                ({ x, y -> grey(1.0 - slope[idx(x, y)] / 60.0) })
            }
            ReliefMode.LOCAL_RELIEF -> {
                val lrm = localRelief(e, radiusPx)
                val range = robustRange(lrm)
                ({ x, y -> grey(0.5 + lrm[idx(x, y)] / (2 * range)) })
            }
            ReliefMode.SKY_VIEW -> {
                val h = horizons(e, cell, radiusPx, needOpenness = false)
                ({ x, y -> grey((h.svf[idx(x, y)] - 0.7) / 0.3) })
            }
            ReliefMode.OPENNESS -> {
                val h = horizons(e, cell, radiusPx, needOpenness = true)
                ({ x, y -> grey((h.positive[idx(x, y)] - 65.0) / 30.0) })
            }
            ReliefMode.MULTI_SCALE -> {
                val msrm = multiScale(e, radiusPx)
                ({ x, y -> grey(0.5 + msrm[idx(x, y)] / 5.0) })
            }
            ReliefMode.RED_RELIEF -> {
                val slope = slopeDeg(e, cell, z)
                val h = horizons(e, cell, radiusPx, needOpenness = true)
                ({ x, y ->
                    val i = idx(x, y)
                    val s = min(1.0, slope[i] / 45.0)
                    val v = clamp01(0.5 + (h.positive[i] - h.negative[i]) / 2.0 / 20.0)
                    rgb(v, v * (1 - 0.85 * s), v * (1 - 0.95 * s))
                })
            }
            ReliefMode.VAT -> {
                val slope = slopeDeg(e, cell, z)
                val h = horizons(e, cell, radiusPx, needOpenness = true)
                val hs = hillshadeArray(e, cell, z, settings.azimuthDeg.toDouble(), settings.altitudeDeg.toDouble())
                ({ x, y ->
                    val i = idx(x, y)
                    val v = (hs[i] + clamp01(1 - slope[i] / 50.0) + clamp01((h.svf[i] - 0.7) / 0.3) +
                        clamp01((h.positive[i] - 65.0) / 30.0)) / 4.0
                    grey(v)
                })
            }
            ReliefMode.MULTI_LIGHT_PCA -> {
                val pc = multiLightPca(e, cell, z, settings.altitudeDeg.toDouble())
                ({ x, y -> grey(pc[idx(x, y)]) })
            }
            ReliefMode.SVF_LRM -> {
                val h = horizons(e, cell, radiusPx, needOpenness = false)
                val lrm = localRelief(e, radiusPx)
                val range = robustRange(lrm)
                ({ x, y ->
                    val i = idx(x, y)
                    val base = clamp01((h.svf[i] - 0.7) / 0.3)
                    val t = (lrm[i] / range).coerceIn(-1.0, 1.0)
                    val a = abs(t) * 0.75
                    if (t >= 0) {
                        rgb(base * (1 - a) + 1.0 * a, base * (1 - a) + 0.55 * a, base * (1 - a) + 0.1 * a)
                    } else {
                        rgb(base * (1 - a) + 0.1 * a, base * (1 - a) + 0.45 * a, base * (1 - a) + 1.0 * a)
                    }
                })
            }
            ReliefMode.SVF_SLOPE -> {
                val slope = slopeDeg(e, cell, z)
                val h = horizons(e, cell, radiusPx, needOpenness = false)
                ({ x, y ->
                    val i = idx(x, y)
                    grey(clamp01((h.svf[i] - 0.7) / 0.3) * (1 - 0.6 * min(1.0, slope[i] / 45.0)))
                })
            }
        }

        val contrast = settings.contrast.toDouble().coerceIn(0.3, 3.0)
        val brightness = settings.brightness.toDouble().coerceIn(-0.5, 0.5) * 255
        for (y in 0 until S) {
            for (x in 0 until S) {
                if (!valid[idx(x, y)]) continue
                val c = rgb(x, y)
                out[y * S + x] = adjust(c, contrast, brightness)
            }
        }
        return out
    }

    // --- building blocks ----------------------------------------------------------------------

    /** Full-array index of inner pixel (x, y). */
    private fun idx(x: Int, y: Int) = (y + P) * F + (x + P)

    /** Replaces gaps with the nearest valid value along rows, then columns, so filters stay sane. */
    private fun filled(values: FloatArray, valid: BooleanArray): DoubleArray {
        val e = DoubleArray(values.size) { if (valid[it]) values[it].toDouble() else Double.NaN }
        val mean = values.filter { !it.isNaN() }.average()
        for (y in 0 until F) {
            var last = Double.NaN
            for (x in 0 until F) {
                val i = y * F + x
                if (e[i].isNaN()) e[i] = last else last = e[i]
            }
            last = Double.NaN
            for (x in F - 1 downTo 0) {
                val i = y * F + x
                if (e[i].isNaN()) e[i] = last else last = e[i]
            }
        }
        for (i in e.indices) if (e[i].isNaN()) e[i] = mean
        return e
    }

    private class Gradient(val gx: DoubleArray, val gn: DoubleArray)

    /** Horn's 3×3 gradient: east and north slopes (rise/run), zFactor applied. */
    private fun gradient(e: DoubleArray, cell: Double, z: Double): Gradient {
        val gx = DoubleArray(F * F)
        val gn = DoubleArray(F * F)
        val k = z / (8 * cell)
        for (y in 1 until F - 1) {
            for (x in 1 until F - 1) {
                val i = y * F + x
                val a = e[i - F - 1]
                val b = e[i - F]
                val c = e[i - F + 1]
                val d = e[i - 1]
                val f = e[i + 1]
                val g = e[i + F - 1]
                val h = e[i + F]
                val ii = e[i + F + 1]
                gx[i] = ((c + 2 * f + ii) - (a + 2 * d + g)) * k
                // Image rows run south, so north slope is top minus bottom.
                gn[i] = ((a + 2 * b + c) - (g + 2 * h + ii)) * k
            }
        }
        return Gradient(gx, gn)
    }

    private fun slopeDeg(e: DoubleArray, cell: Double, z: Double): DoubleArray {
        val g = gradient(e, cell, z)
        return DoubleArray(F * F) { Math.toDegrees(atan(sqrt(g.gx[it] * g.gx[it] + g.gn[it] * g.gn[it]))) }
    }

    private fun shade(gx: Double, gn: Double, lx: Double, ly: Double, lz: Double): Double {
        val norm = sqrt(gx * gx + gn * gn + 1)
        return max(0.0, (-gx * lx - gn * ly + lz) / norm)
    }

    private fun light(azimuthDeg: Double, altitudeDeg: Double): DoubleArray {
        val az = Math.toRadians(azimuthDeg)
        val alt = Math.toRadians(altitudeDeg)
        return doubleArrayOf(sin(az) * cos(alt), cos(az) * cos(alt), sin(alt))
    }

    private fun hillshadeArray(e: DoubleArray, cell: Double, z: Double, az: Double, alt: Double): DoubleArray {
        val g = gradient(e, cell, z)
        val l = light(az, alt)
        return DoubleArray(F * F) { shade(g.gx[it], g.gn[it], l[0], l[1], l[2]) }
    }

    private fun hillshadeShader(e: DoubleArray, cell: Double, z: Double, s: ReliefSettings): (Int, Int) -> Int {
        val g = gradient(e, cell, z)
        val az = s.azimuthDeg.toDouble()
        val alt = s.altitudeDeg.toDouble()
        return when {
            s.multiColour -> {
                val lr = light(az - 60, alt)
                val lg = light(az, alt)
                val lb = light(az + 60, alt)
                ({ x, y ->
                    val i = idx(x, y)
                    rgb(
                        shade(g.gx[i], g.gn[i], lr[0], lr[1], lr[2]),
                        shade(g.gx[i], g.gn[i], lg[0], lg[1], lg[2]),
                        shade(g.gx[i], g.gn[i], lb[0], lb[1], lb[2]),
                    )
                })
            }
            s.multiDirection -> {
                val suns = listOf(-45.0, -15.0, 15.0, 45.0).map { light(az + it, alt) }
                ({ x, y ->
                    val i = idx(x, y)
                    grey(suns.sumOf { shade(g.gx[i], g.gn[i], it[0], it[1], it[2]) } / suns.size)
                })
            }
            else -> {
                val l = light(az, alt)
                ({ x, y ->
                    val i = idx(x, y)
                    grey(shade(g.gx[i], g.gn[i], l[0], l[1], l[2]))
                })
            }
        }
    }

    /** Mean over a (2r+1)² window via a summed-area table. */
    private fun boxMean(e: DoubleArray, r: Int): DoubleArray {
        val w = F + 1
        val sat = DoubleArray(w * w)
        for (y in 0 until F) {
            var row = 0.0
            for (x in 0 until F) {
                row += e[y * F + x]
                sat[(y + 1) * w + (x + 1)] = sat[y * w + (x + 1)] + row
            }
        }
        val out = DoubleArray(F * F)
        for (y in 0 until F) {
            val y0 = max(0, y - r)
            val y1 = min(F - 1, y + r)
            for (x in 0 until F) {
                val x0 = max(0, x - r)
                val x1 = min(F - 1, x + r)
                val sum = sat[(y1 + 1) * w + (x1 + 1)] - sat[y0 * w + (x1 + 1)] - sat[(y1 + 1) * w + x0] + sat[y0 * w + x0]
                out[y * F + x] = sum / ((y1 - y0 + 1) * (x1 - x0 + 1))
            }
        }
        return out
    }

    private fun localRelief(e: DoubleArray, r: Int): DoubleArray {
        val mean = boxMean(e, r)
        return DoubleArray(F * F) { e[it] - mean[it] }
    }

    /** Spread used to stretch a signed layer: 2.5 × its standard deviation over the inner area. */
    private fun robustRange(layer: DoubleArray): Double {
        var sum = 0.0
        var sum2 = 0.0
        var n = 0
        for (y in 0 until S step 4) for (x in 0 until S step 4) {
            val v = layer[idx(x, y)]
            sum += v
            sum2 += v * v
            n++
        }
        val mean = sum / n
        val sd = sqrt(max(0.0, sum2 / n - mean * mean))
        return max(sd * 2.5, 0.05)
    }

    /** Mean of local-relief layers at four radii, each in units of its own spread. */
    private fun multiScale(e: DoubleArray, rMax: Int): DoubleArray {
        val radii = (0..3).map { max(1, (rMax / 2.0.pow(3 - it)).roundToInt()) }.distinct()
        val out = DoubleArray(F * F)
        for (r in radii) {
            val lrm = localRelief(e, r)
            val sd = robustRange(lrm) / 2.5
            for (i in out.indices) out[i] += lrm[i] / sd
        }
        for (i in out.indices) out[i] /= radii.size
        return out
    }

    private class Horizons(val svf: DoubleArray, val positive: DoubleArray, val negative: DoubleArray)

    /**
     * Horizon search in [DIRECTIONS] directions out to [radius] pixels: sky-view factor
     * (Zakšek et al. 2011) and positive/negative openness (Yokoyama et al. 2002), in degrees.
     */
    private fun horizons(e: DoubleArray, cell: Double, radius: Int, needOpenness: Boolean): Horizons {
        val steps = (1..radius).let { all ->
            if (radius <= 12) all.toList() else (0 until 12).map { k -> (1 + (radius - 1) * (k / 11.0).pow(1.6)).roundToInt() }.distinct()
        }
        val offsets = Array(DIRECTIONS) { d ->
            val a = 2 * PI * d / DIRECTIONS
            IntArray(steps.size) { s ->
                val dx = (sin(a) * steps[s]).roundToInt()
                val dy = (-cos(a) * steps[s]).roundToInt()
                dy * F + dx
            }
        }
        val dists = Array(DIRECTIONS) { d ->
            val a = 2 * PI * d / DIRECTIONS
            DoubleArray(steps.size) { s ->
                val dx = (sin(a) * steps[s]).roundToInt()
                val dy = (-cos(a) * steps[s]).roundToInt()
                sqrt((dx * dx + dy * dy).toDouble()).coerceAtLeast(1.0) * cell
            }
        }
        val svf = DoubleArray(F * F)
        val pos = if (needOpenness) DoubleArray(F * F) else svf
        val neg = if (needOpenness) DoubleArray(F * F) else svf
        for (y in 0 until S) {
            for (x in 0 until S) {
                val i = idx(x, y)
                val h0 = e[i]
                var svfSum = 0.0
                var posSum = 0.0
                var negSum = 0.0
                for (d in 0 until DIRECTIONS) {
                    var maxUp = -1e9
                    var maxDown = -1e9
                    val off = offsets[d]
                    val dist = dists[d]
                    for (s in off.indices) {
                        val dh = e[i + off[s]] - h0
                        val t = dh / dist[s]
                        if (t > maxUp) maxUp = t
                        if (-t > maxDown) maxDown = -t
                    }
                    val up = max(0.0, maxUp)
                    svfSum += up / sqrt(1 + up * up)
                    if (needOpenness) {
                        posSum += 90.0 - Math.toDegrees(atan(maxUp))
                        negSum += 90.0 - Math.toDegrees(atan(maxDown))
                    }
                }
                svf[i] = 1.0 - svfSum / DIRECTIONS
                if (needOpenness) {
                    pos[i] = posSum / DIRECTIONS
                    neg[i] = negSum / DIRECTIONS
                }
            }
        }
        return Horizons(svf, pos, neg)
    }

    /** First principal component of eight hillshades (every 45°), stretched to 0..1. */
    private fun multiLightPca(e: DoubleArray, cell: Double, z: Double, alt: Double): DoubleArray {
        val g = gradient(e, cell, z)
        val lights = (0 until 8).map { light(it * 45.0, alt) }
        fun bands(i: Int) = DoubleArray(8) { b -> shade(g.gx[i], g.gn[i], lights[b][0], lights[b][1], lights[b][2]) }
        // Covariance on a sample grid.
        val mean = DoubleArray(8)
        val cov = Array(8) { DoubleArray(8) }
        var n = 0
        for (y in 0 until S step 8) for (x in 0 until S step 8) {
            val v = bands(idx(x, y))
            for (a in 0 until 8) mean[a] += v[a]
            n++
        }
        for (a in 0 until 8) mean[a] /= n
        for (y in 0 until S step 8) for (x in 0 until S step 8) {
            val v = bands(idx(x, y))
            for (a in 0 until 8) for (b in 0 until 8) cov[a][b] += (v[a] - mean[a]) * (v[b] - mean[b])
        }
        // Power iteration for the dominant eigenvector.
        var vec = DoubleArray(8) { 1.0 }
        repeat(40) {
            val next = DoubleArray(8) { a -> (0 until 8).sumOf { b -> cov[a][b] * vec[b] } }
            val norm = sqrt(next.sumOf { it * it }).coerceAtLeast(1e-12)
            vec = DoubleArray(8) { next[it] / norm }
        }
        if (vec.sum() < 0) vec = DoubleArray(8) { -vec[it] }
        val pc = DoubleArray(F * F)
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (y in 0 until S) for (x in 0 until S) {
            val i = idx(x, y)
            val v = bands(i)
            val p = (0 until 8).sumOf { (v[it] - mean[it]) * vec[it] }
            pc[i] = p
            if (p < lo) lo = p
            if (p > hi) hi = p
        }
        val span = (hi - lo).coerceAtLeast(1e-9)
        for (y in 0 until S) for (x in 0 until S) {
            val i = idx(x, y)
            pc[i] = (pc[i] - lo) / span
        }
        return pc
    }

    // --- colour -------------------------------------------------------------------------------

    private fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)

    private fun grey(v: Double): Int {
        val c = (clamp01(v) * 255).roundToInt()
        return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
    }

    private fun rgb(r: Double, g: Double, b: Double): Int =
        (0xFF shl 24) or
            ((clamp01(r) * 255).roundToInt() shl 16) or
            ((clamp01(g) * 255).roundToInt() shl 8) or
            (clamp01(b) * 255).roundToInt()

    private fun adjust(c: Int, contrast: Double, brightness: Double): Int {
        if (contrast == 1.0 && brightness == 0.0) return c
        fun ch(v: Int) = ((v - 128) * contrast + 128 + brightness).roundToInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or
            (ch((c shr 16) and 0xFF) shl 16) or
            (ch((c shr 8) and 0xFF) shl 8) or
            ch(c and 0xFF)
    }
}
