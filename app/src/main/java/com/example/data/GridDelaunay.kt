package com.example.data

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * O(n log n) sweep-hull Delaunay triangulation (port of mapbox/delaunator) for integer grid
 * coordinates. With integer inputs below ~10⁶ every orientation and in-circle test is exact in
 * doubles, so collinear/cocircular grid points cannot loop or flip inconsistently.
 *
 * Output: [triangles] holds vertex ids, three per triangle; [triangleCount] triangles.
 */
internal class GridDelaunay(private val coords: DoubleArray) {
    private val n = coords.size / 2
    var triangles: IntArray = IntArray(max(2 * n - 5, 0) * 3)
        private set
    private var halfedges = IntArray(triangles.size)
    var triangleCount = 0
        private set
    private var trianglesLen = 0
    private val hashSize = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1)
    private val hullPrev = IntArray(n)
    private val hullNext = IntArray(n)
    private val hullTri = IntArray(n)
    private val hullHash = IntArray(hashSize) { -1 }
    private var hullStart = 0
    private var edgeStack = IntArray(512)
    private var cx = 0.0
    private var cy = 0.0

    init {
        if (n >= 3) triangulate()
        triangleCount = trianglesLen / 3
    }

    private fun triangulate() {
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        val ids = IntArray(n)
        for (i in 0 until n) {
            val x = coords[2 * i]; val y = coords[2 * i + 1]
            if (x < minX) minX = x; if (y < minY) minY = y
            if (x > maxX) maxX = x; if (y > maxY) maxY = y
            ids[i] = i
        }
        val ccx = (minX + maxX) / 2; val ccy = (minY + maxY) / 2
        var i0 = 0; var minDist = Double.POSITIVE_INFINITY
        for (i in 0 until n) {
            val d = dist(ccx, ccy, coords[2 * i], coords[2 * i + 1])
            if (d < minDist) { i0 = i; minDist = d }
        }
        val i0x = coords[2 * i0]; val i0y = coords[2 * i0 + 1]
        var i1 = -1; minDist = Double.POSITIVE_INFINITY
        for (i in 0 until n) {
            if (i == i0) continue
            val d = dist(i0x, i0y, coords[2 * i], coords[2 * i + 1])
            if (d < minDist && d > 0) { i1 = i; minDist = d }
        }
        if (i1 < 0) return
        var i1x = coords[2 * i1]; var i1y = coords[2 * i1 + 1]
        var minRadius = Double.POSITIVE_INFINITY
        var i2 = -1
        for (i in 0 until n) {
            if (i == i0 || i == i1) continue
            val r = circumradius(i0x, i0y, i1x, i1y, coords[2 * i], coords[2 * i + 1])
            if (r < minRadius) { i2 = i; minRadius = r }
        }
        if (i2 < 0 || minRadius == Double.POSITIVE_INFINITY) return // all collinear: no triangles
        var i2x = coords[2 * i2]; var i2y = coords[2 * i2 + 1]
        if (orient(i0x, i0y, i1x, i1y, i2x, i2y)) {
            val t = i1; i1 = i2; i2 = t
            val tx = i1x; i1x = i2x; i2x = tx
            val ty = i1y; i1y = i2y; i2y = ty
        }
        circumcenter(i0x, i0y, i1x, i1y, i2x, i2y)
        val dists = DoubleArray(n) { dist(coords[2 * it], coords[2 * it + 1], cx, cy) }
        quicksort(ids, dists, 0, n - 1)

        hullStart = i0
        hullNext[i0] = i1; hullPrev[i2] = i1
        hullNext[i1] = i2; hullPrev[i0] = i2
        hullNext[i2] = i0; hullPrev[i1] = i0
        hullTri[i0] = 0; hullTri[i1] = 1; hullTri[i2] = 2
        hullHash[hashKey(i0x, i0y)] = i0
        hullHash[hashKey(i1x, i1y)] = i1
        hullHash[hashKey(i2x, i2y)] = i2
        addTriangle(i0, i1, i2, -1, -1, -1)

        var xp = 0.0; var yp = 0.0
        for (k in 0 until n) {
            val i = ids[k]
            val x = coords[2 * i]; val y = coords[2 * i + 1]
            if (k > 0 && abs(x - xp) <= EPS && abs(y - yp) <= EPS) continue
            xp = x; yp = y
            if (i == i0 || i == i1 || i == i2) continue
            var start = 0
            val key = hashKey(x, y)
            for (j in 0 until hashSize) {
                start = hullHash[(key + j) % hashSize]
                if (start != -1 && start != hullNext[start]) break
            }
            start = hullPrev[start]
            var e = start
            while (true) {
                val q = hullNext[e]
                if (orient(x, y, coords[2 * e], coords[2 * e + 1], coords[2 * q], coords[2 * q + 1])) break
                e = q
                if (e == start) { e = -1; break }
            }
            if (e == -1) continue
            var t = addTriangle(e, i, hullNext[e], -1, -1, hullTri[e])
            hullTri[i] = legalize(t + 2)
            hullTri[e] = t
            var nx = hullNext[e]
            while (true) {
                val q = hullNext[nx]
                if (!orient(x, y, coords[2 * nx], coords[2 * nx + 1], coords[2 * q], coords[2 * q + 1])) break
                t = addTriangle(nx, i, q, hullTri[i], -1, hullTri[nx])
                hullTri[i] = legalize(t + 2)
                hullNext[nx] = nx
                nx = q
            }
            if (e == start) {
                while (true) {
                    val q = hullPrev[e]
                    if (!orient(x, y, coords[2 * q], coords[2 * q + 1], coords[2 * e], coords[2 * e + 1])) break
                    t = addTriangle(q, i, e, -1, hullTri[e], hullTri[q])
                    legalize(t + 2)
                    hullTri[q] = t
                    hullNext[e] = e
                    e = q
                }
            }
            hullStart = e
            hullPrev[i] = e
            hullNext[e] = i
            hullPrev[nx] = i
            hullNext[i] = nx
            hullHash[hashKey(x, y)] = i
            hullHash[hashKey(coords[2 * e], coords[2 * e + 1])] = e
        }
    }

    private fun legalize(start: Int): Int {
        var a = start
        var i = 0
        var ar: Int
        while (true) {
            val b = halfedges[a]
            val a0 = a - a % 3
            ar = a0 + (a + 2) % 3
            if (b == -1) {
                if (i == 0) break
                a = edgeStack[--i]
                continue
            }
            val b0 = b - b % 3
            val al = a0 + (a + 1) % 3
            val bl = b0 + (b + 2) % 3
            val p0 = triangles[ar]; val pr = triangles[a]; val pl = triangles[al]; val p1 = triangles[bl]
            val illegal = inCircle(
                coords[2 * p0], coords[2 * p0 + 1], coords[2 * pr], coords[2 * pr + 1],
                coords[2 * pl], coords[2 * pl + 1], coords[2 * p1], coords[2 * p1 + 1],
            )
            if (illegal) {
                triangles[a] = p1
                triangles[b] = p0
                val hbl = halfedges[bl]
                if (hbl == -1) {
                    var e = hullStart
                    do {
                        if (hullTri[e] == bl) { hullTri[e] = a; break }
                        e = hullPrev[e]
                    } while (e != hullStart)
                }
                link(a, hbl)
                link(b, halfedges[ar])
                link(ar, bl)
                val br = b0 + (b + 1) % 3
                if (i >= edgeStack.size) edgeStack = edgeStack.copyOf(edgeStack.size * 2)
                edgeStack[i++] = br
            } else {
                if (i == 0) break
                a = edgeStack[--i]
            }
        }
        return ar
    }

    private fun link(a: Int, b: Int) {
        halfedges[a] = b
        if (b != -1) halfedges[b] = a
    }

    private fun addTriangle(i0: Int, i1: Int, i2: Int, a: Int, b: Int, c: Int): Int {
        val t = trianglesLen
        triangles[t] = i0; triangles[t + 1] = i1; triangles[t + 2] = i2
        link(t, a); link(t + 1, b); link(t + 2, c)
        trianglesLen += 3
        return t
    }

    private fun hashKey(x: Double, y: Double): Int =
        (floor(pseudoAngle(x - cx, y - cy) * hashSize).toInt() % hashSize).let { if (it < 0) it + hashSize else it }

    private fun circumcenter(ax: Double, ay: Double, bx: Double, by: Double, qx: Double, qy: Double) {
        val dx = bx - ax; val dy = by - ay; val ex = qx - ax; val ey = qy - ay
        val bl = dx * dx + dy * dy; val cl = ex * ex + ey * ey
        val d = 0.5 / (dx * ey - dy * ex)
        cx = ax + (ey * bl - dy * cl) * d
        cy = ay + (dx * cl - ex * bl) * d
    }

    companion object {
        private const val EPS = 1e-12

        private fun dist(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = ax - bx; val dy = ay - by
            return dx * dx + dy * dy
        }

        private fun pseudoAngle(dx: Double, dy: Double): Double {
            val s = abs(dx) + abs(dy)
            if (s == 0.0) return 0.0
            val p = dx / s
            return (if (dy > 0) 3 - p else 1 + p) / 4
        }

        /** True when r, q, p turn clockwise (exact for integer coordinates). */
        private fun orient(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double): Boolean =
            (qy - py) * (rx - qx) - (qx - px) * (ry - qy) < 0

        private fun inCircle(
            ax: Double, ay: Double, bx: Double, by: Double,
            cx: Double, cy: Double, px: Double, py: Double,
        ): Boolean {
            val dx = ax - px; val dy = ay - py
            val ex = bx - px; val ey = by - py
            val fx = cx - px; val fy = cy - py
            val ap = dx * dx + dy * dy
            val bp = ex * ex + ey * ey
            val cp = fx * fx + fy * fy
            return dx * (ey * cp - bp * fy) - dy * (ex * cp - bp * fx) + ap * (ex * fy - ey * fx) < 0
        }

        private fun circumradius(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double {
            val dx = bx - ax; val dy = by - ay; val ex = cx - ax; val ey = cy - ay
            val bl = dx * dx + dy * dy; val cl = ex * ex + ey * ey
            val den = dx * ey - dy * ex
            if (den == 0.0) return Double.POSITIVE_INFINITY
            val d = 0.5 / den
            val x = (ey * bl - dy * cl) * d
            val y = (dx * cl - ex * bl) * d
            return x * x + y * y
        }

        private fun quicksort(ids: IntArray, dists: DoubleArray, left: Int, right: Int) {
            var l = left; var r = right
            while (true) {
                if (r - l <= 20) {
                    for (i in l + 1..r) {
                        val temp = ids[i]; val tempDist = dists[temp]
                        var j = i - 1
                        while (j >= l && dists[ids[j]] > tempDist) { ids[j + 1] = ids[j]; j-- }
                        ids[j + 1] = temp
                    }
                    return
                }
                val median = (l + r) ushr 1
                var i = l + 1; var j = r
                swap(ids, median, i)
                if (dists[ids[l]] > dists[ids[r]]) swap(ids, l, r)
                if (dists[ids[i]] > dists[ids[r]]) swap(ids, i, r)
                if (dists[ids[l]] > dists[ids[i]]) swap(ids, l, i)
                val temp = ids[i]; val tempDist = dists[temp]
                while (true) {
                    do i++ while (dists[ids[i]] < tempDist)
                    do j-- while (dists[ids[j]] > tempDist)
                    if (j < i) break
                    swap(ids, i, j)
                }
                ids[l + 1] = ids[j]; ids[j] = temp
                if (r - i + 1 >= j - l) {
                    quicksort(ids, dists, i, r); r = j - 1
                } else {
                    quicksort(ids, dists, l, j - 1); l = i
                }
            }
        }

        private fun swap(a: IntArray, i: Int, j: Int) { val t = a[i]; a[i] = a[j]; a[j] = t }
    }
}
