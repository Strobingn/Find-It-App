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

        return ElevationGrid(GRID_SIZE, GRID_SIZE, bareEarth, canopySpikes)
    }

    /**
     * Parses custom elevation grid from pasted CSV text data.
     * Robustly handles different sizes and formats, fallback to default.
     */
    fun parseCustomGrid(csvText: String): ElevationGrid? {
        try {
            val lines = csvText.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

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
                    floats[i] = tokens[i].toFloatOrNull() ?: 0f
                }

                if (colCount == -1) {
                    colCount = floats.size
                } else if (floats.size != colCount) {
                    // Non-matching columns, truncate or pad to maintain grid
                    val adjustedFloats = FloatArray(colCount)
                    System.arraycopy(floats, 0, adjustedFloats, 0, Math.min(floats.size, colCount))
                    rowData.add(adjustedFloats)
                    continue
                }
                rowData.add(floats)
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

    fun parseLasStream(inputStream: java.io.InputStream): ElevationGrid? {
        try {
            // Read entire stream into memory - no mark/reset ever needed
            val bytes = inputStream.readBytes()
            if (bytes.size < 227) return null

            // Check signature "LASF"
            if (bytes[0].toChar() != 'L' || bytes[1].toChar() != 'A' || bytes[2].toChar() != 'S' || bytes[3].toChar() != 'F') {
                return null
            }

            val offsetToPoints = readIntLE(bytes, 96)
            val pointFormat = bytes[104].toInt() and 0xFF
            val pointRecordLength = readShortLE(bytes, 105).toInt() and 0xFFFF
            val legacyNumPoints = readIntLE(bytes, 107)

            val scaleX = readDoubleLE(bytes, 131)
            val scaleY = readDoubleLE(bytes, 139)
            val scaleZ = readDoubleLE(bytes, 147)

            val offsetX = readDoubleLE(bytes, 155)
            val offsetY = readDoubleLE(bytes, 163)
            val offsetZ = readDoubleLE(bytes, 171)

            val maxX = readDoubleLE(bytes, 179)
            val minX = readDoubleLE(bytes, 187)
            val maxY = readDoubleLE(bytes, 195)
            val minY = readDoubleLE(bytes, 203)
            val maxZ = readDoubleLE(bytes, 211)
            val minZ = readDoubleLE(bytes, 219)

            if (offsetToPoints < 0 || offsetToPoints >= bytes.size) return null

            // Bins for 100x100 grid
            val gridW = 100
            val gridH = 100
            val minGrid = FloatArray(gridW * gridH) { Float.MAX_VALUE }
            val maxGrid = FloatArray(gridW * gridH) { Float.MIN_VALUE }
            val countGrid = IntArray(gridW * gridH)

            val pointsToProcess = Math.min(if (legacyNumPoints <= 0) 100000 else legacyNumPoints, 250000)

            // Bounding box range
            val rangeX = if (maxX - minX > 0) maxX - minX else 1.0
            val rangeY = if (maxY - minY > 0) maxY - minY else 1.0

            var pos = offsetToPoints
            for (i in 0 until pointsToProcess) {
                if (pos + pointRecordLength > bytes.size) break

                // X, Y, Z are the first three 4-byte integers in the point record
                val rawX = readIntLE(bytes, pos)
                val rawY = readIntLE(bytes, pos + 4)
                val rawZ = readIntLE(bytes, pos + 8)

                val realX = rawX * scaleX + offsetX
                val realY = rawY * scaleY + offsetY
                val realZ = (rawZ * scaleZ + offsetZ).toFloat()

                val gx = (((realX - minX) / rangeX) * (gridW - 1)).toInt().coerceIn(0, gridW - 1)
                // LAS coords have Y pointing North, so we invert Y for standard image row coords
                val gy = ((1.0 - (realY - minY) / rangeY) * (gridH - 1)).toInt().coerceIn(0, gridH - 1)

                val idx = gy * gridW + gx
                if (realZ < minGrid[idx]) minGrid[idx] = realZ
                if (realZ > maxGrid[idx]) maxGrid[idx] = realZ
                countGrid[idx]++

                pos += pointRecordLength
            }

            // Interpolate missing cells to make a continuous terrain
            val bareEarth = FloatArray(gridW * gridH)
            val canopySpikes = FloatArray(gridW * gridH)

            for (y in 0 until gridH) {
                for (x in 0 until gridW) {
                    val idx = y * gridW + x
                    if (countGrid[idx] > 0) {
                        bareEarth[idx] = minGrid[idx]
                        canopySpikes[idx] = (maxGrid[idx] - minGrid[idx]).coerceAtLeast(0f)
                    } else {
                        // Find nearest non-empty cell to interpolate
                        var nearestVal = 0f
                        var nearestCanopy = 0f
                        var found = false
                        // Spiral search or radial search up to 10 cells
                        for (r in 1..10) {
                            for (dy in -r..r) {
                                for (dx in -r..r) {
                                    if (Math.abs(dx) != r && Math.abs(dy) != r) continue
                                    val nx = x + dx
                                    val ny = y + dy
                                    if (nx in 0 until gridW && ny in 0 until gridH) {
                                        val nidx = ny * gridW + nx
                                        if (countGrid[nidx] > 0) {
                                            nearestVal = minGrid[nidx]
                                            nearestCanopy = (maxGrid[nidx] - minGrid[nidx]).coerceAtLeast(0f)
                                            found = true
                                            break
                                        }
                                    }
                                }
                                if (found) break
                            }
                            if (found) break
                        }
                        if (found) {
                            bareEarth[idx] = nearestVal
                            canopySpikes[idx] = nearestCanopy
                        } else {
                            // Fallback
                            bareEarth[idx] = 10f
                            canopySpikes[idx] = 0f
                        }
                    }
                }
            }

            // Apply smooth pass over bareEarth and canopy to reduce binning/sampling noise
            val smoothBare = FloatArray(gridW * gridH)
            val smoothCanopy = FloatArray(gridW * gridH)
            for (y in 0 until gridH) {
                for (x in 0 until gridW) {
                    var sumB = 0f
                    var sumC = 0f
                    var count = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until gridW && ny in 0 until gridH) {
                                val nidx = ny * gridW + nx
                                sumB += bareEarth[nidx]
                                sumC += canopySpikes[nidx]
                                count++
                            }
                        }
                    }
                    smoothBare[y * gridW + x] = sumB / count
                    smoothCanopy[y * gridW + x] = sumC / count
                }
            }

            return ElevationGrid(gridW, gridH, smoothBare, smoothCanopy)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
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
            
            if (ncols <= 0 || nrows <= 0) return null
            
            val tempGrid = FloatArray(ncols * nrows)
            var index = 0
            
            // If we broke on a data line, we need to process it first
            var dataLine = line
            while (dataLine != null) {
                val tokens = dataLine.trim().split(Regex("\\s+"))
                for (token in tokens) {
                    if (token.isNotEmpty() && index < tempGrid.size) {
                        val v = token.toFloatOrNull() ?: 0f
                        tempGrid[index++] = if (v == nodata) 0f else v
                    }
                }
                dataLine = reader.readLine()
            }
            
            val gridW = 100
            val gridH = 100
            val bareEarth = FloatArray(gridW * gridH)
            val canopySpikes = FloatArray(gridW * gridH) // ASC typically doesn't have canopy
            
            for (y in 0 until gridH) {
                for (x in 0 until gridW) {
                    val srcX = (x.toFloat() / (gridW - 1) * (ncols - 1)).toInt().coerceIn(0, ncols - 1)
                    val srcY = (y.toFloat() / (gridH - 1) * (nrows - 1)).toInt().coerceIn(0, nrows - 1)
                    bareEarth[y * gridW + x] = tempGrid[srcY * ncols + srcX]
                }
            }
            
            return ElevationGrid(gridW, gridH, bareEarth, canopySpikes)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun parseXyzStream(reader: java.io.BufferedReader): ElevationGrid? {
        try {
            val points = ArrayList<FloatArray>()
            var minX = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var minY = Float.MAX_VALUE
            var maxY = Float.MIN_VALUE
            
            var lineCount = 0
            reader.forEachLine { line ->
                if (lineCount < 300000) { // Limit to 300k points
                    val tokens = line.trim().split(Regex("[,\\s\\t]+"))
                    if (tokens.size >= 3) {
                        val x = tokens[0].toFloatOrNull()
                        val y = tokens[1].toFloatOrNull()
                        val z = tokens[2].toFloatOrNull()
                        if (x != null && y != null && z != null) {
                            points.add(floatArrayOf(x, y, z))
                            if (x < minX) minX = x
                            if (x > maxX) maxX = x
                            if (y < minY) minY = y
                            if (y > maxY) maxY = y
                            lineCount++
                        }
                    }
                }
            }
            
            if (points.isEmpty()) return null
            
            val gridW = 100
            val gridH = 100
            val minGrid = FloatArray(gridW * gridH) { Float.MAX_VALUE }
            val maxGrid = FloatArray(gridW * gridH) { Float.MIN_VALUE }
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
            
            for (y in 0 until gridH) {
                for (x in 0 until gridW) {
                    val idx = y * gridW + x
                    if (countGrid[idx] > 0) {
                        bareEarth[idx] = minGrid[idx]
                        canopySpikes[idx] = (maxGrid[idx] - minGrid[idx]).coerceAtLeast(0f)
                    } else {
                        var nearestVal = 0f
                        var nearestCanopy = 0f
                        var found = false
                        for (r in 1..10) {
                            for (dy in -r..r) {
                                for (dx in -r..r) {
                                    if (Math.abs(dx) != r && Math.abs(dy) != r) continue
                                    val nx = x + dx
                                    val ny = y + dy
                                    if (nx in 0 until gridW && ny in 0 until gridH) {
                                        val nidx = ny * gridW + nx
                                        if (countGrid[nidx] > 0) {
                                            nearestVal = minGrid[nidx]
                                            nearestCanopy = (maxGrid[nidx] - minGrid[nidx]).coerceAtLeast(0f)
                                            found = true
                                            break
                                        }
                                    }
                                }
                                if (found) break
                            }
                            if (found) break
                        }
                        if (found) {
                            bareEarth[idx] = nearestVal
                            canopySpikes[idx] = nearestCanopy
                        } else {
                            bareEarth[idx] = 10f
                            canopySpikes[idx] = 0f
                        }
                    }
                }
            }
            
            return ElevationGrid(gridW, gridH, bareEarth, canopySpikes)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Master entry point. Reads the ENTIRE stream into memory first.
     * This completely eliminates every possible "Resetting to invalid mark" crash.
     */
    fun parseFromStream(fileName: String, inputStream: java.io.InputStream): ElevationGrid? {
        return try {
            // OPTION A: full in-memory read. Zero mark/reset risk.
            val bytes = inputStream.readBytes()
            if (bytes.isEmpty()) return null

            val lowerName = fileName.lowercase()

            if (lowerName.endsWith(".las")) {
                // Re-use the now-memory-safe LAS parser
                parseLasStream(java.io.ByteArrayInputStream(bytes))
            } else {
                val text = String(bytes, Charsets.UTF_8)
                val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) return null

                val firstLine = lines[0].lowercase()

                if (firstLine.startsWith("ncols ") || firstLine.startsWith("ncols\t")) {
                    // ASC / DEM grid
                    parseAscDem(java.io.BufferedReader(java.io.StringReader(text)))
                } else {
                    // Decide between XYZ point cloud vs pure elevation matrix
                    val tokens1 = lines[0].split(Regex("[,\\s\\t]+")).filter { it.isNotEmpty() }
                    val tokens2 = if (lines.size > 1) lines[1].split(Regex("[,\\s\\t]+")).filter { it.isNotEmpty() } else emptyList()

                    if (tokens1.size == 3 && tokens2.size == 3) {
                        // XYZ point cloud
                        parseXyzStream(java.io.BufferedReader(java.io.StringReader(text)))
                    } else {
                        // Raw elevation matrix (space / comma separated)
                        parseCustomGrid(text)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
