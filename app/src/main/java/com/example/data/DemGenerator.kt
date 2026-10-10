package com.example.data

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

object DemGenerator {
    const val GRID_SIZE = 100

    /**
     * Generates a pre-configured archaeological site.
     * @param siteType 0 = Old Homestead, 1 = Civil War Fort, 2 = Roman Villa
     */
    fun generateSite(siteType: Int): ElevationGrid {
        val bareEarth = FloatArray(GRID_SIZE * GRID_SIZE)
        val canopySpikes = FloatArray(GRID_SIZE * GRID_SIZE)

        for (y in 0 until GRID_SIZE) {
            for (x in 0 until GRID_SIZE) {
                val idx = y * GRID_SIZE + x
                val xf = x.toFloat()
                val yf = y.toFloat()

                // 1. Natural Base Terrain (Hills, ridges, and slopes)
                var baseTerrain = 0f
                when (siteType) {
                    0 -> { // Homestead: Gentle rolling hill slope
                        baseTerrain = 12f + (xf * 0.05f) + (yf * 0.03f) + 3f * sin(xf * 0.08f) * cos(yf * 0.06f)
                    }
                    1 -> { // Fortification: Tactical ridge crest across the center
                        // A ridge running diagonally
                        val distToRidge = (xf + yf - GRID_SIZE) / sqrt(2f)
                        baseTerrain = 15f + 6f * exp(-(distToRidge * distToRidge) / 600f) + (xf * 0.02f)
                    }
                    2 -> { // Roman Villa: Large, flat agricultural plateau
                        baseTerrain = 25f + 1.5f * sin(xf * 0.04f) + 1.5f * cos(yf * 0.04f)
                        // A depressed stream bed/valley on the right
                        if (x > 75) {
                            val streamDist = (xf - 85f)
                            baseTerrain -= (8f - 0.1f * streamDist * streamDist).coerceAtLeast(0f)
                        }
                    }
                }

                // 2. Archaeological Foundations & Earthworks (Subtle details carved into DEM)
                var ruinsDepth = 0f
                when (siteType) {
                    0 -> { // Old Homestead (Cellar hole, Chimney, Well, Path)
                        // Cellar hole at (45, 45), size 14x14
                        if (x in 38..52 && y in 38..52) {
                            ruinsDepth = -3.2f // Cellar excavation
                            // Leave a chimney mound at (45, 39)
                            if (x in 44..46 && y in 38..40) {
                                ruinsDepth = 1.8f
                            }
                        }
                        // Surrounding low fieldstone wall (mound border around cellar hole, 24x24)
                        val dx = Math.abs(xf - 45f)
                        val dy = Math.abs(yf - 45f)
                        if ((dx in 11.5f..13.5f && dy <= 13.5f) || (dy in 11.5f..13.5f && dx <= 13.5f)) {
                            ruinsDepth = 0.8f
                        }
                        // Circular Well at (25, 65)
                        val distToWell = sqrt((xf - 25f) * (xf - 25f) + (yf - 65f) * (yf - 65f))
                        if (distToWell < 3f) {
                            ruinsDepth = -4.5f
                        } else if (distToWell >= 3f && distToWell < 4.5f) {
                            ruinsDepth = 0.6f // Well stone collar ring
                        }

                        // Disturbed old trail: winding depression
                        val trailOffset = 15f * sin(yf * 0.06f) + 20f
                        val distToTrail = Math.abs(xf - trailOffset)
                        if (distToTrail < 2.5f && y > 30) {
                            ruinsDepth = -0.5f // slightly packed/sunken path
                        }
                    }
                    1 -> { // Civil War Fort (Chevron earthworks, defensive ditch, gun pit)
                        // Chevron shaped breastwork (embankment) pointed toward top-right (80, 20)
                        // Formula: y = -x + 100 is the diagonal. Fort centered around (50, 50).
                        val d1 = yf - (100f - xf) // Line 1
                        val d2 = yf - xf // Line 2
                        
                        // Let's create an arrowhead fort pointing North (50, 30)
                        // Chevron walls
                        val armLeft = Math.abs((yf - 30f) - 0.8f * (xf - 50f))
                        val armRight = Math.abs((yf - 30f) + 0.8f * (xf - 50f))
                        
                        val isInsideArms = yf > 30f + 0.8f * Math.abs(xf - 50f) && yf < 70f
                        val isNearLeftWall = armLeft < 3.5f && xf <= 50f && yf in 30f..65f
                        val isNearRightWall = armRight < 3.5f && xf >= 50f && yf in 30f..65f
                        
                        if (isNearLeftWall || isNearRightWall) {
                            ruinsDepth = 2.8f // Tall earthen ramparts
                        }
                        
                        // Defensive trench/moat running parallel, just outside the chevron
                        val isNearLeftTrench = armLeft >= 4f && armLeft < 8f && xf <= 51f && yf in 27f..66f
                        val isNearRightTrench = armRight >= 4f && armRight < 8f && xf >= 49f && yf in 27f..66f
                        if (isNearLeftTrench || isNearRightTrench) {
                            ruinsDepth = -1.8f // Excavated ditch
                        }

                        // Gun emplacement / Artillery mound at (50, 52)
                        val distToGun = sqrt((xf - 50f) * (xf - 50f) + (yf - 52f) * (yf - 52f))
                        if (distToGun < 4f) {
                            ruinsDepth = -0.8f // Pit inside gun platform
                        } else if (distToGun >= 4f && distToGun < 7f) {
                            ruinsDepth = 1.6f // Circular earthen parapet protecting the gun
                        }
                    }
                    2 -> { // Roman Villa (Rectangular foundation walls, shrine, straight Roman road)
                        // Villa rectangular walls at (40, 45) sized 28x18
                        val dx = Math.abs(xf - 40f)
                        val dy = Math.abs(yf - 45f)
                        
                        var isWall = false
                        // Main outer walls
                        if ((dx in 13.5f..14.5f && dy <= 9.5f) || (dy in 8.5f..9.5f && dx <= 14.5f)) {
                            isWall = true
                        }
                        // Inner room dividers
                        if (dx in 0f..1f && dy <= 9f) { // central divider
                            isWall = true
                        }
                        if (dy in 0f..1f && dx > 0f && dx < 14f) { // room 1/2 divider
                            isWall = true
                        }
                        
                        if (isWall) {
                            ruinsDepth = 1.1f // Uncovered stone foundation wall
                        } else if (dx < 13.5f && dy < 8.5f) {
                            ruinsDepth = -0.3f // Intentionally flat courtyard floor
                        }

                        // Circular Temple shrine outline at (68, 30)
                        val distToShrine = sqrt((xf - 68f) * (xf - 68f) + (yf - 30f) * (yf - 30f))
                        if (distToShrine >= 5f && distToShrine <= 6.5f) {
                            ruinsDepth = 0.9f // circular stone wall
                        } else if (distToShrine < 5f) {
                            ruinsDepth = 0.2f // raised marble floor pedestal
                        }

                        // Straight ancient Roman paved road diagonal from (0, 90) to (50, 100) or let's say straight up the west side
                        // A paved raised road running from bottom-left to top-middle-left
                        val roadLine = Math.abs(xf - (yf * 0.4f + 10f))
                        if (roadLine < 3f) {
                            ruinsDepth = 0.6f // raised agger (road mound)
                        }
                    }
                }

                bareEarth[idx] = baseTerrain + ruinsDepth

                // 3. Canopy Spikes (Vegetation trees/shrub height to overlay DSM)
                // We'll use high frequency sinusoids combined with noise to simulate dense trees
                var canopyVal = 0f
                val seed = (x * 73 + y * 31) % 100
                if (seed > 65) { // 35% density of trees/brush
                    // Generate height of trees (from 4 meters to 12 meters)
                    canopyVal = 5f + 7f * sin(xf * 0.5f) * cos(yf * 0.5f) + (seed % 10).toFloat() * 0.4f
                    
                    // Do not put trees inside the cellar holes or on top of well rings for historical accuracy,
                    // but in fort and villa they can grow on top of abandoned mounds!
                    if (siteType == 0) {
                        val inCellar = x in 38..52 && y in 38..52
                        val nearWell = sqrt((xf - 25f) * (xf - 25f) + (yf - 65f) * (yf - 65f)) < 4f
                        if (inCellar || nearWell) {
                            canopyVal = 0.5f // only minor weeds
                        }
                    }
                } else if (seed > 40) { // Shorter scrub/weeds (0.5m to 2m)
                    canopyVal = 0.5f + (seed % 5).toFloat() * 0.3f
                }
                
                canopySpikes[idx] = canopyVal.coerceAtLeast(0f)
            }
        }

        return ElevationGrid(GRID_SIZE, GRID_SIZE, bareEarth, canopySpikes, cellSizeMeters = 0.5f)
    }

