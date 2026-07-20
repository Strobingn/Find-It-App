package com.example.data

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Represents a 2D grid of elevation points.
 * Supports ground point classification filtering, hillshading computations,
 * and high-performance Bitmap rendering.
 */
class ElevationGrid(
    val width: Int,
    val height: Int,
    val bareEarth: FloatArray, // Bare earth elevation values (DEM)
    val canopySpikes: FloatArray // Height of trees/vegetation on top of bare earth (DSM)
) {
    init {
        require(bareEarth.size == width * height) { "bareEarth size must be width * height" }
        require(canopySpikes.size == width * height) { "canopySpikes size must be width * height" }
    }

    /**
     * Gets the elevation at a specific coordinate (col, row),
     * applying the vegetation filter (0.0 = show full trees, 1.0 = fully classified bare ground).
     */
    fun getElevationAt(col: Int, row: Int, vegetationFilter: Float): Float {
        val c = col.coerceIn(0, width - 1)
        val r = row.coerceIn(0, height - 1)
        val idx = r * width + c
        // As vegetationFilter goes to 1.0, the vegetation spikes are filtered out
        return bareEarth[idx] + canopySpikes[idx] * (1.0f - vegetationFilter)
    }

    /**
     * Generates a hillshaded visual representation of the grid as a Bitmap.
     * Uses Horn's formula for slope and aspect calculation.
     *
     * @param sunAzimuth Angle of the sun in degrees (0-360, 0 is North, 90 is East)
     * @param sunAltitude Angle of the sun above the horizon in degrees (0-90)
     * @param vegetationFilter Strength of vegetation filtering (0.0 to 1.0)
     * @param palette Color theme for the elevation tinting (0 = Slate-Grey Clay, 1 = Lidar Copper, 2 = Terra Earth)
     * @param contrast Contrast multiplier for hillshade shadow depth (1.0 to 2.5)
     */
    fun renderHillshade(
        sunAzimuth: Float,
        sunAltitude: Float,
        vegetationFilter: Float,
        palette: Int,
        contrast: Float = 1.5f,
        visualizationMode: Int = 0, // 0 = Standard, 1 = Multi-Directional Relief, 2 = Slope Map
        overlayType: Int = 0, // 0 = None, 1 = 1880s Homestead Plat, 2 = 1940s Contour Lines
        overlayOpacity: Float = 0.5f
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        // Convert sun angles to radians
        val sunAzRad1 = Math.toRadians((360.0 - sunAzimuth + 90.0) % 360.0).toFloat()
        // Opposite sun angle for secondary direction in Multi-Directional Relief
        val sunAzRad2 = Math.toRadians((360.0 - ((sunAzimuth + 180f) % 360f) + 90.0) % 360.0).toFloat()
        val sunAltRad = Math.toRadians(sunAltitude.toDouble()).toFloat()

        val cosSunAlt = cos(sunAltRad)
        val sinSunAlt = sin(sunAltRad)

        // Constants for Horn's method gradient cell size
        val cellDistance = 1.0f

        // Precompute elevations with vegetation filter applied to speed up loops
        val filteredElevations = FloatArray(width * height)
        for (i in 0 until width * height) {
            filteredElevations[i] = bareEarth[i] + canopySpikes[i] * (1.0f - vegetationFilter)
        }

        // Find min and max elevations for hypsometric tinting
        var minElev = Float.MAX_VALUE
        var maxElev = Float.MIN_VALUE
        for (e in filteredElevations) {
            if (e < minElev) minElev = e
            if (e > maxElev) maxElev = e
        }
        val elevRange = if (maxElev - minElev > 0f) maxElev - minElev else 1f

        for (y in 0 until height) {
            for (x in 0 until width) {
                // Get 3x3 neighbors for Horn's slope algorithm
                val z00 = filteredElevations[coerceIndex(x - 1, y - 1)]
                val z01 = filteredElevations[coerceIndex(x, y - 1)]
                val z02 = filteredElevations[coerceIndex(x + 1, y - 1)]
                val z10 = filteredElevations[coerceIndex(x - 1, y)]
                val z12 = filteredElevations[coerceIndex(x + 1, y)]
                val z20 = filteredElevations[coerceIndex(x - 1, y + 1)]
                val z21 = filteredElevations[coerceIndex(x, y + 1)]
                val z22 = filteredElevations[coerceIndex(x + 1, y + 1)]

                // Horn's method for rate of change
                val dz_dx = ((z02 + 2f * z12 + z22) - (z00 + 2f * z10 + z20)) / (8f * cellDistance)
                val dz_dy = ((z20 + 2f * z21 + z22) - (z00 + 2f * z01 + z02)) / (8f * cellDistance)

                // Slope and Aspect
                val slope = atan(sqrt(dz_dx * dz_dx + dz_dy * dz_dy))
                val aspect = if (dz_dx != 0f) {
                    atan2(dz_dy, -dz_dx)
                } else {
                    if (dz_dy > 0) Math.PI.toFloat() / 2f else -Math.PI.toFloat() / 2f
                }

                // Elevation percentage for color tinting
                val currentElev = filteredElevations[y * width + x]
                val elevPct = ((currentElev - minElev) / elevRange).coerceIn(0f, 1f)

                // Determine base color based on palette
                val baseColor = getPaletteColor(palette, elevPct)

                var finalColor = baseColor

                if (visualizationMode == 2) {
                    // STYLE 2: Slope Map (Neon Orange/Red highlighting steep gradients, otherwise dark steel clay)
                    val slopePct = (slope / 0.4f).coerceIn(0f, 1f)
                    
                    // High-contrast slope coloring: blend the base color into red-orange based on slope
                    val rSlope = (255 * slopePct + Color.red(baseColor) * (1f - slopePct)).toInt().coerceIn(0, 255)
                    val gSlope = (70 * slopePct + Color.green(baseColor) * (1f - slopePct)).toInt().coerceIn(0, 255)
                    val bSlope = (0 * slopePct + Color.blue(baseColor) * (1f - slopePct)).toInt().coerceIn(0, 255)
                    finalColor = Color.rgb(rSlope, gSlope, bSlope)
                } else {
                    // STYLE 0 & 1: Hillshading calculations
                    var hillshade = if (visualizationMode == 1) {
                        // Multi-directional relief: combine primary (NW) and secondary (SE) shadow shading
                        val h1 = cosSunAlt * cos(slope) + sinSunAlt * sin(slope) * cos(sunAzRad1 - aspect)
                        val h2 = cosSunAlt * cos(slope) + sinSunAlt * sin(slope) * cos(sunAzRad2 - aspect)
                        ((h1.coerceIn(0f, 1f) + h2.coerceIn(0f, 1f)) / 2f)
                    } else {
                        // Standard Hillshade
                        val h = cosSunAlt * cos(slope) + sinSunAlt * sin(slope) * cos(sunAzRad1 - aspect)
                        h.coerceIn(0f, 1f)
                    }

                    // Apply contrast/intensity to shadow relief
                    if (contrast != 1.0f) {
                        hillshade = ((hillshade - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
                    }

                    // Blend base color with hillshade
                    val r = (Color.red(baseColor) * hillshade).toInt().coerceIn(0, 255)
                    val g = (Color.green(baseColor) * hillshade).toInt().coerceIn(0, 255)
                    val b = (Color.blue(baseColor) * hillshade).toInt().coerceIn(0, 255)
                    finalColor = Color.rgb(r, g, b)
                }

                // Blend Historical Overlay if requested
                if (overlayType > 0 && overlayOpacity > 0f) {
                    var isOverlayPixel = false
                    var overlayColor = Color.BLACK

                    if (overlayType == 1) {
                        // OVERLAY 1: 1880s Homestead Plat Overlay (Vintage Sepia boundaries, structures, and roads)
                        // A. Plat grid line divisions (e.g. sections every 50 grid lines)
                        val isSectionLine = (x % 50 == 0) || (y % 50 == 0)
                        
                        // B. Old Homestead Carriage Road (Wavy path crossing screen)
                        val roadY = height * 0.65f + sin(x * 0.08f) * 12f
                        val isRoad = Math.abs(y - roadY) < 1.5f
                        
                        // C. Property boundary fences (rectangular layout at center)
                        val isFence = (x in 20..80 && (y == 25 || y == 75)) || (y in 25..75 && (x == 20 || x == 80))

                        // D. Old ruined Homestead structure plots (small squares representing cellar holes)
                        val isHomesteadPlot = (x in 40..45 && y in 41..44) || (x in 24..27 && y in 63..66)

                        if (isSectionLine) {
                            isOverlayPixel = true
                            overlayColor = Color.rgb(180, 150, 110) // Light vintage sepia
                        } else if (isFence) {
                            isOverlayPixel = true
                            overlayColor = Color.rgb(139, 90, 43) // Dark vintage brown
                        } else if (isRoad) {
                            isOverlayPixel = true
                            overlayColor = Color.rgb(160, 130, 90) // Medium sepia dashed carriage road
                        } else if (isHomesteadPlot) {
                            isOverlayPixel = true
                            overlayColor = Color.rgb(200, 50, 50) // Red warning foundation overlay
                        }
                    } else if (overlayType == 2) {
                        // OVERLAY 2: 1940s Vintage Topographic Contour Lines (Brown hand-styled contours)
                        val elevInt = currentElev.toInt()
                        val isContour = (elevInt % 12 == 0) && (filteredElevations[coerceIndex(x - 1, y)].toInt() % 12 != 0 || filteredElevations[coerceIndex(x, y - 1)].toInt() % 12 != 0)
                        
                        // Old coordinate meridian line
                        val isGridMeridian = (x == width / 2) || (y == height / 2)

                        if (isContour) {
                            isOverlayPixel = true
                            overlayColor = Color.rgb(115, 80, 50) // Vintage Topographic Brown
                        } else if (isGridMeridian) {
                            isOverlayPixel = true
                            overlayColor = Color.rgb(50, 80, 115) // Deep ocean surveyor grid blue
                        }
                    }

                    if (isOverlayPixel) {
                        val ro = Color.red(overlayColor)
                        val go = Color.green(overlayColor)
                        val bo = Color.blue(overlayColor)

                        val rf = (ro * overlayOpacity + Color.red(finalColor) * (1f - overlayOpacity)).toInt().coerceIn(0, 255)
                        val gf = (go * overlayOpacity + Color.green(finalColor) * (1f - overlayOpacity)).toInt().coerceIn(0, 255)
                        val bf = (bo * overlayOpacity + Color.blue(finalColor) * (1f - overlayOpacity)).toInt().coerceIn(0, 255)
                        finalColor = Color.rgb(rf, gf, bf)
                    }
                }

                pixels[y * width + x] = finalColor
            }
        }

        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun coerceIndex(x: Int, y: Int): Int {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return cy * width + cx
    }

    /**
     * Color palettes for LiDAR elevation mapping.
     */
    private fun getPaletteColor(palette: Int, pct: Float): Int {
        return when (palette) {
            // 0: Clay / Greyscale (Excellent for archeology foundations)
            0 -> {
                val shade = (150 + (pct * 60)).toInt().coerceIn(0, 255)
                Color.rgb(shade, shade, shade)
            }
            // 1: LiDAR Copper (Deep Navy blue/black to hot glowing copper)
            1 -> {
                // Multi-stop gradient: Navy (0) -> Maroon (0.3) -> Copper (0.7) -> Gold (1.0)
                if (pct < 0.3f) {
                    val p = pct / 0.3f
                    Color.rgb(
                        (15 * (1f - p) + 80 * p).toInt(),
                        (20 * (1f - p) + 15 * p).toInt(),
                        (45 * (1f - p) + 20 * p).toInt()
                    )
                } else if (pct < 0.7f) {
                    val p = (pct - 0.3f) / 0.4f
                    Color.rgb(
                        (80 * (1f - p) + 200 * p).toInt(),
                        (15 * (1f - p) + 90 * p).toInt(),
                        (20 * (1f - p) + 40 * p).toInt()
                    )
                } else {
                    val p = (pct - 0.7f) / 0.3f
                    Color.rgb(
                        (200 * (1f - p) + 250 * p).toInt(),
                        (90 * (1f - p) + 180 * p).toInt(),
                        (40 * (1f - p) + 70 * p).toInt()
                    )
                }
            }
            // 2: Terra / Topographical (Green valleys to brown mountains, white peaks)
            2 -> {
                if (pct < 0.2f) { // Valley: Deep green
                    val p = pct / 0.2f
                    Color.rgb(
                        (34 * (1f - p) + 46 * p).toInt(),
                        (112 * (1f - p) + 139 * p).toInt(),
                        (63 * (1f - p) + 87 * p).toInt()
                    )
                } else if (pct < 0.6f) { // Forest/Plain: Olive Green to Yellow
                    val p = (pct - 0.2f) / 0.4f
                    Color.rgb(
                        (46 * (1f - p) + 196 * p).toInt(),
                        (139 * (1f - p) + 183 * p).toInt(),
                        (87 * (1f - p) + 101 * p).toInt()
                    )
                } else if (pct < 0.85f) { // Slopes: Yellow to Terracotta Brown
                    val p = (pct - 0.6f) / 0.25f
                    Color.rgb(
                        (196 * (1f - p) + 130 * p).toInt(),
                        (183 * (1f - p) + 80 * p).toInt(),
                        (101 * (1f - p) + 40 * p).toInt()
                    )
                } else { // Peaks: Mountain Dark Brown to Snow-ish Grey
                    val p = (pct - 0.85f) / 0.15f
                    Color.rgb(
                        (130 * (1f - p) + 210 * p).toInt(),
                        (80 * (1f - p) + 210 * p).toInt(),
                        (40 * (1f - p) + 215 * p).toInt()
                    )
                }
            }
            else -> Color.GRAY
        }
    }
}
