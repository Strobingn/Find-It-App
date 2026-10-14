package com.example.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TerrainPipelineRegressionTest {
    @Test fun nativeIntegerRangesMatchJavaForFullAndFocusedClouds() {
        if (!LazRsNativeDecoder.isAvailable) return
        val root=Files.createTempDirectory("native-parity").toFile()
        try {
            val file=File(root,"strips.laz")
            requireNotNull(javaClass.getResourceAsStream("/laz/viewport-chunks.laz")).use { input -> file.outputStream().use { input.copyTo(it) } }
            for(mode in GroundSurfaceMode.entries) for(focus in listOf(null,NormalizedRasterBounds(.15,.65,.45,.85))) {
                val options=LidarImportOptions(groundMode=mode,rasterResolution=128,focusBounds=focus)
                val java=LazTerrainReader.readForBenchmark(file,options,LazDecoderBackend.LASZIP4J){true}!!
                val native=LazTerrainReader.readForBenchmark(file,options,LazDecoderBackend.NATIVE_LAZ_RS){true}!!
                assertTrue(native.note.contains("native integer"))
                assertTrue("$mode $focus",TerrainDecoderBenchmark.equivalent(java,native))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun adjacentCropsReuseStableTilesAndColdDiskCache() {
        val root=Files.createTempDirectory("tile-reuse").toFile()
        try {
            val source=LasFixture.writeLattice(File(root,"surface.las"),129,129,spacingMeters=1.0)
            val directory=File(root,"tiles")
            val cache=TerrainTileCache(directory)
            val options=LidarImportOptions(rasterResolution=128,focusBounds=NormalizedRasterBounds(.05,.05,.8,.8))
            val first=cache.refine(source,options)!!
            assertTrue(first.misses>0)
            val shifted=cache.refine(source,options.copy(focusBounds=NormalizedRasterBounds(.1,.1,.85,.85)))!!
            assertEquals(0,shifted.misses)
            assertTrue(shifted.hits>0)
            val cold=TerrainTileCache(directory).refine(source,options)!!
            assertEquals(0,cold.misses)
            assertArrayEquals(first.terrain.grid.bareEarth,cold.terrain.grid.bareEarth,0f)
            assertArrayEquals(first.terrain.grid.interpolatedData,cold.terrain.grid.interpolatedData)
            val a=TerrainTileCache.plan(128.0,128.0,options.focusBounds!!,128)
            val b=TerrainTileCache.plan(128.0,128.0,NormalizedRasterBounds(.1,.1,.85,.85),128)
            for(y in maxOf(a.y0,b.y0)..minOf(a.y1,b.y1)) for(x in maxOf(a.x0,b.x0)..minOf(a.x1,b.x1)) {
                val ai=(y-a.y0)*a.width+x-a.x0; val bi=(y-b.y0)*b.width+x-b.x0
                assertEquals(first.terrain.grid.bareEarth[ai],shifted.terrain.grid.bareEarth[bi],0f)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun cancelledOrCorruptSourceCannotPublishATerrainTile() {
        val root=Files.createTempDirectory("bad-tile").toFile()
        try {
            val source=File(root,"broken.laz").apply { writeText("broken") }
            val cache=TerrainTileCache(File(root,"tiles"))
            assertNull(cache.refine(source,LidarImportOptions(focusBounds=NormalizedRasterBounds.Full),shouldContinue={false}))
            assertTrue(File(root,"tiles").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun opennessUsesMetresAndDistinguishesPitsFromRidges() {
        val w=65; val h=65
        val plane=ElevationGrid(w,h,FloatArray(w*h){ i -> 20f+(i%w)*.25f+(i/w)*.5f },FloatArray(w*h),
            cellSizeXMeters=.5f,cellSizeYMeters=1f)
        val flat=TerrainOpenness.compute(plane,1f,6f,false){true}
        assertEquals((Math.PI/2).toFloat(),flat[32*w+32],.00001f)
        val heights=FloatArray(w*h){30f}.apply { this[32*w+32]=28f }
        val pit=ElevationGrid(w,h,heights,FloatArray(w*h),cellSizeXMeters=.5f,cellSizeYMeters=1f)
        val positive=TerrainOpenness.compute(pit,1f,6f,false){true}
        val negative=TerrainOpenness.compute(pit,1f,6f,true){true}
        assertTrue(positive[32*w+32] < (Math.PI/2).toFloat())
        assertTrue(negative[32*w+32] > (Math.PI/2).toFloat())
        assertEquals(28f,pit.bareEarth[32*w+32],0f)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            TerrainOpenness.compute(pit,1f,6f,false){false}
        }
    }

    @Test fun relativeHeightEncodingHasBoundedErrorAndNoGlobalXYRounding() {
        val base=500f; val range=100f
        for(i in 0..10000) {
            val z=base+i*.01f
            val q=TerrainHeightEncoding.encode(z,base,range)
            val restored=TerrainHeightEncoding.decode(q,base,range)
            assertEquals(z,restored,Math.ulp(z)+range/TerrainHeightEncoding.MAX)
        }
        assertTrue(NormalizedRasterBounds(.5,.5,.50001,.50001).sanitized().right < .50002)
        val grid=ElevationGrid(128,64,FloatArray(128*64){40f},FloatArray(128*64),cellSizeMeters=2f,cellSizeXMeters=1f,cellSizeYMeters=2f)
        val scene=TerrainGpuSceneBuilder.buildHeightfield(grid)
        assertTrue(scene.levels.all { it.batches.isEmpty() })
        assertEquals(1f,scene.heightmap!!.cellSizeXMeters,0f)
        assertEquals(2f,scene.heightmap.cellSizeYMeters,0f)
    }
}
