package com.example.data

import java.io.File
import java.util.Locale

/** On-device / host comparison of laszip4j vs laz-rs on the same file, plus cache reopen. */
data class DecoderBenchmarkResult(
    val fileName: String,
    val points: Long,
    val laszip4jMs: Double,
    val nativeMs: Double?,
    val nativeAvailable: Boolean,
    val cacheWriteMs: Double,
    val cacheReadMs: Double,
    val rasterizeMs: Double,
    val meshMs: Double,
    val hillshadeMs: Double?,
    val cdlodMs: Double,
    val memory: TerrainMemorySnapshot,
    val winner: String,
    val firstReadMs: Double = laszip4jMs,
    val trials: List<DecoderTrial> = emptyList(),
    val recommendedBackend: LazDecoderBackend = LazDecoderBackend.LASZIP4J,
    val recommendedWorkers: Int = 1,
    val tileTrials: List<TerrainTileTrial> = emptyList(),
) {
    val recommendedTileCells: Int get() = tileTrials.filter { it.failure==null }.minByOrNull { it.coldMs+4*it.revisitMs }?.cells ?: 512

    fun format(): String = buildString {
        appendLine("Decoder benchmark · $fileName")
        appendLine("  device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · heap ${Runtime.getRuntime().maxMemory()/1048576} MiB")
        appendLine(String.format(Locale.US,"  first app read      %.2f ms (includes missing index; OS cache uncontrolled)",firstReadMs))
        appendLine("  repeated reads: alternating backend/worker order; decoded-point cache bypassed")
        trials.forEach { trial -> appendLine(String.format(Locale.US,"  %-12s workers=%d (asked %d) n=%d median=%.2f ms range=%.2f–%.2f",trial.backend.name,trial.actualWorkers,trial.requestedWorkers,trial.samples.size,trial.medianMs,trial.samples.minOrNull()?:0.0,trial.samples.maxOrNull()?:0.0)) }

        appendLine(String.format(Locale.US, "  points              %d", points))
        appendLine(String.format(Locale.US, "  laszip4j            %.2f ms", laszip4jMs))
        if (nativeAvailable && nativeMs != null) {
            appendLine(String.format(Locale.US, "  laz-rs native       %.2f ms", nativeMs))
            appendLine("  winner              $winner")
        } else {
            appendLine("  laz-rs native       unavailable/rejected for this workload")
        }
        appendLine(String.format(Locale.US, "  Java decode+grid    %.2f ms", rasterizeMs))
        appendLine(String.format(Locale.US, "  height preparation            %.2f ms", meshMs))
        appendLine(String.format(Locale.US, "  adaptive LOD select        %.2f ms", cdlodMs))
        if (hillshadeMs != null) {
            appendLine(String.format(Locale.US, "  hillshade CPU       %.2f ms", hillshadeMs))
        }
        tileTrials.forEach { t -> if(t.failure!=null) appendLine("  tiles ${t.cells} unavailable: ${t.failure}") else appendLine(String.format(Locale.US,"  tiles %d · empty DEM-cache %.2f ms · revisit %.2f ms · reused=%d",t.cells,t.coldMs,t.revisitMs,t.reused)) }
        appendLine(String.format(Locale.US, "  cache write         %.2f ms", cacheWriteMs))
        appendLine(String.format(Locale.US, "  cache reopen        %.2f ms", cacheReadMs))
        appendLine("  ${memory.format()}")
        append("Recommended ${recommendedBackend.name} · $recommendedWorkers workers. Tiles $recommendedTileCells (cold + four revisits). Apply measured settings to use this profile.")
    }
}

data class TerrainTileTrial(val cells: Int,val coldMs: Double,val revisitMs: Double,val reused: Int,val failure: String? = null)

data class DecoderTrial(val backend: LazDecoderBackend, val requestedWorkers: Int, val actualWorkers: Int, val samples: List<Double>) {
    val medianMs: Double get() = samples.sorted().let { if(it.isEmpty()) Double.POSITIVE_INFINITY else if(it.size%2==0) (it[it.size/2-1]+it[it.size/2])/2 else it[it.size/2] }
}

