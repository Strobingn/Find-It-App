package com.example.data

import android.opengl.Matrix
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainFrustumCullerTest {
    @Test
    fun identityMvpKeepsCenterTileAndCullsFarTile() {
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)
        val planes = TerrainFrustumCuller.extractPlanes(mvp)
        val visible = TerrainFrustumCuller.Aabb(-0.2f, -0.2f, -0.1f, 0.2f, 0.2f, 0.1f)
        val hidden = TerrainFrustumCuller.Aabb(4f, 4f, 4f, 5f, 5f, 5f)
        assertTrue(TerrainFrustumCuller.isVisible(visible, planes))
        assertFalse(TerrainFrustumCuller.isVisible(hidden, planes))
    }

    @Test
    fun tileAabbCoversGridCorner() {
        val tile = TerrainSpatialTile(
            tileColumn = 0,
            tileRow = 0,
            startX = 0,
            startY = 0,
            endXInclusive = 10,
            endYInclusive = 10,
            validCellCount = 100,
            minElevation = 0f,
            maxElevation = 1f,
        )
        val aabb = TerrainFrustumCuller.tileAabb(tile, gridWidth = 100, gridHeight = 100)
        assertTrue(aabb.minX < aabb.maxX)
        assertTrue(aabb.minY < aabb.maxY)
    }
}
