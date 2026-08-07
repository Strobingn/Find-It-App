package com.example.data.historicmap

import com.example.data.field.BoundaryVertex
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Offline auto-extraction of roads / structures / walls / boundaries from a scanned historic
 * map image. Uses ink-threshold + connected components — not a neural CV model.
 *
 * Requires a [GeoReferenceTransform] to emit real-world [HistoricMapFeature] polylines.
 * Confidence is intentionally moderate; results are operator-reviewable drafts.
 */
object HistoricMapFeatureExtractor {

    const val MAX_ANALYSIS_SIDE: Int = 256
    const val MAX_FEATURES: Int = 24
    const val MIN_COMPONENT_PIXELS: Int = 18

    data class ImagePoint(val x: Float, val y: Float)

    data class ExtractedComponent(
        val type: MapFeatureType,
        val imagePoints: List<ImagePoint>,
        val pixelCount: Int,
        val aspect: Float,
        val confidence: Float,
    )

    data class ExtractionResult(
        val features: List<HistoricMapFeature>,
        val componentCount: Int,
        val analysisWidth: Int,
        val analysisHeight: Int,
        val note: String,
    )

    /**
     * @param pixels ARGB or greyscale packed pixels (row-major)
     * @param width image width in pixels
     * @param height image height in pixels
     * @param mapId parent historic map id
     * @param transform image→world affine (required for lat/lon features)
     */
    fun extract(
        pixels: IntArray,
        width: Int,
        height: Int,
        mapId: String,
        transform: GeoReferenceTransform,
        maxFeatures: Int = MAX_FEATURES,
        nowMillis: Long = System.currentTimeMillis(),
    ): ExtractionResult {
        require(width > 1 && height > 1) { "image too small" }
        require(pixels.size >= width * height) { "pixel buffer too short" }

        val scale = max(width, height).toFloat() / MAX_ANALYSIS_SIDE.toFloat()
        val aw = if (scale <= 1f) width else (width / scale).roundToInt().coerceAtLeast(2)
        val ah = if (scale <= 1f) height else (height / scale).roundToInt().coerceAtLeast(2)
        val sx = width.toFloat() / aw
        val sy = height.toFloat() / ah

        val grey = FloatArray(aw * ah)
        var sum = 0.0
        for (y in 0 until ah) {
            for (x in 0 until aw) {
                val srcX = min(width - 1, (x * sx).toInt())
                val srcY = min(height - 1, (y * sy).toInt())
                val c = pixels[srcY * width + srcX]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                grey[y * aw + x] = lum
                sum += lum
            }
        }
        val mean = (sum / grey.size).toFloat()
        // Dark ink on light paper (typical plats). Invert threshold if map is dark-background.
        val darkBg = mean < 90f
        val threshold = if (darkBg) mean + 35f else mean * 0.72f
        val ink = BooleanArray(grey.size) { i ->
            if (darkBg) grey[i] > threshold else grey[i] < threshold
        }

        val components = labelComponents(ink, aw, ah)
            .filter { it.pixels.size >= MIN_COMPONENT_PIXELS }
            .sortedByDescending { it.pixels.size }
            .take(maxFeatures * 2)

        val extracted = components.mapNotNull { classifyAndPolyline(it, aw, ah) }
            .sortedByDescending { it.confidence * it.pixelCount }
            .take(maxFeatures)

        val features = extracted.mapNotNull { comp ->
            val world = comp.imagePoints.map { pt ->
                // Scale analysis coords back to full image pixels
                val ix = pt.x * sx
                val iy = pt.y * sy
                val (lat, lon) = transform.imageToWorld(ix, iy)
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    return@mapNotNull null
                }
                BoundaryVertex(lat, lon)
            }
            if (world.size < 1) return@mapNotNull null
            // Collapse near-duplicate consecutive points
            val simplified = simplifyVertices(world, minMeters = 1.5)
            if (simplified.isEmpty()) return@mapNotNull null
            HistoricMapFeature(
                id = UUID.randomUUID().toString(),
                mapId = mapId,
                type = comp.type,
                points = simplified,
                confidence = comp.confidence,
                note = "Auto-extract · ${comp.type.label} · ${comp.pixelCount}px · aspect=${"%.1f".format(comp.aspect)}",
                createdAtMillis = nowMillis,
            )
        }

