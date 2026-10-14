package com.example.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class TerrainResearchCompletionTest {
    private fun matrix()=FloatArray(16).apply { this[0]=1f;this[5]=1f;this[10]=1f;this[15]=1f }
    @Test fun skyViewFactorIsHorizontalVisibilityAndLeavesReturnsUnchanged() {
        val w=33;val index=16*w+16
        val heights=FloatArray(w*w){30f}
        val flat=ElevationGrid(w,w,heights,FloatArray(w*w),cellSizeXMeters=.5f,cellSizeYMeters=1f)
        assertEquals(1f,TerrainOpenness.compute(flat,1f,6f,false,skyView=true){true}[index],0f)
        val pit=ElevationGrid(w,w,heights.copyOf().apply { this[index]=28f },FloatArray(w*w),cellSizeXMeters=.5f,cellSizeYMeters=1f)
        val before=pit.bareEarth.copyOf()
        assertTrue(TerrainOpenness.compute(pit,1f,6f,false,skyView=true){true}[index]<.3f)
        assertArrayEquals(before,pit.bareEarth,0f)
        assertTrue(TerrainOpenness.compute(flat,1f,.1f,false,skyView=true){true}[index].isNaN())
    }
    @Test fun derivedFieldsReopenValidateUnitsMasksAndRejectCorruptionCancellation() {
        val root=Files.createTempDirectory("derived-fields").toFile()
        try {
            var computes=0
            fun grid(cell: Float=1f, interpolated: Boolean=false)=ElevationGrid(8,8,FloatArray(64){40f},FloatArray(64),
                interpolatedData=BooleanArray(64){interpolated},cellSizeXMeters=cell,cellSizeYMeters=2f)
            fun load(cache: TerrainDerivedFieldCache,g: ElevationGrid)=cache.field(g,"test",1f,6f,{true}) { computes++;FloatArray(64){it*.1f} }
            val first=load(TerrainDerivedFieldCache(root),grid())
            assertArrayEquals(first,load(TerrainDerivedFieldCache(root),grid()),0f);assertEquals(1,computes)
            load(TerrainDerivedFieldCache(root),grid(2f));assertEquals(2,computes)
            load(TerrainDerivedFieldCache(root),grid(interpolated=true));assertEquals(3,computes)
            root.listFiles()!!.forEach { file -> RandomAccessFile(file,"rw").use { it.seek(20);it.writeInt(0x1234) } }
            assertArrayEquals(first,load(TerrainDerivedFieldCache(root),grid()),0f);assertEquals(4,computes)
            val count=root.listFiles()!!.size
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                TerrainDerivedFieldCache(root).field(grid(),"cancelled",1f,4f,{false}){FloatArray(64)}
            }
            assertEquals(count,root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun adaptiveQuadtreeHasSeveralBalancedLevelsAndNoOverlap() {
        val h=HeightmapTexture(1025,513,FloatArray(1025*513){40f},2f,.5f,2f)
        val m=matrix().apply { this[3]=.8f }
        val plan=AdaptiveTerrainLod.select(h,m,256,256,40f,40f)
        assertTrue(plan.patches.map { it.level }.distinct().size>1)
        assertTrue(plan.triangles<(1024L*512*2))
        for(i in plan.patches.indices) for(j in i+1 until plan.patches.size) {
            val a=plan.patches[i];val b=plan.patches[j]
            if(AdaptiveTerrainLod.touching(a,b)) assertTrue(abs(a.level-b.level)<=1)
            assertFalse(maxOf(a.x,b.x)<minOf(a.x+a.size,b.x+b.size) && maxOf(a.y,b.y)<minOf(a.y+a.size,b.y+b.size))
        }
    }
    @Test fun nodataForcesFineCoverageAndNeverCreatesParentHeights() {
        val w=513;val values=FloatArray(w*w){40f}.apply { this[100*w+100]=Float.NaN }
        val h=HeightmapTexture(w,w,values,1f)
        val m=matrix()
        val plan=AdaptiveTerrainLod.select(h,m,128,128,40f,40f)
        assertEquals(0,plan.patches.single { 100 in it.x until it.x+it.size && 100 in it.y until it.y+it.size }.level)
        val p=AdaptiveTerrainLod.Patch(64,64,0)
        // The first parent (100,100) is missing; (101,101) must remain a real height.
        val vertex=AdaptiveTerrainLod.vertex(h,p,37,37,3,m,128,128,0f)
        assertEquals(40f,vertex[2],0f)
        assertTrue(AdaptiveTerrainLod.select(h.copy(floats=FloatArray(w*w){Float.NaN}),m,128,128,0f,0f).patches.isEmpty())
    }
    @Test fun geomorphedFineEdgesMatchCoarseVerticesExactlyAtEveryTransition() {
        val w=513;val h=HeightmapTexture(w,w,FloatArray(w*w){40f+sin((it%w)*.07f)+sin((it/w)*.06f)},1f)
        for(scale in listOf(.3f,1f,3f,8f)) {
            val m=matrix().apply { this[0]=scale;this[5]=scale }
            val fine=AdaptiveTerrainLod.Patch(64,0,0,edges=2)
            val coarse=AdaptiveTerrainLod.Patch(128,0,1)
            for(y in 0..64) {
                val a=AdaptiveTerrainLod.vertex(h,fine,64,y,3,m,256,256,38f)
                val b=AdaptiveTerrainLod.vertex(h,coarse,0,y/2,3,m,256,256,38f)
                assertArrayEquals(a,b,0f)
            }
        }
    }
    @Test fun localReliefAndCacheSharesRespectPhysicalAxesAndTotalBudget() {
        val w=33;val values=FloatArray(w*w) { i -> val x=(i%w-16)*.5f;val y=(i/w-16)*1f;x*x+y*y }
        val grid=ElevationGrid(w,w,values,FloatArray(w*w),cellSizeXMeters=.5f,cellSizeYMeters=1f)
        val statistics=grid.localStatistics(values,6,3) // 3 m in each axis
        assertEquals(-7.5f,statistics.residual[16*w+16],.00001f)
        val previous=AppMemoryBudget.lowMemoryOverride
        try {
            for(low in listOf(false,true)) {
                AppMemoryBudget.lowMemoryOverride=low
                val total=AppMemoryBudget.terrainMemoryCacheBytes()+AppMemoryBudget.derivedLayerMemoryCacheBytes()
                val limit=AppMemoryBudget.persistentCacheBudgetBytes/(if(low) 2 else 1)
                assertTrue("Cache shares exceed common budget",total<=limit)
            }
        } finally { AppMemoryBudget.lowMemoryOverride=previous }
    }
    @Test fun fourTileBoundaryPreservesMeasuredPitAndMatchesFullSurface() {
        val root=Files.createTempDirectory("four-tile").toFile()
        try {
            val source=LasFixture.writeLattice(File(root,"lattice.las"),129,129,spacingMeters=1.0)
            RandomAccessFile(source,"rw").use { f ->
                f.seek(227L+(64*129+64)*20+8)
                // LAS point coordinates are little endian.
                f.write(byteArrayOf(0xf0.toByte(),0x0a,0,0)) // 28.00 m
            }
            val focus=NormalizedRasterBounds(.45,.45,.55,.55)
            val options=LidarImportOptions(rasterResolution=128,focusBounds=focus,fixedWidth=128,fixedHeight=128)
            val plan=TerrainTileCache.plan(128.0,128.0,focus,128)
            assertEquals(1024,plan.nx)
            val cache=TerrainTileCache(File(root,"tiles"))
            val first=cache.refine(source,options)!!
            assertEquals(4,first.misses)
            val full=LazTerrainReader.readPrepared(source,LidarImportOptions(rasterResolution=1025,
                focusBounds=NormalizedRasterBounds.Full,fixedWidth=1025,fixedHeight=1025),{true},null)!!.grid
            val actual=first.terrain.grid
            val pit=(512-plan.y0)*plan.width+512-plan.x0
            assertEquals(28f,actual.bareEarth[pit],0f);assertFalse(actual.interpolatedData[pit])
            var maxFilledDifference=0f
            for(y in plan.y0..plan.y1) for(x in plan.x0..plan.x1) {
                val local=(y-plan.y0)*plan.width+x-plan.x0;val reference=y*1025+x
                assertEquals(full.validData[reference],actual.validData[local])
                if(!actual.interpolatedData[local]) assertEquals(full.bareEarth[reference],actual.bareEarth[local],0f)
                else maxFilledDifference=maxOf(maxFilledDifference,abs(full.bareEarth[reference]-actual.bareEarth[local]))
            }
            assertTrue("Filled context deviation $maxFilledDifference metres",maxFilledDifference<.01f)
            val cold=TerrainTileCache(File(root,"tiles")).refine(source,options)!!
            assertEquals(0,cold.misses);assertArrayEquals(actual.bareEarth,cold.terrain.grid.bareEarth,0f)
            val smaller=TerrainTileCache(File(root,"tiles"),256).refine(source,options)!!
            assertEquals(28f,smaller.terrain.grid.bareEarth[pit],0f)
            val repeated=TerrainTileCache(File(root,"tiles")).refine(source,options)!!
            assertEquals(0,repeated.misses);assertArrayEquals(actual.bareEarth,repeated.terrain.grid.bareEarth,0f)
        } finally { root.deleteRecursively() }
    }
    @Test fun outwardSnappingRespectsRasterTextureDimensionLimit() {
        val focus=NormalizedRasterBounds(4095.5/16384,4095.5/16384,6142.5/16384,6142.5/16384)
        val plan=TerrainTileCache.plan(128.0,128.0,focus,2048)
        assertTrue(plan.width<=2048 && plan.height<=2048)
        assertTrue(plan.bounds.left<=focus.left && plan.bounds.right>=focus.right)
    }
    @Test fun frameReportUsesNearestRankP95AndBoundedWindow() {
        val stats=TerrainFrameStats(100)
        for(i in 1..100) stats.record(i*1_000_000L,-1,0)
        assertTrue(stats.report().contains("p50=50.00"));assertTrue(stats.report().contains("p95=95.00"))
        stats.record(101_000_000L,-1,2)
        assertTrue(stats.report().contains("n=100"));assertTrue(stats.report().contains("callback drops=2"))
        stats.reset();assertTrue(stats.report().contains("n=0"))
    }
    @Test fun nativeRawRangeReusePreservesTheSameTerrainAndBudget() {
        if(!LazRsNativeDecoder.isAvailable) return
        val root=Files.createTempDirectory("native-reuse").toFile()
        val previous=LazDecoderSettings.preferred
        try {
            val file=File(root,"source.laz")
            requireNotNull(javaClass.getResourceAsStream("/laz/viewport-chunks.laz")).use { input -> file.outputStream().use { input.copyTo(it) } }
            LazDecoderSettings.preferred=LazDecoderBackend.NATIVE_LAZ_RS
            LazTerrainReader.clearDecodedChunks()
            val options=LidarImportOptions(rasterResolution=64)
            val first=LazTerrainReader.read(file,options)!!
            val second=LazTerrainReader.read(file,options)!!
            assertTrue(LazTerrainReader.nativeRangeCacheHits>0)
            assertTrue(TerrainDecoderBenchmark.equivalent(first,second))
        } finally { LazDecoderSettings.preferred=previous;LazTerrainReader.clearDecodedChunks();root.deleteRecursively() }
    }
    @Test fun sustainedHeatCapsWorkersWithoutChangingTerrainPolicy() {
        val old=AppMemoryBudget.thermalStatus
        try {
            AppMemoryBudget.thermalStatus=3;assertTrue(AppMemoryBudget.decodeWorkers(6)<=2)
            AppMemoryBudget.thermalStatus=4;assertEquals(1,AppMemoryBudget.decodeWorkers(6))
        } finally { AppMemoryBudget.thermalStatus=old }
    }
    @Test fun benchmarksUseTrueMediansAndRejectDecodedCountMismatch() {
        val trial=DecoderTrial(LazDecoderBackend.LASZIP4J,4,2,listOf(10.0,2.0,4.0,6.0))
        assertEquals(5.0,trial.medianMs,0.0)
        val grid=ElevationGrid(2,2,FloatArray(4){40f},FloatArray(4))
        val a=DemGenerator.LasLoadResult(grid,4,4,true,0,"test",decodedPoints=4)
        assertFalse(TerrainDecoderBenchmark.equivalent(a,a.copy(decodedPoints=5)))
    }
}
