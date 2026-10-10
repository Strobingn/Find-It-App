package com.example.data

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Full-resolution DEM packed for an ES 3.0 R32F height texture. */
data class HeightmapTexture(
    val width: Int,
    val height: Int,
    val floats: FloatArray,
    val cellSizeMeters: Float,
)

data class CdlodNode(
    val level: Int,
    val originX: Int,
    val originY: Int,
    val sizeCells: Int,
) {
    val isFullResolution: Boolean get() = level == 0
}

/**
 * CDLOD selection over a regular DEM. Level 0 is one vertex per source cell — nearest nodes
 * keep the full-resolution surface. Farther nodes double the step so triangle count drops
 * without ever replacing the height texture.
 */
object HeightmapCdlod {
    fun pack(grid: ElevationGrid): HeightmapTexture {
        val floats = FloatArray(grid.width * grid.height)
        for (index in floats.indices) {
            val z = grid.bareEarth[index]
            floats[index] = if (grid.validData[index] && z.isFinite()) z else Float.NaN
        }
        return HeightmapTexture(grid.width, grid.height, floats, grid.cellSizeMeters)
    }

    /**
     * [rangeInCells] is the camera distance expressed in DEM cells. Nodes whose center is
     * closer than `baseRange * 2^level` stay at that level. The center of the view always
     * includes at least one level-0 node when [rangeInCells] is finite.
     */
    fun select(
        width: Int,
        height: Int,
        rangeInCells: Float,
        maxLevel: Int = 4,
        rootSize: Int = 32,
    ): List<CdlodNode> {
        val safeRange = rangeInCells.coerceAtLeast(1f)
        val size = rootSize.coerceIn(8, 128)
        val nodes = ArrayList<CdlodNode>()
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val nodeW = min(size, width - x)
                val nodeH = min(size, height - y)
                val cx = x + nodeW * 0.5f
                val cy = y + nodeH * 0.5f
                val viewX = width * 0.5f
                val viewY = height * 0.5f
                val dx = cx - viewX
                val dy = cy - viewY
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                val level = min(
                    maxLevel,
                    max(0, (ceil((dist / safeRange).toDouble()).toInt() - 1)),
                )
                val step = 1 shl level
                nodes += CdlodNode(level, x, y, max(nodeW, nodeH).coerceAtLeast(step))
                x += size
            }
            y += size
        }
        return nodes
    }

    /** Vertices along one edge of a node. Level 0 → one vertex per cell (full DEM). */
    fun vertexStep(node: CdlodNode): Int = 1 shl node.level

    fun vertexCount(node: CdlodNode, gridWidth: Int, gridHeight: Int): Int {
        val step = vertexStep(node)
        val cellsX = min(node.sizeCells, gridWidth - node.originX).coerceAtLeast(1)
        val cellsY = min(node.sizeCells, gridHeight - node.originY).coerceAtLeast(1)
        val vx = cellsX / step + 1
        val vy = cellsY / step + 1
        return vx * vy
    }

    /**
     * Builds a 32-bit-index grid for [node] that samples [heightmap] at every vertex.
     * Full-resolution nodes (level 0) emit one vertex per DEM cell.
     */
    fun meshNode(
        heightmap: HeightmapTexture,
        node: CdlodNode,
    ): Pair<FloatArray, IntArray> {
        val step = vertexStep(node)
        val maxX = min(heightmap.width - 1, node.originX + node.sizeCells)
        val maxY = min(heightmap.height - 1, node.originY + node.sizeCells)
        val cols = ((maxX - node.originX) / step) + 1
        val rows = ((maxY - node.originY) / step) + 1
        require(cols >= 2 && rows >= 2)
        val vertices = FloatArray(cols * rows * TerrainGpuBatch.FLOATS_PER_VERTEX)
        var cursor = 0
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val gx = (node.originX + col * step).coerceIn(0, heightmap.width - 1)
                val gy = (node.originY + row * step).coerceIn(0, heightmap.height - 1)
                val z = heightmap.floats[gy * heightmap.width + gx]
                val elevation = if (z.isFinite()) z else 0f
                vertices[cursor++] = if (heightmap.width > 1) gx.toFloat() / (heightmap.width - 1) * 2f - 1f else 0f
                vertices[cursor++] = if (heightmap.height > 1) 1f - gy.toFloat() / (heightmap.height - 1) * 2f else 0f
                vertices[cursor++] = elevation
                vertices[cursor++] = 0f
                vertices[cursor++] = 0f
                vertices[cursor++] = 1f
            }
        }
        val indices = IntArray((cols - 1) * (rows - 1) * 6)
        var index = 0
        for (row in 0 until rows - 1) {
            for (col in 0 until cols - 1) {
                val i00 = row * cols + col
                val i10 = i00 + 1
                val i01 = i00 + cols
                val i11 = i01 + 1
                indices[index++] = i00
                indices[index++] = i01
                indices[index++] = i10
                indices[index++] = i10
                indices[index++] = i01
                indices[index++] = i11
            }
        }
        return vertices to indices
    }

    fun rangeInCellsForZoom(zoom: Float, gridMaxSide: Int): Float {
        val safeZoom = zoom.coerceAtLeast(0.25f)
        return (gridMaxSide / (2f * safeZoom)).coerceAtLeast(8f)
    }
}