    /**
     * Parses custom elevation grid from pasted CSV text data.
     * Robustly handles different sizes and formats, fallback to default.
     */
    fun parseCustomGrid(csvText: String): ElevationGrid? =
        parseCustomGrid(java.io.BufferedReader(java.io.StringReader(csvText)))

    fun parseCustomGrid(reader: java.io.BufferedReader): ElevationGrid? {
        try {
            val lines = reader.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()

            if (lines.isEmpty()) return null

            val rowData = ArrayList<FloatArray>()
            var colCount = -1

            for (line in lines) {
                // Split by commas, tabs, or spaces
                val tokens = line.split(Regex("[,\\s\\t]+"))
                    .filter { it.isNotEmpty() }
                
                if (tokens.isEmpty()) continue

                val floats = FloatArray(tokens.size)
                for (i in tokens.indices) {
                    val value = tokens[i].toFloatOrNull() ?: return null
                    if (!value.isFinite()) return null
                    floats[i] = value
                }

                if (colCount == -1) {
                    colCount = floats.size
                } else if (floats.size != colCount) {
                    return null
                }
                rowData.add(floats)
                if (rowData.size > 2_048 || colCount > 2_048 || rowData.size.toLong() * colCount > 2_000_000) {
                    return null
                }
            }

            if (rowData.isEmpty() || colCount <= 0) return null

            val width = colCount
            val height = rowData.size

            val bareEarth = FloatArray(width * height)
            val canopySpikes = FloatArray(width * height) // Custom files don't have separate canopy, so 0

            for (r in 0 until height) {
                val row = rowData[r]
                System.arraycopy(row, 0, bareEarth, r * width, width)
            }

            return ElevationGrid(width, height, bareEarth, canopySpikes)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
               ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
               ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
               ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(bytes: ByteArray, offset: Int): Short {
        return ((bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    private fun readDoubleLE(bytes: ByteArray, offset: Int): Double {
        val bits = (bytes[offset].toLong() and 0xFF) or
                   ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                   ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                   ((bytes[offset + 3].toLong() and 0xFF) shl 24) or
                   ((bytes[offset + 4].toLong() and 0xFF) shl 32) or
                   ((bytes[offset + 5].toLong() and 0xFF) shl 40) or
                   ((bytes[offset + 6].toLong() and 0xFF) shl 48) or
                   ((bytes[offset + 7].toLong() and 0xFF) shl 56)
        return Double.fromBits(bits)
    }

    /**
     * Parse LAS with ASPRS ground classification.
     *
     * @param groundOnly keep class 2 (Ground) and 8 (Model Key-Point) only.
     *        Falls back to min-Z binning if the file has almost no ground classes
     *        (unclassified vendor clouds).
     */
    fun parseLasStream(
        inputStream: java.io.InputStream,
        groundOnly: Boolean = true,
    ): ElevationGrid? = parseLasStreamDetailed(inputStream, groundOnly)?.grid

    /**
     * Full LAS load result with stats for the UI (ground counts, method used).
     */
    data class LasLoadResult(
        val grid: ElevationGrid,
        val totalPointsRead: Int,
        val groundPointsUsed: Int,
        val usedClassificationFilter: Boolean,
        val pointFormat: Int,
        val note: String,
        val requestedGroundMode: GroundSurfaceMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
        val appliedGroundMode: GroundSurfaceMode = if (usedClassificationFilter) {
            GroundSurfaceMode.SOURCE_CLASSIFIED
        } else {
            GroundSurfaceMode.AUTO_LOWEST
        },
        val sampledPoints: Int = totalPointsRead,
        val wasTruncated: Boolean = false,
        /** Structured ground-filter outcome; null for legacy non-rasterizer loads. */
        val groundReport: GroundSurfaceReport? = null,
    )

    /**
     * Configurable LAS reader used by the import workspace. It preserves the file footprint aspect
     * ratio and can build classified ground, automatic ground, or highest-return surface rasters.
     */
    fun parseLasStreamDetailed(
        inputStream: java.io.InputStream,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
    ): LasLoadResult? {
        return try {
            val buffered = if (inputStream is java.io.BufferedInputStream) {
                inputStream
            } else {
                java.io.BufferedInputStream(inputStream, 256 * 1024)
            }
            buffered.mark(4_096)
            val header = ByteArray(375)
            val headerBytes = readUpTo(buffered, header)
            buffered.reset()
            if (headerBytes < 227 || !header.copyOfRange(0, 4).contentEquals("LASF".toByteArray())) {
                return null
            }

            val versionMajor = header[24].toInt() and 0xFF
            val versionMinor = header[25].toInt() and 0xFF
            val offsetToPoints = readIntLE(header, 96)
            val rawPointFormat = header[104].toInt() and 0xFF
            val pointFormat = rawPointFormat and 0x3F
            val pointRecordLength = readShortLE(header, 105).toInt() and 0xFFFF
            if (rawPointFormat and 0xC0 != 0) {
                return LazTerrainReader.read(buffered, options, shouldContinue = shouldContinue)
            }
            if (offsetToPoints < 227 || pointRecordLength < 20 || pointRecordLength > 512) return null

            var pointCount = readIntLE(header, 107).toLong() and 0xFFFFFFFFL
            if (versionMajor == 1 && versionMinor >= 4 && headerBytes >= 255) {
                readLongLE(header, 247).takeIf { it > 0 }?.let { pointCount = it }
            }
            val scaleX = readDoubleLE(header, 131)
            val scaleY = readDoubleLE(header, 139)
            val scaleZ = readDoubleLE(header, 147)
            val offsetX = readDoubleLE(header, 155)
            val offsetY = readDoubleLE(header, 163)
            val offsetZ = readDoubleLE(header, 171)
            val maxX = readDoubleLE(header, 179)
            val minX = readDoubleLE(header, 187)
            val maxY = readDoubleLE(header, 195)
            val minY = readDoubleLE(header, 203)
            if (!listOf(scaleX, scaleY, scaleZ, offsetX, offsetY, offsetZ, maxX, minX, maxY, minY)
                    .all { it.isFinite() } || maxX <= minX || maxY <= minY
            ) {
                return null
            }

            if (!skipFully(buffered, offsetToPoints)) return null
            val rasterizer = LidarRasterizer(
                minX = minX,
                maxX = maxX,
                minY = minY,
                maxY = maxY,
                options = options,
                declaredPointCount = pointCount,
                lasVersionMajor = versionMajor,
                lasVersionMinor = versionMinor,
                pointFormat = pointFormat,
            )
            val classOffset = if (pointFormat >= 6) 16 else 15
            val classMask = if (pointFormat >= 6) 0xFF else 0x1F
            val keyPointMask = if (pointFormat >= 6) 0x02 else 0x40
            val keyPointOffset = if (pointFormat >= 6) 15 else 15
            val batchRecords = 4_096
            val batch = ByteArray(pointRecordLength * batchRecords)
            var decoded = 0L
            while (true) {
                val bytesRead = readBulkRecords(buffered, batch, pointRecordLength)
                if (bytesRead < pointRecordLength) break
                val records = bytesRead / pointRecordLength
                var offset = 0
                repeat(records) {
                    val rawX = readIntLE(batch, offset)
                    val rawY = readIntLE(batch, offset + 4)
                    val rawZ = readIntLE(batch, offset + 8)
                    val classification = batch[offset + classOffset].toInt() and classMask
                    val isKeyPoint = batch[offset + keyPointOffset].toInt() and keyPointMask != 0
                    rasterizer.addPoint(
                        x = rawX * scaleX + offsetX,
                        y = rawY * scaleY + offsetY,
                        z = (rawZ * scaleZ + offsetZ).toFloat(),
                        classification = classification,
                        isKeyPoint = isKeyPoint,
                    )
                    offset += pointRecordLength
                    decoded++
                }
                if (!shouldContinue()) {
                    rasterizer.markTruncated()
                    break
                }
            }
            rasterizer.finish(
                pointFormat = pointFormat,
                sourceLabel = "LAS $versionMajor.$versionMinor format $pointFormat",
            )
        } catch (exception: Exception) {
            exception.printStackTrace()
            null
        }
    }

    /**
     * Legacy entry that used a 128² raster and a 2M-point cap. It now delegates to the
     * full-detail rasterizer so every return is binned and unmeasured cells stay invalid.
     */
    fun parseLasStreamDetailed(
        inputStream: java.io.InputStream,
        groundOnly: Boolean = true,
    ): LasLoadResult? = parseLasStreamDetailed(
        inputStream = inputStream,
        options = LidarImportOptions(
            groundMode = if (groundOnly) {
                GroundSurfaceMode.SOURCE_CLASSIFIED
            } else {
                GroundSurfaceMode.AUTO_LOWEST
            },
        ),
    )

    private fun readUpTo(input: java.io.InputStream, target: ByteArray): Int {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) break
            if (count == 0) continue
            offset += count
        }
        return offset
    }

    private fun readFullyRecord(input: java.io.InputStream, record: ByteArray): Boolean {
        var offset = 0
        while (offset < record.size) {
            val count = input.read(record, offset, record.size - offset)
            if (count < 0) return false
            // A zero-length read without EOF used to spin forever on some streams.
            if (count == 0) return false
            offset += count
        }
        return true
    }

    /** Reads as many whole point records as will fit in [target]. Returns bytes actually read. */
    private fun readBulkRecords(input: java.io.InputStream, target: ByteArray, recordLength: Int): Int {
        if (recordLength <= 0) return 0
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) break
            if (count == 0) break
            offset += count
        }
        return offset - (offset % recordLength)
    }

    private fun skipFully(input: java.io.InputStream, byteCount: Int): Boolean {
        var remaining = byteCount.toLong()
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (input.read() < 0) {
                return false
            } else {
                remaining--
            }
        }
        return true
    }

