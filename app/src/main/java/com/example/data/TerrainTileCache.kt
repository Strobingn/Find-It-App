package com.example.data

import com.example.gps.crs.LasCrsReader
import java.io.File
import kotlin.math.*

/** Stable source-coordinate lattice. Each tile has fixed interpolation context, independent of pan. */
internal class TerrainTileCache(directory: File, private val tileCells: Int = TILE) {
    init { require(tileCells==256 || tileCells==512) }
    private val disk = LazTerrainDiskCache(if(tileCells==512) directory else File(directory,"tile-size-$tileCells"), 128L * 1024 * 1024)
    private val memory = LazTerrainMemoryCache(maxBytes = 24L * 1024 * 1024)
    data class Outcome(val terrain: DemGenerator.TerrainLoadResult, val bounds: NormalizedRasterBounds,
                       val hits: Int, val misses: Int)
    data class Plan(val nx: Int, val ny: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width get() = x1 - x0 + 1
        val height get() = y1 - y0 + 1
        val bounds get() = NormalizedRasterBounds(x0.toDouble() / nx, y0.toDouble() / ny,
            x1.toDouble() / nx, y1.toDouble() / ny)
    }
    fun refine(file: File, options: LidarImportOptions, shouldContinue: () -> Boolean = { true },
               progress: (Float, String) -> Unit = { _, _ -> }): Outcome? {
        val info = LasCrsReader.read(file) ?: return null
        val safe = if(options.fixedWidth!=null && options.fixedHeight!=null) options.sanitized()
            else LazTerrainReader.focusDecodeOptions(file, options.sanitized(), shouldContinue)
        val focus = safe.focusBounds ?: return null
        val plan = plan(info.maxX - info.minX, info.maxY - info.minY, focus, safe.rasterResolution)
        val units = LasTerrainUnits.read(file)
        val cellX = ((info.maxX - info.minX) / plan.nx * units.horizontal).toFloat()
        val cellY = ((info.maxY - info.minY) / plan.ny * units.horizontal).toFloat()
        val elevations = FloatArray(plan.width * plan.height) { Float.NaN }
        val canopy = FloatArray(elevations.size)
        val valid = BooleanArray(elevations.size)
        val interpolated = BooleanArray(elevations.size)
        val tiles = mutableListOf<Pair<Int, Int>>()
        for (ty in plan.y0.coerceAtMost(plan.ny-1) / tileCells..plan.y1.coerceAtMost(plan.ny-1) / tileCells) for (tx in plan.x0.coerceAtMost(plan.nx-1) / tileCells..plan.x1.coerceAtMost(plan.nx-1) / tileCells) tiles += tx to ty
        var hits = 0; var misses = 0
        for ((ordinal, coordinate) in tiles.withIndex()) {
            if (!shouldContinue()) return null
            val (tx, ty) = coordinate
            val cx0 = tx * tileCells; val cy0 = ty * tileCells
            val cx1 = if(cx0+tileCells>=plan.nx) plan.nx else cx0+tileCells-1
            val cy1 = if(cy0+tileCells>=plan.ny) plan.ny else cy0+tileCells-1
            // 256 nodes cover the two closing-radius passes; source edge context is clipped.
            val px0 = maxOf(0, cx0 - HALO); val py0 = maxOf(0, cy0 - HALO)
            val px1 = minOf(plan.nx, cx1 + HALO); val py1 = minOf(plan.ny, cy1 + HALO)
            val bounds = NormalizedRasterBounds(px0.toDouble() / plan.nx, py0.toDouble() / plan.ny,
                px1.toDouble() / plan.nx, py1.toDouble() / plan.ny)
            val tileOptions = safe.copy(focusBounds = bounds, fixedWidth = px1 - px0 + 1,
                fixedHeight = py1 - py0 + 1, rasterResolution = maxOf(px1 - px0 + 1, py1 - py0 + 1))
            val cached = memory.get(file, tileOptions) ?: disk.get(file, tileOptions)?.also { memory.put(file, tileOptions, it) }
            val terrain = if (cached != null) { hits++; cached } else {
                misses++
                progress(ordinal.toFloat() / tiles.size, "Reading terrain tile ${ordinal + 1}/${tiles.size}…")
                val decoded = LazTerrainReader.readPrepared(file, tileOptions, shouldContinue) { n, total ->
                    progress((ordinal + if (total > 0) n.toFloat() / total else 0f) / tiles.size, "Reading terrain tile ${ordinal + 1}/${tiles.size}…")
                }
                if (!shouldContinue()) return null
                val grid = requireNotNull(decoded) { "Source terrain tile could not be decoded" }.grid
                val core = crop(grid, cx0-px0, cy0-py0, cx1-cx0+1, cy1-cy0+1)
                DemGenerator.TerrainLoadResult(core, decoded.note,
                    safe.groundMode != GroundSurfaceMode.SURFACE_MODEL).also {
                    memory.put(file, tileOptions, it)
                    disk.put(file, tileOptions, it)
                }
            }
            val grid = terrain.grid
            check(grid.width == cx1 - cx0 + 1 && grid.height == cy1 - cy0 + 1) { "Tile shape changed" }
            for (y in maxOf(cy0, plan.y0)..minOf(cy1, plan.y1)) for (x in maxOf(cx0, plan.x0)..minOf(cx1, plan.x1)) {
                val src = (y - cy0) * grid.width + x - cx0
                val dst = (y - plan.y0) * plan.width + x - plan.x0
                elevations[dst] = grid.bareEarth[src]; canopy[dst] = grid.canopySpikes[src]
                valid[dst] = grid.validData[src]; interpolated[dst] = grid.interpolatedData[src]
            }
        }
        if (valid.none { it }) return null
        val grid = ElevationGrid(plan.width, plan.height, elevations, canopy, maxOf(cellX, cellY), valid,
            interpolated, cellSizeXMeters = cellX, cellSizeYMeters = cellY)
        val terrain = DemGenerator.TerrainLoadResult(grid,
            "Detailed terrain · $hits tiles reused, $misses source tiles read · measured/interpolated cells retained",
            safe.groundMode != GroundSurfaceMode.SURFACE_MODEL)
        progress(1f, "Terrain tiles ready")
        return Outcome(LasGeoreferencing.attach(terrain, file, safe.copy(focusBounds = plan.bounds)), plan.bounds, hits, misses)
    }
    private fun crop(grid: ElevationGrid, x: Int, y: Int, width: Int, height: Int): ElevationGrid {
        val z=FloatArray(width*height); val canopy=FloatArray(z.size); val valid=BooleanArray(z.size); val filled=BooleanArray(z.size)
        for(row in 0 until height) for(col in 0 until width) {
            val src=(y+row)*grid.width+x+col; val dst=row*width+col
            z[dst]=grid.bareEarth[src]; canopy[dst]=grid.canopySpikes[src]; valid[dst]=grid.validData[src]; filled[dst]=grid.interpolatedData[src]
        }
        return ElevationGrid(width,height,z,canopy,grid.cellSizeMeters,valid,filled,
            cellSizeXMeters=grid.cellSizeXMeters,cellSizeYMeters=grid.cellSizeYMeters)
    }
    companion object {
        const val TILE = 512
        const val HALO = 256
        internal fun plan(rangeX: Double, rangeY: Double, focus: NormalizedRasterBounds, resolution: Int): Plan {
            require(rangeX > 0 && rangeY > 0 && rangeX.isFinite() && rangeY.isFinite())
            val longSide = max(rangeX, rangeY)
            val target = max(rangeX * (focus.right - focus.left), rangeY * (focus.bottom - focus.top)) / (resolution - 1).coerceAtLeast(1)
            var intervals = 1 shl floor(log2(longSide / target)).toInt().coerceIn(6, 24)
            while(true) {
                val nx = (intervals * rangeX / longSide).roundToInt().coerceAtLeast(47)
                val ny = (intervals * rangeY / longSide).roundToInt().coerceAtLeast(47)
                val x0 = floor(focus.left * nx).toInt().coerceIn(0, nx - 1)
                val y0 = floor(focus.top * ny).toInt().coerceIn(0, ny - 1)
                val result=Plan(nx, ny, x0, y0, ceil(focus.right * nx).toInt().coerceIn(x0 + 1, nx),
                    ceil(focus.bottom * ny).toInt().coerceIn(y0 + 1, ny))
                // Outward lattice snapping can add two nodes. Keep the declared raster/texture limit.
                if(maxOf(result.width,result.height)<=resolution || intervals==64) return result
                intervals/=2
            }
        }
    }
}