        return ExtractionResult(
            features = features,
            componentCount = components.size,
            analysisWidth = aw,
            analysisHeight = ah,
            note = if (features.isEmpty()) {
                "No ink components met size thresholds — try a higher-contrast scan or add features manually."
            } else {
                "Extracted ${features.size} draft feature(s) from ${components.size} ink region(s). Review before trusting."
            },
        )
    }

    // ------------------------------------------------------------------

    private data class RawComponent(val pixels: List<Pair<Int, Int>>)

    private fun labelComponents(ink: BooleanArray, w: Int, h: Int): List<RawComponent> {
        val seen = BooleanArray(ink.size)
        val out = ArrayList<RawComponent>()
        val q = ArrayDeque<Int>()
        for (i in ink.indices) {
            if (!ink[i] || seen[i]) continue
            q.clear()
            q.add(i)
            seen[i] = true
            val pts = ArrayList<Pair<Int, Int>>(64)
            while (q.isNotEmpty()) {
                val idx = q.removeFirst()
                val x = idx % w
                val y = idx / w
                pts.add(x to y)
                // 4-connected
                val neighbors = intArrayOf(
                    if (x > 0) idx - 1 else -1,
                    if (x + 1 < w) idx + 1 else -1,
                    if (y > 0) idx - w else -1,
                    if (y + 1 < h) idx + w else -1,
                )
                for (n in neighbors) {
                    if (n < 0 || seen[n] || !ink[n]) continue
                    seen[n] = true
                    q.add(n)
                }
            }
            out.add(RawComponent(pts))
        }
        return out
    }

    private fun classifyAndPolyline(comp: RawComponent, w: Int, h: Int): ExtractedComponent? {
        val pts = comp.pixels
        if (pts.size < MIN_COMPONENT_PIXELS) return null
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var sumX = 0.0
        var sumY = 0.0
        for ((x, y) in pts) {
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            sumX += x
            sumY += y
        }
        val bw = (maxX - minX + 1).coerceAtLeast(1)
        val bh = (maxY - minY + 1).coerceAtLeast(1)
        val aspect = max(bw, bh).toFloat() / min(bw, bh).toFloat()
        val fill = pts.size.toFloat() / (bw * bh).toFloat()
        val cx = (sumX / pts.size).toFloat()
        val cy = (sumY / pts.size).toFloat()
        val nearBorder = minX <= 2 || minY <= 2 || maxX >= w - 3 || maxY >= h - 3
        val sizeFrac = pts.size.toFloat() / (w * h).toFloat()

        val type = when {
            nearBorder && aspect >= 3.5f && sizeFrac > 0.01f -> MapFeatureType.BOUNDARY
            aspect >= 4.5f && fill < 0.45f -> MapFeatureType.ROAD
            aspect >= 3.2f && fill < 0.55f -> MapFeatureType.WALL
            aspect < 2.8f && fill > 0.25f && pts.size in 25..2_500 -> MapFeatureType.STRUCTURE
            aspect >= 2.5f -> MapFeatureType.ROAD
            else -> MapFeatureType.STRUCTURE
        }

        val polyline = if (aspect >= 2.5f) {
            // Sample along major axis using extreme points
            val majorHorizontal = bw >= bh
            val extremes = if (majorHorizontal) {
                val left = pts.minBy { it.first }
                val right = pts.maxBy { it.first }
                listOf(left, right)
            } else {
                val top = pts.minBy { it.second }
                val bot = pts.maxBy { it.second }
                listOf(top, bot)
            }
            val mid = pts.sortedBy {
                if (majorHorizontal) it.first else it.second
            }
            val midPt = mid[mid.size / 2]
            listOf(
                ImagePoint(extremes[0].first.toFloat(), extremes[0].second.toFloat()),
                ImagePoint(midPt.first.toFloat(), midPt.second.toFloat()),
                ImagePoint(extremes[1].first.toFloat(), extremes[1].second.toFloat()),
            ).distinct()
        } else {
            // Compact structure: centroid + bbox corners (as short ring-ish sample)
            listOf(
                ImagePoint(cx, cy),
                ImagePoint(minX.toFloat(), minY.toFloat()),
                ImagePoint(maxX.toFloat(), maxY.toFloat()),
            )
        }

        val conf = when (type) {
            MapFeatureType.ROAD -> (0.45f + 0.08f * min(aspect, 8f)).coerceIn(0.4f, 0.85f)
            MapFeatureType.WALL -> (0.40f + 0.06f * min(aspect, 8f)).coerceIn(0.35f, 0.8f)
            MapFeatureType.STRUCTURE -> (0.40f + 0.3f * fill).coerceIn(0.35f, 0.8f)
            MapFeatureType.BOUNDARY -> (0.35f + 0.05f * min(aspect, 10f)).coerceIn(0.3f, 0.75f)
        }

        return ExtractedComponent(
            type = type,
            imagePoints = polyline,
            pixelCount = pts.size,
            aspect = aspect,
            confidence = conf,
        )
    }

    private fun simplifyVertices(
        points: List<BoundaryVertex>,
        minMeters: Double,
    ): List<BoundaryVertex> {
        if (points.size <= 2) return points
        val out = ArrayList<BoundaryVertex>(points.size)
        out.add(points.first())
        for (i in 1 until points.lastIndex) {
            val prev = out.last()
            val cur = points[i]
            val d = hypot(
                (cur.latitude - prev.latitude) * 111_000.0,
                (cur.longitude - prev.longitude) * 85_000.0,
            )
            if (d >= minMeters) out.add(cur)
        }
        out.add(points.last())
        return out.distinctBy { "${"%.6f".format(it.latitude)},${"%.6f".format(it.longitude)}" }
    }
}