    private fun readLongLE(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0..7) {
            v = v or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return v
    }

    private fun fillNaNNearest(grid: FloatArray, w: Int, h: Int) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!grid[idx].isNaN()) continue
                var found = false
                var nearest = 0f
                for (r in 1..16) {
                    for (dy in -r..r) {
                        for (dx in -r..r) {
                            if (kotlin.math.abs(dx) != r && kotlin.math.abs(dy) != r) continue
                            val nx = x + dx
                            val ny = y + dy
                            if (nx !in 0 until w || ny !in 0 until h) continue
                            val v = grid[ny * w + nx]
                            if (!v.isNaN()) {
                                nearest = v
                                found = true
                                break
                            }
                        }
                        if (found) break
                    }
                    if (found) break
                }
                grid[idx] = if (found) nearest else 0f
            }
        }
    }

    private fun boxSmooth(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var n = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            val v = src[ny * w + nx]
                            if (!v.isNaN()) {
                                sum += v
                                n++
                            }
                        }
                    }
                }
                out[y * w + x] = if (n > 0) sum / n else 0f
            }
        }
        return out
    }

    fun parseAscDem(reader: java.io.BufferedReader): ElevationGrid? {
        try {
            var ncols = 0
            var nrows = 0
            var nodata = -9999f
            var headerLines = 0
            
            val headerMap = HashMap<String, String>()
            
            // Read header (usually 6 lines, sometimes 5 or more)
            var line: String? = null
            while (headerLines < 10) {
                line = reader.readLine() ?: break
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val key = parts[0].lowercase()
                    val value = parts[1]
                    if (key == "ncols" || key == "nrows" || key == "xllcorner" || key == "yllcorner" || key == "cellsize" || key == "nodata_value") {
                        headerMap[key] = value
                        headerLines++
                    } else {
                        // Start of data! Break out of header parsing.
                        break
                    }
                } else {
                    break
                }
            }
            
            ncols = headerMap["ncols"]?.toInt() ?: 0
            nrows = headerMap["nrows"]?.toInt() ?: 0
            nodata = headerMap["nodata_value"]?.toFloat() ?: -9999f
            val sourceCellSize = headerMap["cellsize"]?.toFloatOrNull() ?: 1f

            if (ncols <= 0 || nrows <= 0 || ncols.toLong() * nrows > 8_000_000L) return null

            val tempGrid = FloatArray(ncols * nrows) { Float.NaN }
            var index = 0
            
            // If we broke on a data line, we need to process it first
            var dataLine = line
            while (dataLine != null) {
                val tokens = dataLine.trim().split(Regex("\\s+"))
                for (token in tokens) {
                    if (token.isNotEmpty() && index < tempGrid.size) {
                        val v = token.toFloatOrNull()
                        tempGrid[index++] = if (v == null || !v.isFinite() || v == nodata) Float.NaN else v
                    }
                }
                dataLine = reader.readLine()
            }
            
            if (index == 0 || tempGrid.none { it.isFinite() }) return null
            fillNaNNearest(tempGrid, ncols, nrows)

            val scale = minOf(160.0 / ncols, 160.0 / nrows, 1.0)
            val gridW = (ncols * scale).toInt().coerceAtLeast(2)
            val gridH = (nrows * scale).toInt().coerceAtLeast(2)
            val bareEarth = FloatArray(gridW * gridH)
            val canopySpikes = FloatArray(gridW * gridH) // ASC typically doesn't have canopy
            
            for (y in 0 until gridH) {
                for (x in 0 until gridW) {
                    val srcX = (x.toFloat() / (gridW - 1) * (ncols - 1)).toInt().coerceIn(0, ncols - 1)
                    val srcY = (y.toFloat() / (gridH - 1) * (nrows - 1)).toInt().coerceIn(0, nrows - 1)
                    bareEarth[y * gridW + x] = tempGrid[srcY * ncols + srcX]
                }
            }
            
            val outputCellSize =
                if (sourceCellSize.isFinite() && sourceCellSize in 0.01f..10_000f) {
                    (sourceCellSize / scale).toFloat()
                } else {
                    1f
                }
            return ElevationGrid(gridW, gridH, bareEarth, canopySpikes, outputCellSize)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun parseXyzStream(reader: java.io.BufferedReader): ElevationGrid? {
        try {
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            val zs = ArrayList<Float>()
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            reader.forEachLine { line ->
                val tokens = line.trim().split(Regex("[,\\s\\t]+"))
                if (tokens.size >= 3) {
                    val x = tokens[0].toFloatOrNull()
                    val y = tokens[1].toFloatOrNull()
                    val z = tokens[2].toFloatOrNull()
                    if (x != null && y != null && z != null && x.isFinite() && y.isFinite() && z.isFinite()) {
                        xs.add(x)
                        ys.add(y)
                        zs.add(z)
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            val points = ArrayList<FloatArray>(xs.size)
            for (i in xs.indices) points.add(floatArrayOf(xs[i], ys[i], zs[i]))
            
            if (points.isEmpty()) return null
            
            val gridW = 100
            val gridH = 100
            val minGrid = FloatArray(gridW * gridH) { Float.MAX_VALUE }
            val maxGrid = FloatArray(gridW * gridH) { -Float.MAX_VALUE }
            val countGrid = IntArray(gridW * gridH)
            
            val rangeX = if (maxX - minX > 0) (maxX - minX) else 1f
            val rangeY = if (maxY - minY > 0) (maxY - minY) else 1f
            
            for (pt in points) {
                val x = pt[0]
                val y = pt[1]
                val z = pt[2]
                
                val gx = (((x - minX) / rangeX) * (gridW - 1)).toInt().coerceIn(0, gridW - 1)
                val gy = ((1f - (y - minY) / rangeY) * (gridH - 1)).toInt().coerceIn(0, gridH - 1)
                
                val idx = gy * gridW + gx
                if (z < minGrid[idx]) minGrid[idx] = z
                if (z > maxGrid[idx]) maxGrid[idx] = z
                countGrid[idx]++
            }
            
            val bareEarth = FloatArray(gridW * gridH)
            val canopySpikes = FloatArray(gridW * gridH)
            val valid = BooleanArray(gridW * gridH)
            for (y in 0 until gridH) {
                for (x in 0 until gridW) {
                    val idx = y * gridW + x
                    if (countGrid[idx] > 0) {
                        bareEarth[idx] = minGrid[idx]
                        canopySpikes[idx] = (maxGrid[idx] - minGrid[idx]).coerceAtLeast(0f)
                        valid[idx] = true
                    } else {
                        bareEarth[idx] = Float.NaN
                    }
                }
            }
            return ElevationGrid(gridW, gridH, bareEarth, canopySpikes, 1f, valid)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Load result for any supported terrain source (LAS/ASC/XYZ/CSV).
     */
    data class TerrainLoadResult(
        val grid: ElevationGrid,
        val summary: String,
        val isBareEarth: Boolean,
        val geoMetadata: com.example.geospatial.GeoSpatialLibrary.GeoSpatialMetadata? = null,
    )

    /** Master entry point. LAS/LAZ/XYZ/ASC are streamed; custom text grids are read line-by-line. */
    fun parseFromStream(
        fileName: String,
        inputStream: java.io.InputStream,
        groundOnly: Boolean = true,
    ): ElevationGrid? = parseFromStreamDetailed(fileName, inputStream, groundOnly)?.grid

    fun parseFromStreamDetailed(
        fileName: String,
        inputStream: java.io.InputStream,
        groundOnly: Boolean = true,
    ): TerrainLoadResult? = parseFromStreamDetailed(
        fileName = fileName,
        inputStream = inputStream,
        options = LidarImportOptions(
            groundMode = if (groundOnly) {
                GroundSurfaceMode.SOURCE_CLASSIFIED
            } else {
                GroundSurfaceMode.AUTO_LOWEST
            },
        ),
    )

    fun parseFromStreamDetailed(
        fileName: String,
        inputStream: java.io.InputStream,
        options: LidarImportOptions,
        onLazProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)? = null,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
    ): TerrainLoadResult? {
        return try {
            val lowerName = fileName.lowercase()
            val buffered =
                if (inputStream is java.io.BufferedInputStream) inputStream
                else java.io.BufferedInputStream(inputStream, 64 * 1024)

            buffered.mark(16)
            val fileHeader = ByteArray(4)
            val headerBytes = readUpTo(buffered, fileHeader)
            buffered.reset()
            val isTiff = headerBytes >= 4 && (
                fileHeader.contentEquals(byteArrayOf(0x49, 0x49, 0x2A, 0x00)) ||
                    fileHeader.contentEquals(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A))
                )
            if (lowerName.endsWith(".tif") || lowerName.endsWith(".tiff") || isTiff) {
                val geotiff = GeoTiffTerrainReader.read(
                    fileName = fileName,
                    input = buffered,
                    maxDimension = options.rasterResolution,
                )
                return TerrainLoadResult(
                    grid = geotiff.grid,
                    summary = geotiff.summary,
                    isBareEarth = true,
                    geoMetadata = geotiff.metadata,
                )
            }

            if (lowerName.endsWith(".laz")) {
                val laz = LazTerrainReader.read(
                    buffered,
                    options,
                    shouldContinue = shouldContinue,
                    onProgress = onLazProgress,
                ) ?: return null
                return TerrainLoadResult(
                    grid = laz.grid,
                    summary = laz.note,
                    isBareEarth = laz.appliedGroundMode != GroundSurfaceMode.SURFACE_MODEL,
                )
            }

            if (lowerName.endsWith(".las")) {
                val las = parseLasStreamDetailed(buffered, options, shouldContinue) ?: return null
                return TerrainLoadResult(
                    grid = las.grid,
                    summary = las.note,
                    isBareEarth = las.appliedGroundMode != GroundSurfaceMode.SURFACE_MODEL,
                )
            }

            // Detect LAS or LAZ by the common LASF signature and point-format compression bits.
            buffered.mark(512)
            val signature = ByteArray(105)
            val signatureBytes = readUpTo(buffered, signature)
            buffered.reset()
            if (signatureBytes >= 105 && signature.copyOfRange(0, 4).contentEquals("LASF".toByteArray())) {
                val isCompressed = signature[104].toInt() and 0xC0 != 0
                val las = if (isCompressed) {
                    LazTerrainReader.read(
                        buffered,
                        options,
                        shouldContinue = shouldContinue,
                        onProgress = onLazProgress,
                    )
                } else {
                    parseLasStreamDetailed(buffered, options, shouldContinue)
                } ?: return null
                return TerrainLoadResult(
                    grid = las.grid,
                    summary = las.note,
                    isBareEarth = las.appliedGroundMode != GroundSurfaceMode.SURFACE_MODEL,
                )
            }

            val reader = buffered.bufferedReader(Charsets.UTF_8)
            val firstLine = generateSequence { reader.readLine() }
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: return null
            val secondLine = generateSequence { reader.readLine() }
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            val prepended = buildString {
                appendLine(firstLine)
                if (secondLine.isNotEmpty()) appendLine(secondLine)
            }
            val sequenced = java.io.SequenceInputStream(
                java.io.ByteArrayInputStream(prepended.toByteArray(Charsets.UTF_8)),
                object : java.io.InputStream() {
                    override fun read(): Int = reader.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = reader.read(b, off, len)
                },
            )
            val streamingReader = java.io.BufferedReader(java.io.InputStreamReader(sequenced, Charsets.UTF_8))
            val firstLower = firstLine.lowercase()
            val grid =
                if (firstLower.startsWith("ncols ") || firstLower.startsWith("ncols\t")) {
                    parseAscDem(streamingReader)
                } else {
                    val tokens1 = firstLine.split(Regex("[,\\s\\t]+")).filter { it.isNotEmpty() }
                    val tokens2 = secondLine.split(Regex("[,\\s\\t]+")).filter { it.isNotEmpty() }
                    if (tokens1.size == 3 && tokens2.size == 3) {
                        parseXyzStream(streamingReader)
                    } else {
                        parseCustomGrid(streamingReader)
                    }
                } ?: return null

            TerrainLoadResult(
                grid = grid,
                summary = "Loaded $fileName → ${grid.width}×${grid.height} DEM (already bare-earth / grid)",
                isBareEarth = true,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

}
