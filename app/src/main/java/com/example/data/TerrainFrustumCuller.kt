package com.example.data

/**
 * Clip-space frustum test for GPU terrain tiles. Planes are extracted from the
 * combined model-view-projection matrix so the renderer can skip batches whose
 * axis-aligned bounds miss the camera.
 */
object TerrainFrustumCuller {
    data class Aabb(
        val minX: Float,
        val minY: Float,
        val minZ: Float,
        val maxX: Float,
        val maxY: Float,
        val maxZ: Float,
    )

    /** Six clip planes stored as (a, b, c, d) with inward normals. */
    fun extractPlanes(mvp: FloatArray): Array<FloatArray> {
        require(mvp.size >= 16)
        val m = mvp
        // Column-major Android Matrix layout.
        val planes = arrayOf(
            floatArrayOf(m[3] + m[0], m[7] + m[4], m[11] + m[8], m[15] + m[12]), // left
            floatArrayOf(m[3] - m[0], m[7] - m[4], m[11] - m[8], m[15] - m[12]), // right
            floatArrayOf(m[3] + m[1], m[7] + m[5], m[11] + m[9], m[15] + m[13]), // bottom
            floatArrayOf(m[3] - m[1], m[7] - m[5], m[11] - m[9], m[15] - m[13]), // top
            floatArrayOf(m[3] + m[2], m[7] + m[6], m[11] + m[10], m[15] + m[14]), // near
            floatArrayOf(m[3] - m[2], m[7] - m[6], m[11] - m[10], m[15] - m[14]), // far
        )
        for (plane in planes) {
            val length = kotlin.math.sqrt(plane[0] * plane[0] + plane[1] * plane[1] + plane[2] * plane[2])
                .coerceAtLeast(1e-6f)
            plane[0] /= length
            plane[1] /= length
            plane[2] /= length
            plane[3] /= length
        }
        return planes
    }

    fun isVisible(aabb: Aabb, planes: Array<FloatArray>): Boolean {
        for (plane in planes) {
            val px = if (plane[0] >= 0f) aabb.maxX else aabb.minX
            val py = if (plane[1] >= 0f) aabb.maxY else aabb.minY
            val pz = if (plane[2] >= 0f) aabb.maxZ else aabb.minZ
            if (plane[0] * px + plane[1] * py + plane[2] * pz + plane[3] < 0f) return false
        }
        return true
    }

    fun tileAabb(tile: TerrainSpatialTile, gridWidth: Int, gridHeight: Int): Aabb {
        val maxX = (gridWidth - 1).coerceAtLeast(1)
        val maxY = (gridHeight - 1).coerceAtLeast(1)
        fun ndcX(x: Int) = x.toFloat() / maxX * 2f - 1f
        fun ndcY(y: Int) = 1f - y.toFloat() / maxY * 2f
        val x0 = ndcX(tile.startX)
        val x1 = ndcX(tile.endXInclusive)
        val y0 = ndcY(tile.startY)
        val y1 = ndcY(tile.endYInclusive)
        return Aabb(
            minX = minOf(x0, x1),
            minY = minOf(y0, y1),
            minZ = -0.4f,
            maxX = maxOf(x0, x1),
            maxY = maxOf(y0, y1),
            maxZ = 0.4f,
        )
    }
}