/** Horn 3×3 hillshade (and placeholders for SVF/LRM) runnable on CPU; GLSL 3.10 lives beside it. */
object GpuAnalysisKernels {
    const val COMPUTE_GLSL_VERSION = "310 es"

    /** GLES 3.1 compute shader. Neighbors missing → center (never 0). NaN → alpha 0. */
    const val HORN_HILLSHADE_COMPUTE = """
        #version 310 es
        layout (local_size_x = 8, local_size_y = 8) in;
        layout (r32f, binding = 0) readonly uniform highp image2D uHeight;
        layout (rgba8, binding = 1) writeonly uniform lowp image2D uShade;
        uniform float uCell;
        uniform vec3 uLight;
        float sampleZ(ivec2 p, ivec2 size, float center) {
            if (p.x < 0 || p.y < 0 || p.x >= size.x || p.y >= size.y) return center;
            float z = imageLoad(uHeight, p).r;
            return isnan(z) ? center : z;
        }
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = imageSize(uHeight);
            if (p.x >= size.x || p.y >= size.y) return;
            float c = imageLoad(uHeight, p).r;
            if (isnan(c)) {
                imageStore(uShade, p, vec4(0.0));
                return;
            }
            float z00 = sampleZ(p + ivec2(-1, -1), size, c);
            float z01 = sampleZ(p + ivec2( 0, -1), size, c);
            float z02 = sampleZ(p + ivec2( 1, -1), size, c);
            float z10 = sampleZ(p + ivec2(-1,  0), size, c);
            float z12 = sampleZ(p + ivec2( 1,  0), size, c);
            float z20 = sampleZ(p + ivec2(-1,  1), size, c);
            float z21 = sampleZ(p + ivec2( 0,  1), size, c);
            float z22 = sampleZ(p + ivec2( 1,  1), size, c);
            float gx = ((z02 + 2.0 * z12 + z22) - (z00 + 2.0 * z10 + z20)) / (8.0 * uCell);
            float gy = ((z20 + 2.0 * z21 + z22) - (z00 + 2.0 * z01 + z02)) / (8.0 * uCell);
            vec3 n = normalize(vec3(-gx, -gy, 1.0));
            float shade = clamp(dot(n, normalize(uLight)), 0.0, 1.0);
            imageStore(uShade, p, vec4(vec3(0.18 + shade * 0.82), 1.0));
        }
    """

    fun hornShadeCpu(
        elevations: FloatArray,
        valid: BooleanArray,
        width: Int,
        height: Int,
        cell: Float,
        lightX: Float = -0.45f,
        lightY: Float = -0.65f,
        lightZ: Float = 0.8f,
    ): FloatArray {
        val out = FloatArray(width * height)
        val inv = 1f / (8f * cell.coerceAtLeast(0.001f))
        fun zAt(x: Int, y: Int, center: Float): Float {
            if (x !in 0 until width || y !in 0 until height) return center
            val i = y * width + x
            if (!valid[i]) return center
            val z = elevations[i]
            return if (z.isFinite()) z else center
        }
        val len = kotlin.math.sqrt(lightX * lightX + lightY * lightY + lightZ * lightZ).coerceAtLeast(1e-5f)
        val lx = lightX / len
        val ly = lightY / len
        val lz = lightZ / len
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (!valid[i] || !elevations[i].isFinite()) {
                    out[i] = Float.NaN
                    continue
                }
                val c = elevations[i]
                val gx = ((zAt(x + 1, y - 1, c) + 2f * zAt(x + 1, y, c) + zAt(x + 1, y + 1, c)) -
                    (zAt(x - 1, y - 1, c) + 2f * zAt(x - 1, y, c) + zAt(x - 1, y + 1, c))) * inv
                val gy = ((zAt(x - 1, y + 1, c) + 2f * zAt(x, y + 1, c) + zAt(x + 1, y + 1, c)) -
                    (zAt(x - 1, y - 1, c) + 2f * zAt(x, y - 1, c) + zAt(x + 1, y - 1, c))) * inv
                val nx = -gx
                val ny = -gy
                val nz = 1f
                val nlen = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                val shade = ((nx / nlen) * lx + (ny / nlen) * ly + (nz / nlen) * lz).coerceIn(0f, 1f)
                out[i] = 0.18f + shade * 0.82f
            }
        }
        return out
    }
}