object TerrainDecoderBenchmark {
    internal fun equivalent(a: DemGenerator.LasLoadResult, b: DemGenerator.LasLoadResult): Boolean =
        a.grid.width == b.grid.width && a.grid.height == b.grid.height &&
            a.decodedPoints == b.decodedPoints && a.sampledPoints == b.sampledPoints && a.groundPointsUsed == b.groundPointsUsed &&
            a.appliedGroundMode == b.appliedGroundMode &&
            a.grid.cellSizeXMeters == b.grid.cellSizeXMeters && a.grid.cellSizeYMeters == b.grid.cellSizeYMeters &&
            a.grid.bareEarth.contentEquals(b.grid.bareEarth) && a.grid.canopySpikes.contentEquals(b.grid.canopySpikes) &&
            a.grid.validData.contentEquals(b.grid.validData) && a.grid.interpolatedData.contentEquals(b.grid.interpolatedData)

    fun run(
        file: File,
        options: LidarImportOptions = LidarImportOptions(),
        cacheDir: File,
        shouldContinue: () -> Boolean = { true },
        repeats: Int = 3,
        onProgress: (String) -> Unit = {},
    ): DecoderBenchmarkResult {
        require(repeats in 3..9)
        TerrainStageProfiler.reset()
        val sanitized=options.sanitized()
        onProgress("First app read / index preparation…")
        val firstStart=System.nanoTime()
        val reference=requireNotNull(LazTerrainReader.readForBenchmark(file,sanitized,LazDecoderBackend.LASZIP4J,shouldContinue=shouldContinue))
        val firstMs=(System.nanoTime()-firstStart)/1_000_000.0
        val chunks=LazTerrainReader.lastBenchmarkWork()?.chunks ?: 1
        val nativeAvailable=file.extension.equals("laz",true) && LazRsNativeDecoder.isAvailable
        val workers=listOf(2,4,6).map { minOf(it,Runtime.getRuntime().availableProcessors().coerceAtLeast(1),chunks).coerceAtLeast(1) }.distinct()
        val backends=if(nativeAvailable) listOf(LazDecoderBackend.LASZIP4J,LazDecoderBackend.NATIVE_LAZ_RS) else listOf(LazDecoderBackend.LASZIP4J)
        val cases=backends.flatMap { backend -> workers.map { backend to it } }
        val timings=cases.associateWith { mutableListOf<Double>() }
        val actual=HashMap<Pair<LazDecoderBackend,Int>,Int>()
        val rejected=HashSet<Pair<LazDecoderBackend,Int>>()
        var completed=0
        for(round in 0 until repeats) {
            // Counterbalance OS cache, allocation and thermal/order effects. No privileged cache flush is claimed.
            val order=if(round%2==0) cases else cases.reversed()
            for(case in order) {
                if(!shouldContinue()) throw java.util.concurrent.CancellationException("Benchmark cancelled")
                onProgress("Read ${++completed}/${repeats*cases.size} · ${case.first.name} · ${case.second} workers")
                if(case in rejected) continue
                val start=System.nanoTime()
                try {
                    val result=requireNotNull(LazTerrainReader.readForBenchmark(file,sanitized,case.first,workers=case.second,shouldContinue=shouldContinue))
                    val elapsed=(System.nanoTime()-start)/1_000_000.0
                    check(case.first!=LazDecoderBackend.NATIVE_LAZ_RS || result.note.contains("native integer")) { "Native fell back to Java" }
                    check(equivalent(reference,result)) { "DEM/coverage/provenance differs from reference" }
                    timings.getValue(case).add(elapsed)
                    actual[case]=LazTerrainReader.lastBenchmarkWork()?.workers ?: 1
                } catch(error: Exception) {
                    if(error is java.util.concurrent.CancellationException) throw error
                    rejected.add(case)
                    TerrainStageProfiler.emit("Benchmark rejected $case: ${error.message}")
                }
            }
        }
        val trials=cases.filter { it !in rejected && timings.getValue(it).size==repeats }.map {
            DecoderTrial(it.first,it.second,actual.getValue(it),timings.getValue(it).toList())
        }
        val javaTrial=trials.filter { it.backend==LazDecoderBackend.LASZIP4J }.minByOrNull { it.medianMs }
            ?: error("No correct Java benchmark trial completed")
        val native=trials.filter { it.backend==LazDecoderBackend.NATIVE_LAZ_RS }.minByOrNull { it.medianMs }
        // Prefer Java when medians are within 5%; a small noisy difference does not justify changing the backend.
        val best=if(native!=null && native.medianMs<javaTrial.medianMs*.95) native else javaTrial
        fun timed(block: () -> Unit): Double { val n=System.nanoTime(); block(); return (System.nanoTime()-n)/1_000_000.0 }
        onProgress("Preparing GPU height data and cache scenarios…")
        lateinit var scene: TerrainGpuScene
        val heightMs=timed { scene=TerrainGpuSceneBuilder.buildHeightfield(reference.grid); TerrainMemoryMonitor.noteGpuScene(scene) }
        val identity=FloatArray(16).apply { this[0]=1f;this[5]=1f;this[10]=1f;this[15]=1f }
        val height=checkNotNull(scene.heightmap)
        var lo=Float.POSITIVE_INFINITY; var hi=Float.NEGATIVE_INFINITY
        for(z in height.floats) if(z.isFinite()) { lo=minOf(lo,z);hi=maxOf(hi,z) }
        if(!lo.isFinite()) { lo=0f;hi=0f }
        val lodMs=timed { AdaptiveTerrainLod.select(height,identity,1080,1920,lo,hi) }
        val hillshade=runCatching { timed { reference.grid.renderHillshade(315f,35f,1f,0,contrast=1f).recycle() } }.getOrNull()
        val disk=LazTerrainDiskCache(cacheDir,64L*1024*1024)
        val terrain=DemGenerator.TerrainLoadResult(reference.grid,reference.note,reference.appliedGroundMode!=GroundSurfaceMode.SURFACE_MODEL)
        val writeMs=timed { disk.put(file,sanitized,terrain) }
        val readMs=timed { requireNotNull(disk.get(file,sanitized)) }
        val tileTrials=ArrayList<TerrainTileTrial>()
        val tileRoot=File.createTempFile("tile-comparison-",".tmp",cacheDir.also { it.mkdirs() }).apply { check(delete());check(mkdir()) }
        try {
            val focus=sanitized.focusBounds ?: run {
                val g=reference.grid
                var selected=0;var distance=Double.POSITIVE_INFINITY
                for(i in g.bareEarth.indices) if(g.validData[i] && g.bareEarth[i].isFinite()) {
                    val x=i%g.width-g.width/2.0;val y=i/g.width-g.height/2.0
                    val d=x*x+y*y;if(d<distance) { distance=d;selected=i }
                }
                val x=(selected%g.width).toDouble()/(g.width-1).coerceAtLeast(1)
                val y=(selected/g.width).toDouble()/(g.height-1).coerceAtLeast(1)
                NormalizedRasterBounds((x-.1).coerceAtLeast(0.0),(y-.1).coerceAtLeast(0.0),
                    (x+.1).coerceAtMost(1.0),(y+.1).coerceAtMost(1.0))
            }
            val tileOptions=sanitized.copy(focusBounds=focus,rasterResolution=minOf(sanitized.rasterResolution,256))
            for(cells in listOf(256,512)) {
                if(!shouldContinue()) throw java.util.concurrent.CancellationException()
                onProgress("Terrain cache comparison · $cells-node tiles")
                LazTerrainReader.clearDecodedChunks()
                try {
                    val tiles=TerrainTileCache(File(tileRoot,"$cells"),cells)
                    var cold: TerrainTileCache.Outcome?=null;var reopened: TerrainTileCache.Outcome?=null
                    val coldMs=timed { cold=tiles.refine(file,tileOptions,shouldContinue) }
                    val warmMs=timed { reopened=tiles.refine(file,tileOptions,shouldContinue) }
                    check(cold!=null && reopened!=null) { "No terrain available in cache comparison crop" }
                    check(cold!!.terrain.grid.bareEarth.contentEquals(reopened!!.terrain.grid.bareEarth))
                    check(cold!!.terrain.grid.validData.contentEquals(reopened!!.terrain.grid.validData))
                    check(reopened!!.misses==0) { "Revisit unexpectedly decoded source" }
                    tileTrials.add(TerrainTileTrial(cells,coldMs,warmMs,reopened!!.hits))
                } catch(error: OutOfMemoryError) {
                    AppMemoryBudget.onOutOfMemory()
                    tileTrials.add(TerrainTileTrial(cells,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,0,"memory pressure"))
                } catch(error: Exception) {
                    if(error is java.util.concurrent.CancellationException) throw error
                    tileTrials.add(TerrainTileTrial(cells,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,0,error.message?:"comparison failed"))
                }
            }
        } finally { tileRoot.deleteRecursively() }
        return DecoderBenchmarkResult(file.name,reference.decodedPoints,javaTrial.medianMs,native?.medianMs,nativeAvailable,
            writeMs,readMs,javaTrial.medianMs,heightMs,hillshade,lodMs,TerrainMemoryMonitor.snapshot(),best.backend.name,
            firstMs,trials,best.backend,best.actualWorkers,tileTrials).also { TerrainStageProfiler.emit(it.format()); onProgress("Benchmark complete") }
    }
}
